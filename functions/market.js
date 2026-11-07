// Selling: the pure parts (IDs and the money split), kept apart from Firebase so they're easy to check.
const crypto = require('crypto');

const PRODUCT_PREFIX = 'akhu_item_';

// Used when config/market is missing a value; the app has the same defaults (Market.kt).
const DEFAULT_SPLIT = { seller: 70, app: 15, google: 15 };

/** The US price in cents a tier product stands for (akhu_item_1999 -> 1999), or null. */
function cents(productId) {
  const m = /^akhu_item_(\d{4,})$/.exec(typeof productId === 'string' ? productId : '');
  return m ? parseInt(m[1], 10) : null;
}

/**
 * What the app attaches to a purchase as the buyer (Play's obfuscated account ID): a hash of the
 * Firebase uid, so Play never sees the uid itself. Market.accountId makes the same one.
 */
function accountId(uid) {
  return crypto.createHash('sha256').update(uid).digest('hex');
}

/** A purchase token's document ID: tokens are long and may hold characters IDs can't. */
function tokenId(token) {
  return crypto.createHash('sha256').update(token).digest('hex');
}

/** The split from config/market's {seller, app, google} percentages, or the defaults if they don't add up to 100. */
function split(config) {
  const s = config && config.split;
  const ok = s && [s.seller, s.app, s.google].every((n) => Number.isInteger(n) && n >= 0)
    && s.seller + s.app + s.google === 100;
  return ok ? { seller: s.seller, app: s.app, google: s.google } : DEFAULT_SPLIT;
}

/**
 * How a sale's US price divides. The seller's share is rounded down and Google's to the nearest
 * cent; Akhu's is what's left, so the three always add up to the price.
 */
function shares(priceCents, config) {
  const s = split(config);
  const seller = Math.floor(priceCents * s.seller / 100);
  const google = Math.round(priceCents * s.google / 100);
  return { seller, google, app: priceCents - seller - google };
}

module.exports = { PRODUCT_PREFIX, DEFAULT_SPLIT, cents, accountId, tokenId, split, shares };
