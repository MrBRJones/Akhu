package com.MrBRos.akhu

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.RequestConfiguration
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform

/**
 * Ads for the free version (AdMob): a banner above the bottom bar. Nothing loads for Premium.
 * Before the first ad, Google's consent form is shown where the law asks for it (EU, UK); ads
 * are rated for teens at most. The app and banner IDs are in strings.xml (admob_*).
 */
object Ads {

    private var started = false
    private var ready = false
    private val whenReady = mutableListOf<() -> Unit>()

    /** Asks for consent if needed, then starts AdMob. Safe to call on every launch. */
    @JvmStatic
    fun start(activity: Activity) {
        if (Premium.isPremium || started) return
        started = true
        val consent = UserMessagingPlatform.getConsentInformation(activity)
        consent.requestConsentInfoUpdate(activity, ConsentRequestParameters.Builder().build(), {
            UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { if (consent.canRequestAds()) init(activity) }
        }, { if (consent.canRequestAds()) init(activity) })
        // Consent given on an earlier launch: no need to wait for the update.
        if (consent.canRequestAds()) init(activity)
    }

    private fun init(activity: Activity) {
        if (ready) return
        ready = true
        MobileAds.setRequestConfiguration(
            RequestConfiguration.Builder().setMaxAdContentRating(RequestConfiguration.MAX_AD_CONTENT_RATING_T).build()
        )
        val app = activity.applicationContext
        Thread({
            MobileAds.initialize(app) {}
            activity.runOnUiThread { whenReady.toList().forEach { it() }; whenReady.clear() }
        }, "AdsInit").start()
    }

    /**
     * Shows a banner in [slot] (an empty FrameLayout), or hides the slot when [show] is false or
     * the user has Premium. Keeps one banner per slot and reuses it.
     */
    @JvmStatic
    fun banner(activity: Activity, slot: FrameLayout, show: Boolean) {
        if (activity.isDestroyed) return
        if (!show || Premium.isPremium) {
            slot.visibility = View.GONE
            if (Premium.isPremium) destroy(slot)
            return
        }
        if (!ready) {
            slot.visibility = View.GONE
            whenReady += { banner(activity, slot, show) }
            return
        }
        slot.visibility = View.VISIBLE
        if (slot.childCount > 0) return
        val metrics = activity.resources.displayMetrics
        val ad = AdView(activity).apply {
            adUnitId = activity.getString(R.string.admob_banner_id)
            setAdSize(AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(activity, (metrics.widthPixels / metrics.density).toInt()))
        }
        slot.addView(ad, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        ad.loadAd(AdRequest.Builder().build())
    }

    @JvmStatic
    fun destroy(slot: FrameLayout) {
        (slot.getChildAt(0) as? AdView)?.destroy()
        slot.removeAllViews()
    }

    /** True where the user must be able to change their ad consent later (Settings shows a row). */
    @JvmStatic
    fun hasPrivacyChoices(activity: Activity): Boolean =
        UserMessagingPlatform.getConsentInformation(activity).privacyOptionsRequirementStatus ==
            ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED

    @JvmStatic
    fun showPrivacyChoices(activity: Activity) {
        UserMessagingPlatform.showPrivacyOptionsForm(activity) {}
    }
}
