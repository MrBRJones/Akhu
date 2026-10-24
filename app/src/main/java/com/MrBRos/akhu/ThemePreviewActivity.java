package com.MrBRos.akhu;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.content.pm.ShortcutManagerCompat;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.CompositePageTransformer;
import androidx.viewpager2.widget.MarginPageTransformer;
import androidx.viewpager2.widget.ViewPager2;

import com.MrBRos.akhu.databinding.ActivityThemePreviewBinding;
import com.bumptech.glide.Glide;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * VibeWalls-style theme preview: swipeable phone mockups of every saved theme with a Home/Lock
 * toggle, and One-Tap Setup, which adds the themed icons to the home screen and sets the wallpaper.
 *
 * <p>Each new icon is a pinned shortcut, and Android asks the user to confirm each one, so they are
 * requested one at a time: the next goes out when the launcher reports the last one added, or when
 * this screen resumes after the confirmation was dismissed.</p>
 */
public class ThemePreviewActivity extends AppCompatActivity {

    public static final String EXTRA_ID = "theme_id";
    private static final String ACTION_PINNED = "com.MrBRos.akhu.THEME_ICON_PINNED";
    private static final String EXTRA_SEQ = "seq";

    private ActivityThemePreviewBinding binding;
    private final List<CustomTheme> themes = new ArrayList<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean lock;

    // One-Tap Setup state.
    private final Deque<CustomTheme.App> pinQueue = new ArrayDeque<>();
    private CustomTheme setupTheme;
    private boolean setupWallpaper;
    private int pinSeq;
    private boolean pinPending;
    private boolean pausedDuringPin;
    private int pinTotal;
    private Snackbar progress;

    private final BroadcastReceiver pinnedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            pinDone(intent.getIntExtra(EXTRA_SEQ, -1));
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityThemePreviewBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        ContextCompat.registerReceiver(this, pinnedReceiver, new IntentFilter(ACTION_PINNED),
                ContextCompat.RECEIVER_NOT_EXPORTED);

        binding.btnBack.setOnClickListener(v -> finish());
        binding.btnEdit.setOnClickListener(v -> {
            CustomTheme t = current();
            if (t != null) startActivity(new Intent(this, ThemeEditorActivity.class).putExtra(EXTRA_ID, t.id));
        });
        binding.btnDelete.setOnClickListener(v -> confirmDelete());
        binding.btnSetup.setOnClickListener(v -> askSetup());
        binding.screenToggle.addOnButtonCheckedListener((group, id, checked) -> {
            if (!checked) return;
            lock = id == R.id.btnLock;
            binding.pager.getAdapter().notifyDataSetChanged();
            // The rebound pages don't go through the page transformer again on their own and
            // would sit off-centre at the wrong scale.
            binding.pager.post(binding.pager::requestTransform);
        });

