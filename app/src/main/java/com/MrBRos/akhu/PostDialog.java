package com.MrBRos.akhu;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ArrayAdapter;

import androidx.annotation.Nullable;

import com.MrBRos.akhu.databinding.DialogUploadBinding;
import com.google.android.material.chip.Chip;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/** The "Upload to cloud" dialog (title, category, public, sell) shared by Create and Theme Creation. */
final class PostDialog {

    interface OnPost {
        /** {@code saleTier}: the {@link Market} tier it's sold at, or null when it isn't for sale. */
        void post(String title, boolean isPublic, @Nullable String category, @Nullable String saleTier);
    }

    interface OnCreate {
        /** {@code post} is the Post switch; the other fields only matter when it's on. */
        void create(boolean post, String title, boolean isPublic, @Nullable String category, @Nullable String saleTier);
    }

    /** Tags the Sell switch of a kind this account may not sell. */
    private static final Object LOCKED = new Object();

    /** The tiers the dialog's price picker showed, and the one picked (the cheapest-but-one to start with). */
    private static final class Sale {
        final java.util.List<String> tiers = Market.INSTANCE.getConfig().getTiers();
        int tier = 1;
    }

    private PostDialog() {
    }

    /** {@code kind}: what's posted (a media type or {@link Market#KIND_THEME}), for who may sell it. */
    static void show(Context context, @Nullable String defaultTitle, String kind, OnPost onPost) {
        show(context, defaultTitle, kind, onPost, null);
    }

