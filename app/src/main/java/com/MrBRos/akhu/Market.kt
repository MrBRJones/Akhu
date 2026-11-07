package com.MrBRos.akhu

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.android.billingclient.api.Purchase
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.functions.FirebaseFunctions
import java.security.MessageDigest
import java.util.Locale

/**
 * Selling posts: the price tiers, the revenue split and which kinds of post only Premium members
 * may sell. They're settings in Firestore's `config/market` document (edited in the Firebase
 * console), not constants; the values below are only used until it loads, or if it's missing.
 *
 * Every tier is a one-time, consumable Play product named `akhu_item_<cents>` (akhu_item_1999 is
 * $19.99 in the US), so any post can be sold at any tier and the same tier bought again for others.
 */
object Market {

    const val CONFIG_COLLECTION = "config"
    const val CONFIG_DOC = "market"
    private const val PRODUCT_PREFIX = "akhu_item_"

    /** What a post is, for the Premium-only list ([FirebaseHelper]'s media types, plus themes). */
    const val KIND_THEME = "theme"

    /** $0.99 to $19.99 in dollar steps, then $24.99 to $49.99 in fives, then $59.99 to $99.99 in tens. */
    private val DEFAULT_TIERS: List<String> =
        ((99..1999 step 100) + (2499..4999 step 500) + (5999..9999 step 1000)).map(::productId)

    data class Config(
        /** Product IDs, cheapest first. */
        val tiers: List<String> = DEFAULT_TIERS,
        /** Shares of the price before tax, in percent; they add up to 100. */
        val sellerPercent: Int = 70,
        val appPercent: Int = 15,
        val googlePercent: Int = 15,
        /** Kinds of post (media type or [KIND_THEME]) only Premium members may sell. */
        val premiumOnly: Set<String> = setOf(FirebaseHelper.MEDIA_VIDEO, FirebaseHelper.MEDIA_REACT, KIND_THEME),
    )

    var config by mutableStateOf(Config())
        private set

    /** Prices as Play shows them in the buyer's currency, by product ID, once Play answers. */
    var prices by mutableStateOf<Map<String, String>>(emptyMap())
        private set

    private val main = Handler(Looper.getMainLooper())

    @JvmStatic
    fun productId(cents: Int): String = PRODUCT_PREFIX + String.format(Locale.US, "%04d", cents)

    /** The US price in cents a tier stands for, or null for an ID that isn't a tier. */
    @JvmStatic
    fun cents(productId: String?): Int? =
        productId?.takeIf { it.startsWith(PRODUCT_PREFIX) }?.removePrefix(PRODUCT_PREFIX)?.toIntOrNull()

    @JvmStatic
    fun isTier(productId: String?): Boolean = productId != null && productId in config.tiers

    /** The tier's price for showing: Play's local price, else the US price it was named for. */
    @JvmStatic
    fun price(productId: String): String =
        prices[productId] ?: cents(productId)?.let { String.format(Locale.US, "$%d.%02d", it / 100, it % 100) } ?: productId

    /** Whether this account may sell a post of [kind] (a media type, or [KIND_THEME]). */
    @JvmStatic
    fun canSell(kind: String): Boolean = kind !in config.premiumOnly || Premium.owned()

    private lateinit var app: Context

    /** Reads the settings, then asks Play for the tiers' local prices. Called from [App]. */
    @JvmStatic
    fun load(context: Context) {
        app = context.applicationContext
        FirebaseFirestore.getInstance().collection(CONFIG_COLLECTION).document(CONFIG_DOC).get()
            .addOnSuccessListener { doc -> parse(doc)?.let { config = it } }
            .addOnCompleteListener { Premium.queryPrices(config.tiers) { found -> main.post { prices = found } } }
    }

    /**
     * The buyer as Play records them on a purchase (its obfuscated account ID): a hash of the
     * Firebase uid, which the server recomputes to check a purchase is the caller's.
     */
    @JvmStatic
    fun accountId(uid: String): String =
        MessageDigest.getInstance("SHA-256").digest(uid.toByteArray()).joinToString("") { "%02x".format(it) }

    /** Told the post ID when a purchase of it has gone through, so its page can unlock. */
    private val claimedListeners = mutableListOf<(String) -> Unit>()

    @JvmStatic
    fun observeClaims(listener: (String) -> Unit) {
        claimedListeners += listener
    }

