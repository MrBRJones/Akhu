package com.MrBRos.wallpapercreator;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;

import androidx.annotation.Nullable;

import com.MrBRos.wallpapercreator.databinding.DialogUploadBinding;
import com.google.android.material.chip.Chip;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/** The "Upload to cloud" dialog (title, category, public) shared by Create and Theme Creation. */
final class PostDialog {

    interface OnPost {
        void post(String title, boolean isPublic, @Nullable String category);
    }

    private PostDialog() {
    }

    static void show(Context context, @Nullable String defaultTitle, OnPost onPost) {
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
        new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.upload_title)
                .setView(dialog.getRoot())
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.post, (d, w) -> {
                    String title = dialog.inputTitle.getText() == null ? "" : dialog.inputTitle.getText().toString().trim();
                    if (title.isEmpty()) title = context.getString(R.string.untitled);
                    View checked = dialog.categoryChips.findViewById(dialog.categoryChips.getCheckedChipId());
                    String category = checked == null ? null : (String) checked.getTag();
                    onPost.post(title, dialog.switchPublic.isChecked(), category);
                })
                .show();
    }
}
