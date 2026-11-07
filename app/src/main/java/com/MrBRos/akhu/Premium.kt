package com.MrBRos.akhu

import android.app.Activity
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams

/**
 * Akhu Premium: a one-time Google Play purchase that removes ads and unlocks Premium-only
 * features (reactive wallpapers). Play keeps the purchase, so it comes back on a new phone or reinstall; the last
 * known answer is cached so the app knows offline. Compose reads [isPremium] and [price] as state.
 */
object Premium {

    /** The one-time product's ID, as created in the Play Console (In-app products). */
    const val PRODUCT_ID = "akhu_premium"

    private const val PREFS = "premium"
    private const val KEY_OWNED = "owned"
    /** Debug builds only: set to true with adb to see the app as Premium without buying. */
    private const val KEY_DEBUG_OWNED = "debug_owned"

    var isPremium by mutableStateOf(false)
        private set
    /** The price as Play shows it in the user's currency, or null until Play answers. */
    var price by mutableStateOf<String?>(null)
        private set

    private lateinit var app: Context
    private var client: BillingClient? = null
    private var details: ProductDetails? = null
    private val listeners = mutableListOf<(Boolean) -> Unit>()
    /** Play may answer on its own thread; state and listeners change on the main one. */
    private val main = Handler(Looper.getMainLooper())

    /** Called once from [App.onCreate]: reads the cached answer, then asks Play. */
    @JvmStatic
    fun init(context: Context) {
        app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val debuggable = app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        isPremium = prefs.getBoolean(KEY_OWNED, false) || (debuggable && prefs.getBoolean(KEY_DEBUG_OWNED, false))
        connect()
    }

    /** [isPremium] for Java callers. */
    @JvmStatic
    fun owned(): Boolean = isPremium

    /** Runs [listener] now and whenever Premium turns on or off (for screens built with views). */
    @JvmStatic
    fun observe(listener: (Boolean) -> Unit) {
        listeners += listener
        listener(isPremium)
    }

    @JvmStatic
    fun stopObserving(listener: (Boolean) -> Unit) {
        listeners -= listener
    }

