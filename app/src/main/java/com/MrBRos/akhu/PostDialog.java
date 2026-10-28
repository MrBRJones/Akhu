package com.MrBRos.akhu;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;

import androidx.annotation.Nullable;

import com.MrBRos.akhu.databinding.DialogUploadBinding;
import com.google.android.material.chip.Chip;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/** The "Upload to cloud" dialog (title, category, public) shared by Create and Theme Creation. */
final class PostDialog {

    interface OnPost {
        void post(String title, boolean isPublic, @Nullable String category);
    }

    interface OnCreate {
        /** {@code post} is the Post switch; the other fields only matter when it's on. */
        void create(boolean post, String title, boolean isPublic, @Nullable String category);
    }

    private PostDialog() {
    }

    static void show(Context context, @Nullable String defaultTitle, OnPost onPost) {
        DialogUploadBinding dialog = inflate(context, defaultTitle);
        new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.upload_title)
                .setView(dialog.getRoot())
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.post, (d, w) ->
                        onPost.post(title(context, dialog), dialog.switchPublic.isChecked(), category(dialog)))
                .show();
    }

    /**
     * The motion editor's Create dialog: the same fields plus a Post switch under Public. With Post
     * off the post fields are greyed out and Create only sets the wallpaper.
     */
    static void showCreate(Context context, @Nullable String defaultTitle, boolean postDefault, OnCreate onCreate) {
        DialogUploadBinding dialog = inflate(context, defaultTitle);
        dialog.switchPost.setVisibility(View.VISIBLE);
        dialog.switchPost.setOnCheckedChangeListener((b, on) -> enablePostFields(dialog, on));
        dialog.switchPost.setChecked(postDefault);
        enablePostFields(dialog, postDefault);
        new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.create_action)
                .setView(dialog.getRoot())
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.create_action, (d, w) -> onCreate.create(dialog.switchPost.isChecked(),
                        title(context, dialog), dialog.switchPublic.isChecked(), category(dialog)))
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

    private static void enablePostFields(DialogUploadBinding dialog, boolean on) {
        dialog.inputTitleLayout.setEnabled(on);
        dialog.switchPublic.setEnabled(on);
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
