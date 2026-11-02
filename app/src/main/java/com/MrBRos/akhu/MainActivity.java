package com.MrBRos.akhu;

import android.content.Intent;
import android.os.Bundle;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.IdRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;

import com.google.android.material.tabs.TabLayout;
import com.google.firebase.firestore.ListenerRegistration;

import com.MrBRos.akhu.databinding.ActivityMainBinding;

/**
 * Home shell in the style of the VibeWalls reference: custom top bar (title tabs, search, settings)
 * over bottom-nav tabs (Wallpapers, Following, Inbox, Profile) around a TikTok-style
 * centre "+" button that goes straight to the Create screen. Wallpapers has a row of sections
 * (2D, 3D, Motion, Themes) under the top bar.
 */
public class MainActivity extends AppCompatActivity {

    public static final String ACTION_SET_LATEST = "com.MrBRos.akhu.SET_LATEST";
    public static final String ACTION_OPEN_IN_CREATE = "com.MrBRos.akhu.OPEN_IN_CREATE";
    /** From a like/comment/share notification. */
    public static final String ACTION_OPEN_INBOX = "com.MrBRos.akhu.OPEN_INBOX";
    private static final String KEY_ASKED_NOTIFY = "asked_notifications";
    public static final String EXTRA_PATH = "path";
    private static final String STATE_TAB = "tab";
    private static final String STATE_SECTION = "section";
    /** Wallpapers sections, in the order of the row at the top. */
    private static final int SECTION_2D = 0, SECTION_3D = 1, SECTION_MOTION = 2, SECTION_THEMES = 3;

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
    /** Shows new likes, comments and shares while the app is open. */
    @Nullable
    private ListenerRegistration notices;
    private final ActivityResultLauncher<String> notifyPermission =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> { });
    @Nullable
    private SelectionHost selection;
    private final OnBackPressedCallback endSelectionOnBack = new OnBackPressedCallback(false) {
        @Override
        public void handleOnBackPressed() {
            if (selection != null) selection.endSelection();
        }
    };
    private ExploreFragment explore;
    private LiveFragment fourD;
    private LiveFragment motion;
    private ThemesFragment themes;
    private FollowingFragment following;
    private CreateFragment create;
    private InboxFragment inbox;
    private ProfileFragment profile;
    private int section = SECTION_2D;
    @IdRes
    private int currentTab = R.id.nav_wallpapers;
    /** Bottom-nav tabs a sideways swipe moves between, in nav order. */
    private final int[] swipeTabs = {R.id.nav_wallpapers, R.id.nav_following, R.id.nav_inbox, R.id.nav_profile};
    private GestureDetector swipe;
    /** +1 / -1 while a swipe is switching to the next / previous tab, else 0. */
    private int swipeDirection;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        FragmentManager fm = getSupportFragmentManager();
        if (savedInstanceState == null) {
            explore = new ExploreFragment();
            fourD = LiveFragment.newInstance(LiveFragment.FOURD);
            motion = LiveFragment.newInstance(LiveFragment.VIDEOS);
            themes = new ThemesFragment();
            following = new FollowingFragment();
            create = new CreateFragment();
            inbox = new InboxFragment();
            profile = new ProfileFragment();
            fm.beginTransaction()
                    .add(R.id.container, explore, "explore")
                    .add(R.id.container, fourD, "fourd").hide(fourD)
                    .add(R.id.container, motion, "motion").hide(motion)
                    .add(R.id.container, themes, "themes").hide(themes)
                    .add(R.id.container, following, "following").hide(following)
                    .add(R.id.container, create, "create").hide(create)
                    .add(R.id.container, inbox, "inbox").hide(inbox)
                    .add(R.id.container, profile, "profile").hide(profile)
                    .commitNow();
        } else {
            explore = (ExploreFragment) fm.findFragmentByTag("explore");
            fourD = (LiveFragment) fm.findFragmentByTag("fourd");
            motion = (LiveFragment) fm.findFragmentByTag("motion");
            themes = (ThemesFragment) fm.findFragmentByTag("themes");
            following = (FollowingFragment) fm.findFragmentByTag("following");
            create = (CreateFragment) fm.findFragmentByTag("create");
            inbox = (InboxFragment) fm.findFragmentByTag("inbox");
            profile = (ProfileFragment) fm.findFragmentByTag("profile");
            currentTab = savedInstanceState.getInt(STATE_TAB, R.id.nav_wallpapers);
            section = savedInstanceState.getInt(STATE_SECTION, SECTION_2D);
        }

        binding.bottomNav.setOnItemSelectedListener(item -> {
            showTab(item.getItemId());
            return true;
        });
        binding.bottomNav.setOnItemReselectedListener(item -> { });

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

        for (int label : new int[]{R.string.section_2d, R.string.section_3d, R.string.nav_live, R.string.nav_themes}) {
            binding.sections.addTab(binding.sections.newTab().setText(label), false);
        }
        binding.sections.selectTab(binding.sections.getTabAt(section));
        binding.sections.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                showSection(tab.getPosition());
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) {
            }

            @Override
            public void onTabReselected(TabLayout.Tab tab) {
            }
        });

        binding.bottomNav.setSelectedItemId(currentTab);
        showTab(currentTab);
        handleIntent(getIntent());

        // Swipe left/right over the page to move through the Wallpapers sections, then the tabs.
        swipe = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onFling(@Nullable MotionEvent down, @NonNull MotionEvent up, float vx, float vy) {
                if (down == null || selection != null) return false;
                float dx = up.getX() - down.getX(), dy = up.getY() - down.getY();
                if (Math.abs(dx) < 80 * getResources().getDisplayMetrics().density || Math.abs(dx) < 2 * Math.abs(dy)) return false;
                if (!contains(binding.container, down.getX(), down.getY())
                        || scrollsSideways(binding.container, down.getX(), down.getY())) return false;
                int step = dx < 0 ? 1 : -1;
                if (currentTab == R.id.nav_wallpapers && section + step >= SECTION_2D && section + step <= SECTION_THEMES) {
                    swipeDirection = step;
                    binding.sections.selectTab(binding.sections.getTabAt(section + step));
                    return true;
                }
                int i = 0;
                while (i < swipeTabs.length && swipeTabs[i] != currentTab) i++;
                int next = i + (dx < 0 ? 1 : -1);
                if (i == swipeTabs.length || next < 0 || next >= swipeTabs.length) return false;
                swipeDirection = dx < 0 ? 1 : -1;
                selectTab(swipeTabs[next]);
                return true;
            }
        });
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (swipe != null) swipe.onTouchEvent(ev);
        return super.dispatchTouchEvent(ev);
    }

    /** True when window point (x, y) is on the shown view. */
    private static boolean contains(View v, float x, float y) {
        if (v.getVisibility() != View.VISIBLE) return false;
        int[] at = new int[2];
        v.getLocationInWindow(at);
        return x >= at[0] && y >= at[1] && x < at[0] + v.getWidth() && y < at[1] + v.getHeight();
    }

    /** True when the touch is on something that scrolls sideways itself, like the category chips. */
    private static boolean scrollsSideways(View v, float x, float y) {
        if (!contains(v, x, y)) return false;
        if (v.canScrollHorizontally(1) || v.canScrollHorizontally(-1)) return true;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) if (scrollsSideways(g.getChildAt(i), x, y)) return true;
        }
        return false;
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(STATE_TAB, currentTab);
        outState.putInt(STATE_SECTION, section);
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (FirebaseHelper.isAvailable(this) && FirebaseHelper.currentUser(this) != null) {
            askToNotify();
            if (notices == null) notices = Notices.watch(this);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (notices != null) {
            notices.remove();
            notices = null;
        }
    }

    /** Once, when signed in: asks to show notifications for likes, comments and shares (Android 13+). */
    private void askToNotify() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) return;
        android.content.SharedPreferences prefs = getSharedPreferences("app_settings", MODE_PRIVATE);
        if (prefs.getBoolean(KEY_ASKED_NOTIFY, false)) return;
        prefs.edit().putBoolean(KEY_ASKED_NOTIFY, true).apply();
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            notifyPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS);
        }
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

    /**
     * Shows a tab's page with M3 motion: a swipe slides along the X axis in its direction, a tap
     * on the nav fades through.
     */
    private void showTab(@IdRes int tab) {
        if (selection != null) selection.endSelection();
        boolean changed = tab != currentTab;
        currentTab = tab;
        show(fragmentFor(tab), changed);
    }

    /** Shows one Wallpapers section (2D, 3D, Motion or Themes). */
    private void showSection(int section) {
        if (selection != null) selection.endSelection();
        boolean changed = section != this.section;
        this.section = section;
        if (currentTab == R.id.nav_wallpapers) show(fragmentFor(currentTab), changed);
        else selectTab(R.id.nav_wallpapers);
    }

    private void show(Fragment target, boolean changed) {
        FragmentTransaction tx = getSupportFragmentManager().beginTransaction();
        if (changed && swipeDirection > 0) {
            tx.setCustomAnimations(R.anim.axis_x_in_next, R.anim.axis_x_out_next);
        } else if (changed && swipeDirection < 0) {
            tx.setCustomAnimations(R.anim.axis_x_in_prev, R.anim.axis_x_out_prev);
        } else if (changed) {
            tx.setCustomAnimations(R.anim.fade_through_in, R.anim.fade_through_out);
        }
        swipeDirection = 0;
        for (Fragment f : new Fragment[]{explore, fourD, motion, themes, following, create, inbox, profile}) {
            if (f == target) tx.show(f);
            else tx.hide(f);
        }
        tx.commit();
        updateTopBar();
    }

    private Fragment fragmentFor(@IdRes int tab) {
        if (tab == R.id.nav_following) return following;
        if (tab == R.id.nav_create) return create;
        if (tab == R.id.nav_inbox) return inbox;
        if (tab == R.id.nav_profile) return profile;
        if (section == SECTION_3D) return fourD;
        if (section == SECTION_MOTION) return motion;
        if (section == SECTION_THEMES) return themes;
        return explore;
    }

    private void updateTopBar() {
        boolean wallpapers = currentTab == R.id.nav_wallpapers;
        binding.sections.setVisibility(wallpapers ? View.VISIBLE : View.GONE);
        // Search belongs to the 2D section.
        binding.btnSearch.setVisibility(wallpapers && section == SECTION_2D ? View.VISIBLE : View.GONE);
        int title = wallpapers ? R.string.nav_wallpapers
                : currentTab == R.id.nav_following ? R.string.nav_following
                : currentTab == R.id.nav_create ? R.string.nav_create
                : currentTab == R.id.nav_inbox ? R.string.nav_inbox : R.string.nav_profile;
        binding.titlePrimary.setText(title);
    }

    private void handleIntent(Intent intent) {
        if (intent != null && ACTION_OPEN_INBOX.equals(intent.getAction())) {
            intent.setAction(Intent.ACTION_MAIN);
            selectTab(R.id.nav_inbox);
            return;
        }
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