        ViewPager2 pager = binding.pager;
        int peek = Math.round(48 * getResources().getDisplayMetrics().density);
        RecyclerView rv = (RecyclerView) pager.getChildAt(0);
        rv.setPadding(peek, 0, peek, 0);
        rv.setClipToPadding(false);
        pager.setOffscreenPageLimit(1);
        CompositePageTransformer transformer = new CompositePageTransformer();
        transformer.addTransformer(new MarginPageTransformer(Math.round(12 * getResources().getDisplayMetrics().density)));
        transformer.addTransformer((page, pos) -> {
            float s = 0.88f + 0.12f * (1f - Math.min(1f, Math.abs(pos)));
            page.setScaleX(s);
            page.setScaleY(s);
        });
        pager.setPageTransformer(transformer);
        pager.setAdapter(new PageAdapter());
        pager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                showCurrent();
            }
        });
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            binding.backdrop.setRenderEffect(RenderEffect.createBlurEffect(60f, 60f, Shader.TileMode.CLAMP));
        }
        binding.backdrop.setColorFilter(android.graphics.Color.argb(120, 0, 0, 0));
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadThemes();
        if (pinPending && pausedDuringPin) {
            // The confirmation was answered (added or cancelled); move on either way.
            int seq = pinSeq;
            main.postDelayed(() -> pinDone(seq), 400);
        }
    }

    @Override
    protected void onPause() {
        if (pinPending) pausedDuringPin = true;
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(pinnedReceiver);
        main.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private void loadThemes() {
        Context app = getApplicationContext();
        String wanted = current() != null ? current().id : getIntent().getStringExtra(EXTRA_ID);
        new Thread(() -> {
            List<CustomTheme> list = ThemeStore.list(app);
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                if (list.isEmpty()) {
                    finish();
                    return;
                }
                themes.clear();
                themes.addAll(list);
                binding.pager.getAdapter().notifyDataSetChanged();
                int index = 0;
                for (int i = 0; i < themes.size(); i++) if (themes.get(i).id.equals(wanted)) index = i;
                binding.pager.setCurrentItem(index, false);
                binding.pager.post(binding.pager::requestTransform);
                showCurrent();
            });
        }, "ThemePreviewLoad").start();
    }

    private CustomTheme current() {
        int i = binding.pager.getCurrentItem();
        return i >= 0 && i < themes.size() ? themes.get(i) : null;
    }

    private void showCurrent() {
        CustomTheme t = current();
        if (t == null) return;
        binding.themeName.setText(t.name);
        binding.counter.setText(getString(R.string.preview_counter, binding.pager.getCurrentItem() + 1, themes.size()));
        Glide.with(this).load(ThemeStore.wallpaper(this, t))
                .signature(new com.bumptech.glide.signature.ObjectKey(t.updated))
                .override(160).centerCrop().into(binding.backdrop);
    }

    private void confirmDelete() {
        CustomTheme t = current();
        if (t == null) return;
        new MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.delete_theme_title, t.name))
                .setMessage(R.string.delete_theme_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.delete, (d, w) -> {
                    ThemeStore.delete(this, t);
                    loadThemes();
                })
                .show();
    }

    // ---- One-Tap Setup ----

    private void askSetup() {
        CustomTheme t = current();
        if (t == null || pinPending) return;
        boolean[] checked = {true, true};
        String[] items = {getString(R.string.setup_icons, t.apps.size()), getString(R.string.setup_wallpaper)};
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.one_tap_setup)
                .setMultiChoiceItems(items, checked, (d, which, isChecked) -> checked[which] = isChecked)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.apply, (d, w) -> startSetup(t, checked[0], checked[1]))
                .show();
    }

    private void startSetup(CustomTheme theme, boolean icons, boolean wallpaper) {
        setupTheme = theme.copy();
        setupWallpaper = wallpaper;
        pinQueue.clear();
        if (!icons) {
            finishSetup(0);
            return;
        }
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(this)) {
            Toast.makeText(this, R.string.pin_unsupported_icons, Toast.LENGTH_LONG).show();
            finishSetup(0);
            return;
        }
        binding.btnSetup.setEnabled(false);
        Context app = getApplicationContext();
        CustomTheme t = setupTheme;
        new Thread(() -> {
            List<CustomTheme.App> missing = ThemeShortcuts.updatePinned(app, t);
            // Warm the icon cache so each request goes out without rendering on the main thread.
            for (CustomTheme.App a : missing) ThemeShortcuts.build(app, t, a);
            int restyled = t.apps.size() - missing.size();
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                pinQueue.addAll(missing);
                pinTotal = missing.size();
                if (restyled > 0) Toast.makeText(this, getString(R.string.icons_restyled, restyled), Toast.LENGTH_SHORT).show();
                if (pinTotal > 0) {
                    progress = Snackbar.make(binding.root, "", Snackbar.LENGTH_INDEFINITE)
                            .setAction(R.string.stop, v -> {
                                pinQueue.clear();
                                pinPending = false;
                                finishSetup(pinTotal);
                            });
                    progress.show();
                }
                pinNext();
            });
        }, "ThemeShortcuts").start();
    }

    private void pinNext() {
        CustomTheme.App app = pinQueue.poll();
        if (app == null) {
            finishSetup(pinTotal);
            return;
        }
        int seq = ++pinSeq;
        pinPending = true;
        pausedDuringPin = false;
        if (progress != null) {
            progress.setText(getString(R.string.adding_icon, pinTotal - pinQueue.size(), pinTotal, app.label));
        }
        Intent cb = new Intent(ACTION_PINNED).setPackage(getPackageName()).putExtra(EXTRA_SEQ, seq);
        PendingIntent pi = PendingIntent.getBroadcast(this, seq, cb,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        try {
            ShortcutManagerCompat.requestPinShortcut(this, ThemeShortcuts.build(this, setupTheme, app), pi.getIntentSender());
        } catch (RuntimeException e) {
            pinDone(seq);
        }
    }

    private void pinDone(int seq) {
        if (!pinPending || seq != pinSeq) return;
        pinPending = false;
        main.postDelayed(this::pinNext, 300);
    }

    private void finishSetup(int asked) {
        if (progress != null) {
            progress.dismiss();
            progress = null;
        }
        binding.btnSetup.setEnabled(true);
        if (asked > 0) Toast.makeText(this, R.string.icons_done, Toast.LENGTH_SHORT).show();
        if (!setupWallpaper || setupTheme == null) return;
        // Wallpaper last: a wallpaper change can recolour and restart the app on some phones.
        setupWallpaper = false;
        Context app = getApplicationContext();
        CustomTheme t = setupTheme;
        Toast.makeText(this, R.string.setting_wallpaper, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            int msg;
            Bitmap bmp = ThemeStore.decodeWallpaper(app, t, 4096);
            try {
                if (bmp == null) throw new IOException("no wallpaper");
                WallpaperHelper.setStaticWallpaper(app, bmp);
                msg = R.string.wallpaper_set;
            } catch (IOException | RuntimeException e) {
                msg = R.string.wallpaper_set_failed;
            } finally {
                if (bmp != null) bmp.recycle();
            }
            int finalMsg = msg;
            main.post(() -> Toast.makeText(app, finalMsg, Toast.LENGTH_SHORT).show());
        }, "ThemeWallpaper").start();
    }

    private class PageAdapter extends RecyclerView.Adapter<PageAdapter.Holder> {

        class Holder extends RecyclerView.ViewHolder {
            final HomeMockView mock;

            Holder(View v) {
                super(v);
                mock = v.findViewById(R.id.mock);
            }
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_theme_page, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            h.mock.setTheme(themes.get(position));
            h.mock.setLock(lock);
        }

        @Override
        public int getItemCount() {
            return themes.size();
        }
    }
}