    /** As {@link #show(Context, String, String, OnPost)}, plus a Save button that keeps it without posting. */
    static void show(Context context, @Nullable String defaultTitle, String kind, OnPost onPost, @Nullable Runnable onSaveOnly) {
        if (!Verify.ok(context)) return;
        DialogUploadBinding dialog = inflate(context, defaultTitle);
        Sale sale = setUpSell(context, dialog, kind);
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.upload_title)
                .setView(dialog.getRoot())
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.post, (d, w) -> onPost.post(title(context, dialog),
                        dialog.switchPublic.isChecked(), category(dialog), saleTier(dialog, sale)));
        if (onSaveOnly != null) builder.setNeutralButton(R.string.save_only, (d, w) -> onSaveOnly.run());
        builder.show();
    }

    /**
     * The motion editor's Create dialog: the same fields plus a Post switch under Public. With Post
     * off the post fields are greyed out and Create only sets the wallpaper.
     */
    static void showCreate(Context context, @Nullable String defaultTitle, boolean postDefault, OnCreate onCreate) {
        DialogUploadBinding dialog = inflate(context, defaultTitle);
        Sale sale = setUpSell(context, dialog, FirebaseHelper.MEDIA_VIDEO);
        dialog.switchPost.setVisibility(View.VISIBLE);
        dialog.switchPost.setOnCheckedChangeListener((b, on) -> enablePostFields(dialog, on));
        dialog.switchPost.setChecked(postDefault);
        enablePostFields(dialog, postDefault);
        new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.create_action)
                .setView(dialog.getRoot())
                .setNegativeButton(android.R.string.cancel, null)
                // An account still to verify its email makes the wallpaper but doesn't post it.
                .setPositiveButton(R.string.create_action, (d, w) -> onCreate.create(
                        dialog.switchPost.isChecked() && Verify.ok(context),
                        title(context, dialog), dialog.switchPublic.isChecked(), category(dialog),
                        saleTier(dialog, sale)))
                .show();
    }

    private static DialogUploadBinding inflate(Context context, @Nullable String defaultTitle) {
        LayoutInflater inflater = LayoutInflater.from(context);
        DialogUploadBinding dialog = DialogUploadBinding.inflate(inflater);
        if (defaultTitle != null) dialog.inputTitle.setText(defaultTitle);
        for (int i = 0; i < Categories.IDS.length; i++) {
            Chip chip = (Chip) inflater.inflate(R.layout.view_category_chip, dialog.categoryChips, false);
            chip.setId(View.generateViewId());
            chip.setText(Categories.LABELS[i]);
            chip.setTag(Categories.IDS[i]);
            dialog.categoryChips.addView(chip);
        }
        return dialog;
    }

    /**
     * Shows the Sell switch to signed-in accounts. A post for sale is public; Premium-only kinds
     * show why the switch is off for everyone else.
     */
    private static Sale setUpSell(Context context, DialogUploadBinding dialog, String kind) {
        Sale sale = new Sale();
        // Themes for sale need their own buy screen in the Themes tab, which isn't built yet.
        if (FirebaseHelper.currentUser(context) == null || Market.KIND_THEME.equals(kind)) return sale;
        java.util.List<String> tiers = sale.tiers;
        sale.tier = Math.min(sale.tier, tiers.size() - 1);
        dialog.sellGroup.setVisibility(View.VISIBLE);
        if (!Market.canSell(kind)) {
            dialog.switchSell.setEnabled(false);
            dialog.switchSell.setTag(LOCKED);
            dialog.sellNote.setText(R.string.sell_premium_only);
            dialog.sellNote.setVisibility(View.VISIBLE);
            return sale;
        }
        String[] labels = new String[tiers.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = Market.price(tiers.get(i));
        dialog.inputPrice.setAdapter(new ArrayAdapter<>(context, android.R.layout.simple_list_item_1, labels));
        dialog.inputPrice.setText(labels[sale.tier], false);
        dialog.inputPrice.setOnItemClickListener((parent, v, position, id) -> {
            sale.tier = position;
            showShare(context, dialog, tiers.get(position));
        });
        dialog.switchSell.setOnCheckedChangeListener((b, on) -> {
            dialog.priceLayout.setVisibility(on ? View.VISIBLE : View.GONE);
            dialog.sellNote.setVisibility(on ? View.VISIBLE : View.GONE);
            if (on) dialog.switchPublic.setChecked(true);
            dialog.switchPublic.setEnabled(!on && dialog.inputTitleLayout.isEnabled());
            showShare(context, dialog, tiers.get(sale.tier));
        });
        return sale;
    }

    /** "You get 70% ... ($1.39 at $1.99 in the US)" for the chosen tier. */
    private static void showShare(Context context, DialogUploadBinding dialog, String tier) {
        Integer cents = Market.cents(tier);
        int percent = Market.INSTANCE.getConfig().getSellerPercent();
        int share = cents == null ? 0 : cents * percent / 100;
        dialog.sellNote.setText(context.getString(R.string.sell_you_get, percent,
                String.format(java.util.Locale.US, "$%d.%02d", share / 100, share % 100),
                String.format(java.util.Locale.US, "$%d.%02d", cents == null ? 0 : cents / 100, cents == null ? 0 : cents % 100)));
    }

    @Nullable
    private static String saleTier(DialogUploadBinding dialog, Sale sale) {
        if (dialog.sellGroup.getVisibility() != View.VISIBLE || !dialog.switchSell.isEnabled()
                || !dialog.switchSell.isChecked()) return null;
        return sale.tiers.get(sale.tier);
    }

    private static void enablePostFields(DialogUploadBinding dialog, boolean on) {
        dialog.inputTitleLayout.setEnabled(on);
        dialog.switchPublic.setEnabled(on && !dialog.switchSell.isChecked());
        dialog.switchSell.setEnabled(on && dialog.switchSell.getTag() == null);
        for (int i = 0; i < dialog.categoryChips.getChildCount(); i++) dialog.categoryChips.getChildAt(i).setEnabled(on);
    }

    private static String title(Context context, DialogUploadBinding dialog) {
        String title = dialog.inputTitle.getText() == null ? "" : dialog.inputTitle.getText().toString().trim();
        return title.isEmpty() ? context.getString(R.string.untitled) : title;
    }

    @Nullable
    private static String category(DialogUploadBinding dialog) {
        View checked = dialog.categoryChips.findViewById(dialog.categoryChips.getCheckedChipId());
        return checked == null ? null : (String) checked.getTag();
    }
}
