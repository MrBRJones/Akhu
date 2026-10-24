package com.MrBRos.wallpapercreator;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.IdRes;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;

import com.MrBRos.wallpapercreator.databinding.ActivityMainBinding;

/**
 * Home shell in the style of the VibeWalls reference: custom top bar (title tabs, search, settings)
 * over bottom-nav tabs (Wallpapers, Motion, Themes, Profile) around a TikTok-style
 * centre "+" button that goes straight to the Create screen.
 */
public class MainActivity extends AppCompatActivity {

    public static final String ACTION_SET_LATEST = "com.MrBRos.wallpapercreator.SET_LATEST";
    public static final String ACTION_OPEN_IN_CREATE = "com.MrBRos.wallpapercreator.OPEN_IN_CREATE";
    public static final String EXTRA_PATH = "path";
    private static final String STATE_TAB = "tab";
    private static final String STATE_MINE = "mine";

    /** A grid that is in multi-select; see {@link GridSelection}. */
    public interface SelectionHost {
        boolean isActive();

        int selectedCount();

        int selectableCount();

        void setAllSelected(boolean all);

        void endSelection();

        void shareSelected();

        void deleteSelected();
    }

    private ActivityMainBinding binding;
    @Nullable
    private SelectionHost selection;
    private final OnBackPressedCallback endSelectionOnBack = new OnBackPressedCallback(false) {
        @Override
        public void handleOnBackPressed() {
            if (selection != null) selection.endSelection();
        }
    };
    private ExploreFragment explore;
    private LiveFragment live;
    private CreateFragment create;
    private ThemesFragment themes;
    private ProfileFragment profile;
    @IdRes
    private int currentTab = R.id.nav_wallpapers;
    private boolean mine;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        FragmentManager fm = getSupportFragmentManager();
        if (savedInstanceState == null) {
            explore = new ExploreFragment();
            live = new LiveFragment();
            create = new CreateFragment();
            themes = new ThemesFragment();
            profile = new ProfileFragment();
            fm.beginTransaction()
                    .add(R.id.container, explore, "explore")
                    .add(R.id.container, live, "live").hide(live)
                    .add(R.id.container, create, "create").hide(create)
                    .add(R.id.container, themes, "themes").hide(themes)
                    .add(R.id.container, profile, "profile").hide(profile)
                    .commitNow();
        } else {
            explore = (ExploreFragment) fm.findFragmentByTag("explore");
            live = (LiveFragment) fm.findFragmentByTag("live");
            create = (CreateFragment) fm.findFragmentByTag("create");
            themes = (ThemesFragment) fm.findFragmentByTag("themes");
            profile = (ProfileFragment) fm.findFragmentByTag("profile");
            currentTab = savedInstanceState.getInt(STATE_TAB, R.id.nav_wallpapers);
            mine = savedInstanceState.getBoolean(STATE_MINE, false);
        }

        binding.bottomNav.setOnItemSelectedListener(item -> {
            showTab(item.getItemId());
            return true;
        });
        binding.bottomNav.setOnItemReselectedListener(item -> { });

        binding.titlePrimary.setOnClickListener(v -> {
            if (currentTab == R.id.nav_wallpapers) setMine(false);
        });
        binding.titleSecondary.setOnClickListener(v -> setMine(true));
        binding.btnSearch.setOnClickListener(v -> explore.toggleSearch());
        binding.btnSettings.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        binding.btnCenterCreate.setOnClickListener(v -> selectTab(R.id.nav_create));
        binding.btnSelectAll.setOnClickListener(v -> {
            if (selection != null) selection.setAllSelected(selection.selectedCount() < selection.selectableCount());
        });
        binding.btnSelectCancel.setOnClickListener(v -> {
            if (selection != null) selection.endSelection();
        });
        binding.btnSelectShare.setOnClickListener(v -> {
            if (selection != null) selection.shareSelected();
        });
        binding.btnSelectDelete.setOnClickListener(v -> {
            if (selection != null) selection.deleteSelected();
        });
        getOnBackPressedDispatcher().addCallback(this, endSelectionOnBack);

        explore.setMine(mine);
        binding.bottomNav.setSelectedItemId(currentTab);
        showTab(currentTab);
        handleIntent(getIntent());
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(STATE_TAB, currentTab);
        outState.putBoolean(STATE_MINE, mine);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    /** Programmatic tab switch that keeps the bottom nav in sync. */
    public void selectTab(@IdRes int tab) {
        if (binding.bottomNav.getSelectedItemId() != tab) {
            binding.bottomNav.setSelectedItemId(tab);
        } else {
            showTab(tab);
        }
    }

    /** Wallpapers tab, switched to the signed-in user's own uploads. */
    public void showMine() {
        selectTab(R.id.nav_wallpapers);
        setMine(true);
    }

