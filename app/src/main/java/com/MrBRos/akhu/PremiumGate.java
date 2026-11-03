package com.MrBRos.akhu;

import android.app.Activity;
import android.widget.Toast;

import androidx.annotation.StringRes;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/** What a free user sees on a Premium-only feature: what it is, and a way to get Premium. */
public final class PremiumGate {

    /**
     * Off until akhu_premium is live in the Play Console: everyone gets the Premium-only
     * features meanwhile. Ads still follow Premium.owned().
     */
    static final boolean LOCKED = false;

    private PremiumGate() {
    }

    /** True when the user has Premium; otherwise shows the offer and returns false. */
    public static boolean check(Activity activity, @StringRes int message) {
        if (!LOCKED || Premium.owned()) return true;
        show(activity, message);
        return false;
    }

    public static void show(Activity activity, @StringRes int message) {
        if (activity.isFinishing()) return;
        String price = Premium.INSTANCE.getPrice();
        new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.premium)
                .setMessage(message)
                .setPositiveButton(price == null ? activity.getString(R.string.premium_buy)
                        : activity.getString(R.string.premium_buy_price, price), (d, w) -> {
                    if (!Premium.buy(activity)) {
                        Toast.makeText(activity, R.string.premium_unavailable, Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton(R.string.not_now, null)
                .show();
    }
}
