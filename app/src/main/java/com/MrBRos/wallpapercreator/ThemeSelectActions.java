package com.MrBRos.wallpapercreator;

import android.content.Context;
import android.view.View;
import android.widget.ImageView;

import androidx.annotation.Nullable;

import java.io.File;

/** Multi-select rules for saved themes: Share sends each theme's wallpaper, Delete removes the theme. */
final class ThemeSelectActions implements GridSelection.Actions<CustomTheme> {

    private final Context app;
    private final Runnable reload;

    ThemeSelectActions(Context context, Runnable reload) {
        this.app = context.getApplicationContext();
        this.reload = reload;
    }

    /** Shows a theme card's tick circle while its grid is selecting. */
    static void bindCheck(ImageView check, GridSelection<CustomTheme> selection, CustomTheme theme) {
        check.setVisibility(selection.isActive() ? View.VISIBLE : View.GONE);
        check.setImageResource(selection.isSelected(theme) ? R.drawable.ic_check_circle : R.drawable.ic_circle_outline);
    }

    @Override
    public String key(CustomTheme theme) {
        return theme.id;
    }

    @Override
    public boolean canSelect(CustomTheme theme) {
        return true;
    }

    @Nullable
    @Override
    public File file(CustomTheme theme) {
        return ThemeStore.wallpaper(app, theme);
    }

    @Override
    public void delete(Context app, CustomTheme theme) {
        ThemeStore.delete(app, theme);
    }

    @Override
    public void reload() {
        reload.run();
    }
}
