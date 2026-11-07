// Akhu's server side. The app never records a sale or Premium itself: it hands Google Play's
// purchase token to these functions, which check it with Play before writing anything.
//
// Firestore written here (the rules let no app write them):
//   sales/{tokenId}                   one per sale; the seller can read it
//   sales/{tokenId}/server/buyer      who bought it (kept from the seller)
//   users/{uid}/purchases/{postId}    what {uid} bought; unlocks <post>/paid/content
//   users/{uid}/private/earnings      pendingCents (in the 30-day hold), payableCents, paidCents
//   users/{uid}/private/entitlements  premium
//   premiumTokens/{tokenId}           which account a Premium purchase belongs to
//
// Play access: this project's service account needs Play Console access to the app (View
// financial data, Manage orders and subscriptions).

const { onCall, HttpsError } = require('firebase-functions/v2/https');
const { onSchedule } = require('firebase-functions/v2/scheduler');
const logger = require('firebase-functions/logger');
const { initializeApp } = require('firebase-admin/app');
const { getFirestore, FieldValue, Timestamp } = require('firebase-admin/firestore');
const { google } = require('googleapis');
const { cents, accountId, tokenId, shares } = require('./market');

initializeApp();
const db = getFirestore();

const PACKAGE = 'com.MrBRos.akhu';
const PREMIUM = 'akhu_premium';
/** Days a sale's earnings wait before they can be paid out, so a refund can't follow a payout. */
const HOLD_DAYS = 30;
/** Play's purchaseState values. */
const PURCHASED = 0;
const PENDING = 2;

let publisher;
function play() {
  if (!publisher) {
    const auth = new google.auth.GoogleAuth({ scopes: ['https://www.googleapis.com/auth/androidpublisher'] });
    publisher = google.androidpublisher({ version: 'v3', auth });
  }
  return publisher;
}

async function purchase(productId, token) {
  try {
    return (await play().purchases.products.get({ packageName: PACKAGE, productId, token })).data;
  } catch (e) {
    logger.warn('Play purchase lookup failed', { productId, error: e.message });
    throw new HttpsError('not-found', 'Google Play doesn\'t know this purchase.');
  }
}

/** Closes a tier purchase so the same tier can be bought again (for another post). */
async function consume(productId, token) {
  await play().purchases.products.consume({ packageName: PACKAGE, productId, token });
}

/** Refunds a purchase that can't be honoured (and takes back what it bought). */
async function refund(orderId) {
  try {
    await play().orders.refund({ packageName: PACKAGE, orderId, revoke: true });
  } catch (e) {
    // Logged for a manual refund in Play Console (Order management).
    logger.error('Refund failed: refund this order by hand', { orderId, error: e.message });
  }
}

async function marketConfig() {
  const doc = await db.doc('config/market').get();
  return doc.exists ? doc.data() : {};
}

/** The post for sale and its collection; posts are in wallpapers or themes. */
async function findPost(postId) {
  for (const collection of ['wallpapers', 'themes']) {
    const doc = await db.collection(collection).doc(postId).get();
    if (doc.exists) return { collection, post: doc.data() };
  }
  return { collection: null, post: null };
}

function signedIn(req) {
  const uid = req.auth && req.auth.uid;
  if (!uid) throw new HttpsError('unauthenticated', 'Sign in first.');
  return uid;
}

/**
 * The app calls this after Play says a tier purchase went through, and again at launch for any
 * left unconsumed. It checks the purchase is paid, the caller's, for this post at its current
 * price; then records the sale, unlocks the post for the buyer, credits the seller and consumes
 * the purchase. Calling it twice for one purchase is harmless.
 */
exports.claimPurchase = onCall(async (req) => {
  const uid = signedIn(req);
  const { token, productId, postId } = req.data || {};
  if (typeof token !== 'string' || typeof postId !== 'string' || cents(productId) == null) {
    throw new HttpsError('invalid-argument', 'That isn\'t a purchase Akhu sells.');
  }
  const p = await purchase(productId, token);
  if (p.purchaseState === PENDING) throw new HttpsError('failed-precondition', 'The payment is still pending.');
  if (p.purchaseState !== PURCHASED) throw new HttpsError('failed-precondition', 'The payment didn\'t go through.');
  if (p.obfuscatedExternalAccountId !== accountId(uid) || p.obfuscatedExternalProfileId !== postId) {
    throw new HttpsError('permission-denied', 'This purchase belongs to another account or post.');
  }

  const saleRef = db.collection('sales').doc(tokenId(token));
  if ((await saleRef.get()).exists) {
    // Already recorded; the consume may not have gone through last time.
    if (p.consumptionState === 0) await consume(productId, token);
    return { ok: true };
  }

  const { collection, post } = await findPost(postId);
  let problem = null;
  if (!post) problem = 'This post has been deleted.';
  else if (post.forSale !== true || post.tier !== productId) problem = 'The price of this post changed.';
  else if (post.uid === uid) problem = 'This is your own post.';
  else if ((await db.doc(`users/${uid}/purchases/${postId}`).get()).exists) problem = 'You already own this.';
  if (problem) {
    await refund(p.orderId);
    throw new HttpsError('failed-precondition', `${problem} Your payment is being refunded.`);
  }

  const price = cents(productId);
  const share = shares(price, await marketConfig());
  // License testers' purchases (Play's test cards) unlock the post but earn the seller nothing.
  const test = p.purchaseType === 0;
  const payableAt = Timestamp.fromMillis(Date.now() + HOLD_DAYS * 24 * 3600 * 1000);
  await db.runTransaction(async (tx) => {
    if ((await tx.get(saleRef)).exists) return;
    tx.set(saleRef, {
      post: postId,
      collection,
      title: typeof post.title === 'string' ? post.title : '',
      seller: post.uid,
      tier: productId,
      priceCents: price,
      sellerCents: share.seller,
      appCents: share.app,
      googleCents: share.google,
      status: test ? 'test' : 'pending',
      orderId: p.orderId || null,
      at: FieldValue.serverTimestamp(),
      payableAt,
    });
    tx.set(saleRef.collection('server').doc('buyer'), { uid });
    tx.set(db.doc(`users/${uid}/purchases/${postId}`), { collection, sale: saleRef.id, at: FieldValue.serverTimestamp() });
    if (!test) {
      tx.set(db.doc(`users/${post.uid}/private/earnings`),
        { pendingCents: FieldValue.increment(share.seller), salesCount: FieldValue.increment(1) }, { merge: true });
    }
    tx.update(db.collection(collection).doc(postId), { salesCount: FieldValue.increment(1) });
  });
  await consume(productId, token);
  return { ok: true };
});