    private fun connect() {
        val c = BillingClient.newBuilder(app)
            .setListener { result, purchases -> if (result.responseCode == BillingClient.BillingResponseCode.OK) handle(purchases.orEmpty()) }
            .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
            .build()
        client = c
        c.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode != BillingClient.BillingResponseCode.OK) return
                refresh()
                loadDetails()
                main.post {
                    val waiting = pendingPriceQueries.toList()
                    pendingPriceQueries.clear()
                    waiting.forEach { it() }
                }
            }

            // Reconnects on the next purchase or launch.
            override fun onBillingServiceDisconnected() {}
        })
    }

    /** Asks Play what this account owns (also how a purchase is restored). */
    @JvmStatic
    fun refresh() {
        val c = client ?: return
        if (!c.isReady) return connect()
        c.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build()) { result, purchases ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) handle(purchases, complete = true)
        }
    }

    /** Tier price lookups waiting for Play to connect (touched on the main thread only). */
    private val pendingPriceQueries = mutableListOf<() -> Unit>()

    /**
     * Asks Play for the local prices of one-time products [ids] (the [Market] tiers) and passes
     * the ones it knows to [onPrices], on Play's thread. Products not set up yet are left out.
     */
    @JvmStatic
    fun queryPrices(ids: List<String>, onPrices: (Map<String, String>) -> Unit) {
        val c = client
        if (c == null || !c.isReady) {
            pendingPriceQueries += { queryPrices(ids, onPrices) }
            return
        }
        val products = ids.map {
            QueryProductDetailsParams.Product.newBuilder().setProductId(it).setProductType(BillingClient.ProductType.INAPP).build()
        }
        c.queryProductDetailsAsync(QueryProductDetailsParams.newBuilder().setProductList(products).build()) { result, found ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) return@queryProductDetailsAsync
            onPrices(found.productDetailsList.mapNotNull { d -> d.oneTimePurchaseOfferDetails?.let { d.productId to it.formattedPrice } }.toMap())
        }
    }

    private fun loadDetails() {
        val product = QueryProductDetailsParams.Product.newBuilder()
            .setProductId(PRODUCT_ID).setProductType(BillingClient.ProductType.INAPP).build()
        client?.queryProductDetailsAsync(QueryProductDetailsParams.newBuilder().setProductList(listOf(product)).build()) { result, found ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) return@queryProductDetailsAsync
            val d = found.productDetailsList.firstOrNull()
            main.post {
                details = d
                price = d?.oneTimePurchaseOfferDetails?.formattedPrice
            }
        }
    }

    /**
     * Opens Play's purchase sheet. Returns false when Play can't sell it right now (not connected,
     * or the product isn't set up in the Play Console yet).
     */
    @JvmStatic
    fun buy(activity: Activity): Boolean {
        val c = client
        val d = details
        if (c == null || !c.isReady || d == null) {
            refresh()
            return false
        }
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(d).build()))
        // Tagged with the Akhu account, so the server can link Premium to it (see Market.claimPremium).
        FirebaseHelper.currentUser(activity)?.let { params.setObfuscatedAccountId(Market.accountId(it.uid)) }
        return c.launchBillingFlow(activity, params.build()).responseCode == BillingClient.BillingResponseCode.OK
    }

    /**
     * Opens Play's purchase sheet for a [Market] tier product, tagged with the buyer ([account]) and
     * the post ([postId]). [onUnavailable] runs, on the main thread, if Play can't sell it now.
     */
    fun buyTier(activity: Activity, productId: String, account: String, postId: String, onUnavailable: () -> Unit) {
        val c = client
        if (c == null || !c.isReady) {
            refresh()
            return onUnavailable()
        }
        val product = QueryProductDetailsParams.Product.newBuilder()
            .setProductId(productId).setProductType(BillingClient.ProductType.INAPP).build()
        c.queryProductDetailsAsync(QueryProductDetailsParams.newBuilder().setProductList(listOf(product)).build()) { result, found ->
            val d = found.productDetailsList.firstOrNull()
            main.post {
                if (result.responseCode != BillingClient.BillingResponseCode.OK || d == null || activity.isFinishing) {
                    return@post onUnavailable()
                }
                val params = BillingFlowParams.newBuilder()
                    .setProductDetailsParamsList(listOf(BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(d).build()))
                    .setObfuscatedAccountId(account)
                    .setObfuscatedProfileId(postId)
                    .build()
                if (c.launchBillingFlow(activity, params).responseCode != BillingClient.BillingResponseCode.OK) onUnavailable()
            }
        }
    }

    /**
     * Premium is on when a purchase of it has gone through; a purchase is acknowledged once (Play
     * refunds unacknowledged ones after 3 days). [complete] = this is the full list, so a missing
     * purchase (refunded) turns Premium off.
     */
    private fun handle(purchases: List<Purchase>, complete: Boolean = false) {
        // Posts bought (the server checks, unlocks and consumes them; pending payments wait).
        val bought = purchases.filter { p -> p.purchaseState == Purchase.PurchaseState.PURCHASED && p.products.any { Market.cents(it) != null } }
        if (bought.isNotEmpty()) main.post { bought.forEach(Market::claim) }
        val owned = purchases.filter { PRODUCT_ID in it.products && it.purchaseState == Purchase.PurchaseState.PURCHASED }
        if (owned.isNotEmpty()) main.post { owned.forEach(Market::claimPremium) }
        for (p in owned) {
            if (!p.isAcknowledged) {
                client?.acknowledgePurchase(AcknowledgePurchaseParams.newBuilder().setPurchaseToken(p.purchaseToken).build()) {}
            }
        }
        if (owned.isNotEmpty()) main.post { set(true) } else if (complete) main.post { set(false) }
    }

    private fun set(on: Boolean) {
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_OWNED, on).apply()
        val debuggable = app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        val now = on || (debuggable && app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_DEBUG_OWNED, false))
        if (now == isPremium) return
        isPremium = now
        listeners.toList().forEach { it(now) }
    }
}
