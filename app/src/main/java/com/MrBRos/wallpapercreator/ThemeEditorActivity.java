package com.MrBRos.wallpapercreator;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.MrBRos.wallpapercreator.databinding.ActivityThemeEditorBinding;
import com.bumptech.glide.Glide;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds or edits a {@link CustomTheme}: wallpaper, icon style, shape, colours and the app list,
 * with a live home screen preview. Tapping an icon in the preview gives it a photo, renames it or
 * removes it.
 */
public class ThemeEditorActivity extends AppCompatActivity {

    private static final int MAX_APPS = 24;
    private static final int[] PRESETS = {
            0xFFFFFFFF, 0xFF16181D, 0xFF2F6BFF, 0xFF8E44FF, 0xFFFF5E8A, 0xFFFF8A00, 0xFFFFD43B,
            0xFF2BC48A, 0xFF00B8D9, 0xFF7A4B2A,
    };

    private ActivityThemeEditorBinding binding;
    private CustomTheme theme;
    private boolean isNew;
    private boolean dirty;
    /** Copy of the wallpaper as it was when editing began, restored if changes are discarded. */
    private File wallpaperBackup;
    /** Index of the app whose icon photo is being picked, or -1 when picking the wallpaper. */
    private int pickingFor = -1;

    private final ActivityResultLauncher<Intent> pickLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null) return;
                Uri uri = result.getData().getData();
                if (uri != null) onPicked(uri);
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityThemeEditorBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        String id = getIntent().getStringExtra(ThemePreviewActivity.EXTRA_ID);
        CustomTheme existing = id != null ? ThemeStore.load(this, id) : null;
        isNew = existing == null;
        theme = isNew ? ThemeStore.create(getString(R.string.default_theme_name)) : existing;
        binding.nameInput.setText(theme.name);

        binding.btnBack.setOnClickListener(v -> leave());
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                leave();
            }
        });
        binding.btnSave.setOnClickListener(v -> save());
        binding.btnPost.setOnClickListener(v -> postWallpaper());
        binding.screenToggle.addOnButtonCheckedListener((g, bid, checked) -> {
            if (checked) binding.mock.setLock(bid == R.id.btnLock);
        });
        binding.mock.setOnIconClickListener(this::iconOptions);
        binding.btnWpGallery.setOnClickListener(v -> pick(-1));
        binding.btnWpFeatured.setOnClickListener(v -> pickFeatured());
        binding.btnApps.setOnClickListener(v -> chooseApps());

        // Display order; each chip carries its CustomTheme.STYLE_* value, so saved themes are unaffected.
        buildChips(binding.styleGroup, new int[]{R.string.style_original, R.string.style_themed, R.string.style_glass,
                        R.string.style_gradient, R.string.style_outline, R.string.style_neon, R.string.style_pastel,
                        R.string.style_minimal, R.string.style_raised, R.string.style_sticker},
                new int[]{CustomTheme.STYLE_ORIGINAL, CustomTheme.STYLE_THEMED, CustomTheme.STYLE_GLASS,
                        CustomTheme.STYLE_GRADIENT, CustomTheme.STYLE_OUTLINE, CustomTheme.STYLE_NEON,
                        CustomTheme.STYLE_PASTEL, CustomTheme.STYLE_MINIMAL, CustomTheme.STYLE_RAISED,
                        CustomTheme.STYLE_STICKER},
                theme.style, i -> {
                    theme.style = i;
                    if (i != CustomTheme.STYLE_ORIGINAL) theme.glyphColor = ThemeIcons.defaultGlyph(i, theme.bgColor);
                    changed();
                });
        buildChips(binding.shapeGroup, new int[]{R.string.shape_circle, R.string.shape_squircle,
                        R.string.shape_rounded, R.string.shape_teardrop, R.string.shape_square,
                        R.string.shape_hexagon, R.string.shape_leaf, R.string.shape_flower,
                        R.string.shape_cookie, R.string.shape_shield},
                new int[]{CustomTheme.SHAPE_CIRCLE, CustomTheme.SHAPE_SQUIRCLE, CustomTheme.SHAPE_ROUNDED,
                        CustomTheme.SHAPE_TEARDROP, CustomTheme.SHAPE_SQUARE, CustomTheme.SHAPE_HEXAGON,
                        CustomTheme.SHAPE_LEAF, CustomTheme.SHAPE_FLOWER, CustomTheme.SHAPE_COOKIE,
                        CustomTheme.SHAPE_SHIELD}, theme.shape, i -> {
            theme.shape = i;
            changed();
        });

        if (isNew) startNew();
        else {
            backupWallpaper();
            refresh();
        }
    }

    /** A new theme starts from a featured wallpaper and everyday apps so the preview isn't blank. */
    private void startNew() {
        Context app = getApplicationContext();
        new Thread(() -> {
            List<CustomTheme.App> apps = AppCatalog.defaults(app, 12);
            List<String> featured = FeaturedWallpapers.list(app, null);
            File src = null;
            try {
                if (!featured.isEmpty()) src = FeaturedWallpapers.copyToCache(app, featured.get(0));
            } catch (IOException ignored) {
            }
            File finalSrc = src;
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                theme.apps.addAll(apps);
                if (finalSrc != null) setWallpaperFrom(finalSrc, true);
                else refresh();
            });
        }, "ThemeNew").start();
    }

    private void backupWallpaper() {
        File wp = ThemeStore.wallpaper(this, theme);
        if (!wp.exists()) return;
        wallpaperBackup = new File(getCacheDir(), "theme_wp_backup.jpg");
        try {
            copy(wp, wallpaperBackup);
        } catch (IOException e) {
            wallpaperBackup = null;
        }
    }

    private static void copy(File from, File to) throws IOException {
        try (InputStream in = new FileInputStream(from); OutputStream os = new FileOutputStream(to)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
        }
    }

    private void changed() {
        dirty = true;
        refresh();
    }

    private void refresh() {
        binding.mock.setTheme(theme);
        boolean original = theme.style == CustomTheme.STYLE_ORIGINAL;
        int colours = original ? View.GONE : View.VISIBLE;
        // Minimal has no background, so only the symbol colour applies.
        int bgColours = original || theme.style == CustomTheme.STYLE_MINIMAL ? View.GONE : View.VISIBLE;
        binding.bgHeader.setVisibility(bgColours);
        binding.bgScroll.setVisibility(bgColours);
        binding.glyphHeader.setVisibility(colours);
        binding.glyphScroll.setVisibility(colours);
        binding.appsHeader.setText(getString(R.string.apps_header_count, theme.apps.size()));
        buildSwatches();
        buildIconImages();
    }

    // ---- Chips & swatches ----

    private interface IntListener {
        void on(int value);
    }

    /** One chip per label; picking chip i reports {@code values[i]}. */
    private void buildChips(ChipGroup group, int[] labels, int[] values, int selected, IntListener listener) {
        group.removeAllViews();
        for (int i = 0; i < labels.length; i++) {
            Chip chip = (Chip) getLayoutInflater().inflate(R.layout.view_category_chip, group, false);
            chip.setId(View.generateViewId());
            chip.setText(labels[i]);
            chip.setChecked(values[i] == selected);
            int value = values[i];
            chip.setOnCheckedChangeListener((c, checked) -> {
                if (checked) listener.on(value);
            });
            group.addView(chip);
        }
    }

    private void buildSwatches() {
        List<Integer> bg = new ArrayList<>();
        for (int p : PRESETS) bg.add(p);
        IntListener setBg = c -> {
            theme.bgColor = c;
            theme.glyphColor = ThemeIcons.defaultGlyph(theme.style, c);
            changed();
        };
        fillSwatches(binding.bgSwatches, bg, theme.bgColor, setBg);
        addCustomSwatch(binding.bgSwatches, R.string.icon_colour, theme.bgColor, setBg);
        List<Integer> glyph = new ArrayList<>();
        glyph.add(0xFFFFFFFF);
        glyph.add(0xFF16181D);
        for (int p : PRESETS) if (!glyph.contains(p)) glyph.add(p);
        IntListener setGlyph = c -> {
            theme.glyphColor = c;
            changed();
        };
        fillSwatches(binding.glyphSwatches, glyph, theme.glyphColor, setGlyph);
        addCustomSwatch(binding.glyphSwatches, R.string.symbol_colour, theme.glyphColor, setGlyph);
    }

    /** A rainbow dot at the start of a swatch row that opens the full colour picker. */
    private void addCustomSwatch(LinearLayout row, int title, int current, IntListener listener) {
        float d = getResources().getDisplayMetrics().density;
        View v = new View(this);
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setGradientType(GradientDrawable.SWEEP_GRADIENT);
        dot.setColors(new int[]{0xFFFF0000, 0xFFFFFF00, 0xFF00FF00, 0xFF00FFFF, 0xFF0000FF, 0xFFFF00FF, 0xFFFF0000});
        dot.setStroke(Math.round(d), 0x33000000);
        v.setBackground(dot);
        int size = Math.round(40 * d);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
        lp.setMarginEnd(Math.round(10 * d));
        lp.topMargin = lp.bottomMargin = Math.round(4 * d);
        v.setLayoutParams(lp);
        v.setContentDescription(getString(R.string.custom_colour));
        v.setOnClickListener(x -> ColorPickerSheet.show(this, title, current, listener::on));
        row.addView(v, 0);
    }

    private void fillSwatches(LinearLayout row, List<Integer> colours, int selected, IntListener listener) {
        row.removeAllViews();
        float d = getResources().getDisplayMetrics().density;
        int size = Math.round(40 * d);
        // A colour picked with the custom picker joins the row so it shows as selected.
        if (!colours.contains(selected | 0xFF000000)) {
            colours = new ArrayList<>(colours);
            colours.add(0, selected | 0xFF000000);
        }
        for (int c : colours) {
            View v = new View(this);
            GradientDrawable dot = new GradientDrawable();
            dot.setShape(GradientDrawable.OVAL);
            dot.setColor(c);
            boolean sel = (c | 0xFF000000) == (selected | 0xFF000000);
            dot.setStroke(Math.round((sel ? 3 : 1) * d), sel ? getColor(R.color.brand_primary) : 0x33000000);
            v.setBackground(dot);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            lp.setMarginEnd(Math.round(10 * d));
            lp.topMargin = lp.bottomMargin = Math.round(4 * d);
            v.setLayoutParams(lp);
            v.setContentDescription(String.format("#%06X", c & 0xFFFFFF));
            v.setOnClickListener(x -> listener.on(c));
            row.addView(v);
            if (sel) scrollIntoView(row, v, size);
        }
    }

    /** Scrolls a swatch row so the selected colour is on screen, e.g. a preset picked by its HEX. */
    private static void scrollIntoView(LinearLayout row, View swatch, int pad) {
        row.post(() -> {
            HorizontalScrollView sv = (HorizontalScrollView) row.getParent();
            int left = swatch.getLeft(), right = swatch.getRight(), x = sv.getScrollX();
            if (left < x || right > x + sv.getWidth()) sv.smoothScrollTo(Math.max(0, left - pad), 0);
        });
    }

    /** The preset closest to the wallpaper's main colour, so a new theme matches it without extra swatches. */
    private static int nearestPreset(int c) {
        int best = PRESETS[0];
        long bestDist = Long.MAX_VALUE;
        for (int p : PRESETS) {
            long dr = Color.red(p) - Color.red(c), dg = Color.green(p) - Color.green(c), db = Color.blue(p) - Color.blue(c);
            long dist = 2 * dr * dr + 4 * dg * dg + 3 * db * db;
            if (dist < bestDist) {
                bestDist = dist;
                best = p;
            }
        }
        return best;
    }

    // ---- Wallpaper ----

    private void pick(int forApp) {
        pickingFor = forApp;
        pickLauncher.launch(ImagePickerHelper.createPickIntent());
    }

    private void onPicked(Uri uri) {
        Context app = getApplicationContext();
        int target = pickingFor;
        new Thread(() -> {
            try {
                File file = ImagePickerHelper.copyToCache(app, uri);
                if (target < 0) {
                    runOnUiThread(() -> setWallpaperFrom(file, false));
                    return;
                }
                Bitmap square = ThemeStore.squarePhoto(file);
                if (square == null) throw new IOException("decode");
                String name = ThemeStore.savePhoto(app, theme, square);
                square.recycle();
                runOnUiThread(() -> {
                    if (target < theme.apps.size()) theme.apps.get(target).photo = name;
                    changed();
                });
            } catch (IOException e) {
                runOnUiThread(() -> Toast.makeText(app, R.string.pick_failed, Toast.LENGTH_SHORT).show());
            }
        }, "ThemePick").start();
    }

    /** Stores {@code src} as the wallpaper; a fresh theme also takes its colours from it. */
    private void setWallpaperFrom(File src, boolean recolour) {
        Context app = getApplicationContext();
        CustomTheme t = theme;
        new Thread(() -> {
            try {
                ThemeStore.setWallpaper(app, t, src);
                Bitmap wp = ThemeStore.decodeWallpaper(app, t, 256);
                List<Integer> pal = wp != null ? ThemeStore.palette(wp, 1) : new ArrayList<>();
                if (wp != null) wp.recycle();
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    if ((recolour || isNew) && !pal.isEmpty()) {
                        theme.bgColor = nearestPreset(pal.get(0));
                        theme.glyphColor = ThemeIcons.defaultGlyph(theme.style, theme.bgColor);
                    }
                    dirty |= !recolour;
                    refresh();
                });
            } catch (IOException e) {
                runOnUiThread(() -> Toast.makeText(app, R.string.pick_failed, Toast.LENGTH_SHORT).show());
            }
        }, "ThemeWallpaper").start();
    }

    private void pickFeatured() {
        List<String> assets = new ArrayList<>(FeaturedWallpapers.list(this, null));
        RecyclerView grid = new RecyclerView(this);
        grid.setLayoutManager(new GridLayoutManager(this, 3));
        int pad = Math.round(8 * getResources().getDisplayMetrics().density);
        grid.setPadding(pad, pad, pad, pad);
        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.from_featured)
                .setView(grid)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        grid.setAdapter(new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            @NonNull
            @Override
            public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
                ImageView iv = new ImageView(parent.getContext());
                int h = Math.round(150 * getResources().getDisplayMetrics().density);
                RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h);
                lp.setMargins(pad / 2, pad / 2, pad / 2, pad / 2);
                iv.setLayoutParams(lp);
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                return new RecyclerView.ViewHolder(iv) { };
            }

            @Override
            public void onBindViewHolder(@NonNull RecyclerView.ViewHolder h, int position) {
                String asset = assets.get(position);
                Glide.with(ThemeEditorActivity.this).load("file:///android_asset/" + asset)
                        .override(300).centerCrop().into((ImageView) h.itemView);
                h.itemView.setOnClickListener(v -> {
                    dialog.dismiss();
                    Context app = getApplicationContext();
                    new Thread(() -> {
                        try {
                            File f = FeaturedWallpapers.copyToCache(app, asset);
                            runOnUiThread(() -> setWallpaperFrom(f, false));
                        } catch (IOException e) {
                            runOnUiThread(() -> Toast.makeText(app, R.string.pick_failed, Toast.LENGTH_SHORT).show());
                        }
                    }, "ThemeFeatured").start();
                });
            }

            @Override
            public int getItemCount() {
                return assets.size();
            }
        });
        dialog.show();
    }

    // ---- Apps & icons ----

    private void chooseApps() {
        Context app = getApplicationContext();
        new Thread(() -> {
            List<CustomTheme.App> all = AppCatalog.all(app);
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                String[] labels = new String[all.size()];
                boolean[] checked = new boolean[all.size()];
                for (int i = 0; i < all.size(); i++) {
                    labels[i] = all.get(i).label;
                    checked[i] = AppCatalog.contains(theme.apps, all.get(i));
                }
                new MaterialAlertDialogBuilder(this)
                        .setTitle(getString(R.string.choose_apps_max, MAX_APPS))
                        .setMultiChoiceItems(labels, checked, (d, which, isChecked) -> checked[which] = isChecked)
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(android.R.string.ok, (d, w) -> applyAppChoice(all, checked))
                        .show();
            });
        }, "ThemeApps").start();
    }

    private void applyAppChoice(List<CustomTheme.App> all, boolean[] checked) {
        // Keep the existing order (and photos/names) of apps that stay; new ones go at the end.
        List<CustomTheme.App> kept = new ArrayList<>();
        for (CustomTheme.App a : theme.apps) {
            for (int i = 0; i < all.size(); i++) {
                CustomTheme.App b = all.get(i);
                if (checked[i] && a.pkg.equals(b.pkg) && a.activity.equals(b.activity)) kept.add(a);
            }
        }
        for (int i = 0; i < all.size(); i++) {
            if (checked[i] && !AppCatalog.contains(kept, all.get(i))) kept.add(all.get(i));
        }
        if (kept.size() > MAX_APPS) {
            Toast.makeText(this, getString(R.string.too_many_apps, MAX_APPS), Toast.LENGTH_LONG).show();
            kept = new ArrayList<>(kept.subList(0, MAX_APPS));
        }
        theme.apps.clear();
        theme.apps.addAll(kept);
        changed();
    }

    /** Bumped on every rebuild so icons rendered for an older row aren't put into a newer one. */
    private int iconRowGen;

    /** The Icon images row: each app's icon as it looks now; tapping one uploads, changes or removes its image. */
    private void buildIconImages() {
        LinearLayout row = binding.iconImageRow;
        row.removeAllViews();
        float d = getResources().getDisplayMetrics().density;
        int iconPx = Math.round(56 * d);
        List<ImageView> icons = new ArrayList<>();
        for (int i = 0; i < theme.apps.size(); i++) {
            CustomTheme.App app = theme.apps.get(i);
            LinearLayout tile = new LinearLayout(this);
            tile.setOrientation(LinearLayout.VERTICAL);
            tile.setGravity(Gravity.CENTER_HORIZONTAL);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Math.round(72 * d),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd(Math.round(4 * d));
            tile.setLayoutParams(lp);
            ImageView icon = new ImageView(this);
            icon.setLayoutParams(new LinearLayout.LayoutParams(iconPx, iconPx));
            tile.addView(icon);
            TextView label = new TextView(this);
            label.setText(app.label);
            label.setSingleLine(true);
            label.setEllipsize(TextUtils.TruncateAt.END);
            label.setGravity(Gravity.CENTER_HORIZONTAL);
            label.setTextSize(12);
            label.setTextColor(getColor(R.color.text_secondary));
            tile.addView(label, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            tile.setContentDescription(app.label);
            int index = i;
            tile.setOnClickListener(v -> iconImageOptions(index));
            row.addView(tile);
            icons.add(icon);
        }
        int gen = ++iconRowGen;
        CustomTheme t = theme;
        List<CustomTheme.App> apps = new ArrayList<>(theme.apps);
        Context context = getApplicationContext();
        new Thread(() -> {
            for (int i = 0; i < apps.size(); i++) {
                Bitmap b = ThemeIcons.render(context, t, apps.get(i), iconPx);
                ImageView target = icons.get(i);
                runOnUiThread(() -> {
                    if (gen == iconRowGen && !isDestroyed()) target.setImageBitmap(b);
                });
            }
        }, "ThemeIconRow").start();
    }

    private void iconImageOptions(int index) {
        if (index >= theme.apps.size()) return;
        CustomTheme.App app = theme.apps.get(index);
        if (app.photo == null) {
            pick(index);
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(app.label)
                .setItems(new String[]{getString(R.string.icon_image_change), getString(R.string.icon_image_remove)},
                        (dlg, which) -> {
                            if (which == 0) {
                                pick(index);
                            } else {
                                app.photo = null;
                                changed();
                            }
                        })
                .show();
    }

    private void iconOptions(int index) {
        if (index >= theme.apps.size()) return;
        CustomTheme.App app = theme.apps.get(index);
        List<String> items = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        items.add(getString(R.string.icon_use_photo));
        actions.add(() -> pick(index));
        if (app.photo != null) {
            items.add(getString(R.string.icon_use_themed));
            actions.add(() -> {
                app.photo = null;
                changed();
            });
        }
        items.add(getString(R.string.icon_rename));
        actions.add(() -> rename(app));
        if (index > 0) {
            items.add(getString(R.string.icon_move_first));
            actions.add(() -> {
                theme.apps.remove(index);
                theme.apps.add(0, app);
                changed();
            });
        }
        items.add(getString(R.string.icon_remove));
        actions.add(() -> {
            theme.apps.remove(index);
            changed();
        });
        new MaterialAlertDialogBuilder(this)
                .setTitle(app.label)
                .setItems(items.toArray(new String[0]), (d, which) -> actions.get(which).run())
                .show();
    }

    private void rename(CustomTheme.App app) {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        input.setText(app.label);
        input.setSelectAllOnFocus(true);
        FrameLayout box = new FrameLayout(this);
        int pad = Math.round(20 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(input);
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.icon_rename)
                .setView(box)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    String s = input.getText().toString().trim();
                    if (!s.isEmpty()) {
                        app.label = s;
                        changed();
                    }
                })
                .show();
    }

    // ---- Save / leave ----

    /** Post button: sends just the theme's background picture to the cloud, like a 2D post. */
    private void postWallpaper() {
        if (!FirebaseHelper.isAvailable(this)) {
            Toast.makeText(this, R.string.cloud_disabled, Toast.LENGTH_LONG).show();
            return;
        }
        if (FirebaseHelper.currentUser(this) == null) {
            Toast.makeText(this, R.string.sign_in_required, Toast.LENGTH_SHORT).show();
            startActivity(new Intent(this, AuthActivity.class));
            return;
        }
        File wp = ThemeStore.wallpaper(this, theme);
        if (!wp.exists()) {
            Toast.makeText(this, R.string.theme_needs_wallpaper, Toast.LENGTH_SHORT).show();
            return;
        }
        String name = binding.nameInput.getText() == null ? "" : binding.nameInput.getText().toString().trim();
        PostDialog.show(this, name.isEmpty() ? null : name, (title, isPublic, category) -> {
            binding.btnPost.setEnabled(false);
            Toast.makeText(this, R.string.uploading, Toast.LENGTH_SHORT).show();
            FirebaseHelper.uploadWallpaper(this, wp, title, isPublic, category, new FirebaseHelper.UploadCallback() {
                @Override
                public void onProgress(int percent) {
                }

                @Override
                public void onSuccess(String docId) {
                    if (isDestroyed()) return;
                    binding.btnPost.setEnabled(true);
                    Toast.makeText(ThemeEditorActivity.this, R.string.upload_done, Toast.LENGTH_SHORT).show();
                }

                @Override
                public void onError(Exception e) {
                    if (isDestroyed()) return;
                    binding.btnPost.setEnabled(true);
                    Toast.makeText(ThemeEditorActivity.this,
                            getString(R.string.upload_failed) + ": " + e.getMessage(), Toast.LENGTH_LONG).show();
                }
            });
        });
    }

    private void save() {
        if (theme.apps.isEmpty()) {
            Toast.makeText(this, R.string.theme_needs_apps, Toast.LENGTH_SHORT).show();
            return;
        }
        if (!ThemeStore.wallpaper(this, theme).exists()) {
            Toast.makeText(this, R.string.theme_needs_wallpaper, Toast.LENGTH_SHORT).show();
            return;
        }
        String name = binding.nameInput.getText() == null ? "" : binding.nameInput.getText().toString().trim();
        theme.name = name.isEmpty() ? getString(R.string.default_theme_name) : name;
        try {
            ThemeStore.save(this, theme);
        } catch (IOException e) {
            Toast.makeText(this, R.string.save_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(this, R.string.theme_saved, Toast.LENGTH_SHORT).show();
        if (isNew) {
            startActivity(new Intent(this, ThemePreviewActivity.class).putExtra(ThemePreviewActivity.EXTRA_ID, theme.id));
        }
        finish();
    }

    private void leave() {
        String name = binding.nameInput.getText() == null ? "" : binding.nameInput.getText().toString().trim();
        if (!dirty && !isNew && name.equals(theme.name)) {
            finish();
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.discard_theme_title)
                .setNegativeButton(R.string.keep_editing, null)
                .setPositiveButton(R.string.discard, (d, w) -> {
                    discard();
                    finish();
                })
                .show();
    }

    private void discard() {
        if (isNew) {
            ThemeStore.delete(this, theme);
            return;
        }
        if (wallpaperBackup != null && wallpaperBackup.exists()) {
            try {
                copy(wallpaperBackup, ThemeStore.wallpaper(this, theme));
            } catch (IOException ignored) {
            }
        }
        // Photos picked during this edit aren't used by the saved theme.
        CustomTheme saved = ThemeStore.load(this, theme.id);
        if (saved != null) ThemeStore.prunePhotos(this, saved);
    }
}