/**
 * The app calls this when Play says this Google account owns Premium. It records Premium on the
 * caller's Akhu account (the rules check it for selling motion, reactive and theme posts). One
 * purchase belongs to the first Akhu account that claims it.
 */
exports.claimPremium = onCall(async (req) => {
  const uid = signedIn(req);
  const { token } = req.data || {};
  if (typeof token !== 'string') throw new HttpsError('invalid-argument', 'Missing purchase.');
  const p = await purchase(PREMIUM, token);
  if (p.purchaseState !== PURCHASED) throw new HttpsError('failed-precondition', 'The payment didn\'t go through.');
  // Purchases made before the app tagged them have no account; the first claim links them.
  if (p.obfuscatedExternalAccountId && p.obfuscatedExternalAccountId !== accountId(uid)) {
    throw new HttpsError('permission-denied', 'This Premium purchase belongs to another Akhu account.');
  }
  const ref = db.collection('premiumTokens').doc(tokenId(token));
  await db.runTransaction(async (tx) => {
    const linked = await tx.get(ref);
    if (linked.exists && linked.data().uid !== uid) {
      throw new HttpsError('permission-denied', 'This Premium purchase belongs to another Akhu account.');
    }
    if (!linked.exists) tx.set(ref, { uid, orderId: p.orderId || null, at: FieldValue.serverTimestamp() });
    tx.set(db.doc(`users/${uid}/private/entitlements`), { premium: true }, { merge: true });
  });
  return { premium: true };
});

/** Takes back a refunded or charged-back sale or Premium purchase. */
async function reverse(voided) {
  const id = tokenId(voided.purchaseToken);
  const saleRef = db.collection('sales').doc(id);
  await db.runTransaction(async (tx) => {
    const sale = await tx.get(saleRef);
    if (!sale.exists || sale.data().status === 'refunded') return;
    const s = sale.data();
    const buyer = await tx.get(saleRef.collection('server').doc('buyer'));
    // A sale already paid out comes off what's payable next (the balance can go below zero).
    // Test sales never earned anything.
    if (s.status !== 'test') {
      const from = s.status === 'pending' ? 'pendingCents' : 'payableCents';
      tx.set(db.doc(`users/${s.seller}/private/earnings`),
        { [from]: FieldValue.increment(-s.sellerCents), refundsCount: FieldValue.increment(1) }, { merge: true });
    }
    tx.update(saleRef, { status: 'refunded', statusBefore: s.status, refundedAt: FieldValue.serverTimestamp() });
    if (buyer.exists) tx.delete(db.doc(`users/${buyer.data().uid}/purchases/${s.post}`));
  });
  const premium = await db.collection('premiumTokens').doc(id).get();
  if (premium.exists && !premium.data().voided) {
    await db.doc(`users/${premium.data().uid}/private/entitlements`).set({ premium: false }, { merge: true });
    await premium.ref.update({ voided: true });
  }
}

/** Daily: Play's refunds and chargebacks from the last few days, reversed (repeats are skipped). */
exports.voidedPurchases = onSchedule({ schedule: 'every day 03:00', timeZone: 'Etc/UTC' }, async () => {
  const startTime = String(Date.now() - 3 * 24 * 3600 * 1000);
  let token;
  do {
    const { data } = await play().purchases.voidedpurchases.list(
      { packageName: PACKAGE, startTime, type: 0, maxResults: 1000, token });
    for (const v of data.voidedPurchases || []) await reverse(v);
    token = data.tokenPagination && data.tokenPagination.nextPageToken;
  } while (token);
});

/** Daily: sales past the 30-day hold move from pending to payable. */
exports.releaseEarnings = onSchedule({ schedule: 'every day 04:00', timeZone: 'Etc/UTC' }, async () => {
  const due = await db.collection('sales').where('status', '==', 'pending')
    .where('payableAt', '<=', Timestamp.now()).limit(500).get();
  for (const doc of due.docs) {
    await db.runTransaction(async (tx) => {
      const s = (await tx.get(doc.ref)).data();
      if (s.status !== 'pending') return;
      tx.update(doc.ref, { status: 'payable' });
      tx.set(db.doc(`users/${s.seller}/private/earnings`),
        { pendingCents: FieldValue.increment(-s.sellerCents), payableCents: FieldValue.increment(s.sellerCents) },
        { merge: true });
    });
  }
});