    /** Opens a saved file in the Create tab. */
    public void openInCreate(String path) {
        selectTab(R.id.nav_create);
        create.loadImage(path);
    }

    /**
     * Called by a grid whenever its selection changes: shows "N selected" and Share / Delete over
     * the top bar and bottom nav while it's selecting, and puts them back when it stops.
     */
    public void onSelectionChanged(SelectionHost host) {
        boolean selecting = host.isActive();
        if (!selecting) {
            if (selection != host) return;
            selection = null;
        } else {
            if (selection != null && selection != host) selection.endSelection();
            selection = host;
        }
        binding.selectionTop.setVisibility(selecting ? View.VISIBLE : View.GONE);
        binding.selectionBottom.setVisibility(selecting ? View.VISIBLE : View.GONE);
        binding.btnCenterCreate.setVisibility(selecting ? View.GONE : View.VISIBLE);
        endSelectionOnBack.setEnabled(selecting);
        if (!selecting) return;
        int count = host.selectedCount();
        binding.selectionTitle.setText(count == 0 ? getString(R.string.select_items)
                : getString(R.string.select_count, count));
        boolean all = count > 0 && count == host.selectableCount();
        binding.selectAllIcon.setImageResource(all ? R.drawable.ic_check_circle : R.drawable.ic_circle_outline);
        binding.selectAllIcon.setImageTintList(all ? null
                : ContextCompat.getColorStateList(this, R.color.text_secondary));
        float enabled = count > 0 ? 1f : 0.4f;
        binding.btnSelectShare.setAlpha(enabled);
        binding.btnSelectDelete.setAlpha(enabled);
    }

    private void showTab(@IdRes int tab) {
        if (selection != null) selection.endSelection();
        currentTab = tab;
        Fragment target = fragmentFor(tab);
        FragmentTransaction tx = getSupportFragmentManager().beginTransaction();
        for (Fragment f : new Fragment[]{explore, live, create, themes, profile}) {
            if (f == target) tx.show(f);
            else tx.hide(f);
        }
        tx.commit();
        updateTopBar();
    }

    private Fragment fragmentFor(@IdRes int tab) {
        if (tab == R.id.nav_live) return live;
        if (tab == R.id.nav_create) return create;
        if (tab == R.id.nav_themes) return themes;
        if (tab == R.id.nav_profile) return profile;
        return explore;
    }

    private void setMine(boolean mine) {
        if (selection != null) selection.endSelection();
        this.mine = mine;
        explore.setMine(mine);
        updateTopBar();
    }

    private void updateTopBar() {
        boolean wallpapers = currentTab == R.id.nav_wallpapers;
        binding.titleSecondary.setVisibility(wallpapers ? View.VISIBLE : View.GONE);
        binding.btnSearch.setVisibility(wallpapers ? View.VISIBLE : View.GONE);
        if (wallpapers) {
            binding.titlePrimary.setText(R.string.tab_explore);
            styleTitle(binding.titlePrimary, !mine);
            styleTitle(binding.titleSecondary, mine);
        } else {
            int title = currentTab == R.id.nav_live ? R.string.nav_live
                    : currentTab == R.id.nav_create ? R.string.nav_create
                    : currentTab == R.id.nav_themes ? R.string.nav_themes : R.string.nav_profile;
            binding.titlePrimary.setText(title);
            styleTitle(binding.titlePrimary, true);
        }
    }

    private void styleTitle(android.widget.TextView view, boolean selected) {
        view.setTextColor(ContextCompat.getColor(this, selected ? R.color.text_primary : R.color.text_secondary));
        view.setTextSize(selected ? 24 : 20);
        view.setTypeface(null, selected ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
    }

    private void handleIntent(Intent intent) {
        if (intent != null && ACTION_OPEN_IN_CREATE.equals(intent.getAction())) {
            String path = intent.getStringExtra(EXTRA_PATH);
            intent.setAction(Intent.ACTION_MAIN);
            if (path != null) openInCreate(path);
            return;
        }
        if (intent == null || !ACTION_SET_LATEST.equals(intent.getAction())) return;
        intent.setAction(Intent.ACTION_MAIN); // don't re-run on rotation
        android.content.Context app = getApplicationContext();
        new Thread(() -> {
            int msg;
            try {
                msg = WidgetSetWallpaperReceiver.setLatest(app) ? R.string.wallpaper_set : R.string.no_saved_wallpapers;
            } catch (Exception e) {
                msg = R.string.wallpaper_set_failed;
            }
            int finalMsg = msg;
            runOnUiThread(() -> Toast.makeText(app, finalMsg, Toast.LENGTH_SHORT).show());
        }, "ShortcutSetLatest").start();
    }
}