    @JvmStatic
    fun stopObservingClaims(listener: (String) -> Unit) {
        claimedListeners -= listener
    }

    /**
     * Opens Play's purchase sheet for [post] at its price, tagged with the buyer and the post so the
     * server can tell the purchase is for exactly this. [onUnavailable] runs if Play can't sell it now.
     */
    @JvmStatic
    fun buy(activity: Activity, post: CloudWallpaper, onUnavailable: Runnable) {
        val user = FirebaseHelper.currentUser(activity)
        val tier = post.tier
        if (user == null || tier == null || !isTier(tier)) return onUnavailable.run()
        Premium.buyTier(activity, tier, accountId(user.uid), post.id) { onUnavailable.run() }
    }

    /** Purchase tokens being claimed now, so Play's report and a launch check don't claim one twice. */
    private val claiming = mutableSetOf<String>()

    /**
     * A tier purchase Play reports, just made or left over (e.g. the app closed before it was
     * claimed). The server checks it with Play, unlocks the post and consumes the purchase.
     * Purchases tagged for another Akhu account on this phone are left for that account.
     */
    internal fun claim(purchase: Purchase) {
        val user = FirebaseHelper.currentUser(app) ?: return
        val ids = purchase.accountIdentifiers ?: return
        val postId = ids.obfuscatedProfileId ?: return
        if (ids.obfuscatedAccountId != accountId(user.uid) || !claiming.add(purchase.purchaseToken)) return
        call("claimPurchase", mapOf("token" to purchase.purchaseToken, "productId" to purchase.products.first(), "postId" to postId))
            .addOnSuccessListener {
                Toast.makeText(app, R.string.purchase_done, Toast.LENGTH_SHORT).show()
                claimedListeners.toList().forEach { it(postId) }
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "Claim failed", e)
                Toast.makeText(app, app.getString(R.string.purchase_failed, e.message ?: ""), Toast.LENGTH_LONG).show()
            }
            .addOnCompleteListener { claiming -= purchase.purchaseToken }
    }

    /**
     * Records Premium on the signed-in Akhu account, once per account and purchase, so the rules
     * let it sell the Premium-only kinds. Quiet on failure: Premium still works in the app.
     */
    internal fun claimPremium(purchase: Purchase) {
        val user = FirebaseHelper.currentUser(app) ?: return
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = KEY_PREMIUM_CLAIMED + user.uid
        if (prefs.getString(key, null) == purchase.purchaseToken || !claiming.add(purchase.purchaseToken)) return
        call("claimPremium", mapOf("token" to purchase.purchaseToken))
            .addOnSuccessListener { prefs.edit().putString(key, purchase.purchaseToken).apply() }
            .addOnFailureListener { e -> Log.w(TAG, "Premium claim failed", e) }
            .addOnCompleteListener { claiming -= purchase.purchaseToken }
    }

    private fun call(name: String, data: Map<String, Any>) =
        FirebaseFunctions.getInstance().getHttpsCallable(name).call(data)

    private const val TAG = "Market"
    private const val PREFS = "market"
    private const val KEY_PREMIUM_CLAIMED = "premium_claimed_"

    private fun parse(doc: DocumentSnapshot): Config? {
        if (!doc.exists()) return null
        val d = Config()
        val tiers = (doc.get("tiers") as? List<*>)?.filterIsInstance<String>()?.filter { cents(it) != null }
        val split = doc.get("split") as? Map<*, *>
        fun share(key: String, fallback: Int) = (split?.get(key) as? Number)?.toInt() ?: fallback
        val seller = share("seller", d.sellerPercent)
        val appShare = share("app", d.appPercent)
        val google = share("google", d.googlePercent)
        val premiumOnly = (doc.get("premiumOnly") as? List<*>)?.filterIsInstance<String>()?.toSet()
        return Config(
            tiers = tiers?.takeIf { it.isNotEmpty() }?.sortedBy { cents(it) } ?: d.tiers,
            // A split that doesn't add up is a typo in the console; the defaults stand instead.
            sellerPercent = if (seller + appShare + google == 100) seller else d.sellerPercent,
            appPercent = if (seller + appShare + google == 100) appShare else d.appPercent,
            googlePercent = if (seller + appShare + google == 100) google else d.googlePercent,
            premiumOnly = premiumOnly ?: d.premiumOnly,
        )
    }
}
