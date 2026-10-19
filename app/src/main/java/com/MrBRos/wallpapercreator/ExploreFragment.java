package com.MrBRos.wallpapercreator;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;

import com.MrBRos.wallpapercreator.databinding.FragmentExploreBinding;
import com.google.android.material.chip.Chip;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.Query;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Wallpapers tab: banner, category chips, Hot/New sort and a 2-column card grid.
 * "Explore" shows public cloud wallpapers; "Mine" shows saved files plus the user's uploads.
 * Without Firebase both modes fall back to the wallpapers saved on this device.
 */
public class ExploreFragment extends Fragment {

    private static final int HOT_BADGE_COUNT = 4;

    private FragmentExploreBinding binding;
    private WallpaperCardAdapter adapter;
    private final List<WallpaperCard> loaded = new ArrayList<>();
    private boolean mine;
    private boolean hot = true;
    @Nullable
    private String category;
    private String search = "";
    private int loadGeneration;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        binding = FragmentExploreBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        adapter = new WallpaperCardAdapter(this::onCardClick, this::onCardLongClick);
        // 2 columns on phones, more on wide screens (e.g. an unfolded Fold).
        int widthDp = getResources().getConfiguration().screenWidthDp;
        binding.grid.setLayoutManager(new GridLayoutManager(requireContext(), Math.max(2, widthDp / 180)));
        binding.grid.setAdapter(adapter);
        binding.grid.setNestedScrollingEnabled(false);

        binding.banner.setOnClickListener(v -> ((MainActivity) requireActivity()).selectTab(R.id.nav_create));
        buildChips();
        binding.sortToggle.check(R.id.sortHot);
        binding.sortToggle.addOnButtonCheckedListener((g, id, checked) -> {
            if (!checked) return;
            hot = id == R.id.sortHot;
            load();
        });
        binding.swipe.setOnRefreshListener(this::load);
        binding.searchInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                search = s.toString().trim().toLowerCase(Locale.ROOT);
                applyFilter();
            }
        });
        applyMode();
    }

    @Override
    public void onResume() {
        super.onResume();
        load();
    }

    @Override
    public void onDestroyView() {
        binding = null;
        super.onDestroyView();
    }

    /** Switches between Explore (public) and Mine. */
    public void setMine(boolean mine) {
        if (this.mine == mine) return;
        this.mine = mine;
        if (binding != null) {
            applyMode();
            load();
        }
    }

    public void toggleSearch() {
        if (binding == null) return;
        boolean show = binding.searchLayout.getVisibility() != View.VISIBLE;
        binding.searchLayout.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) {
            binding.searchInput.requestFocus();
        } else {
            binding.searchInput.setText("");
        }
    }

    private void applyMode() {
        int exploreOnly = mine ? View.GONE : View.VISIBLE;
        binding.banner.setVisibility(exploreOnly);
        binding.chipScroll.setVisibility(exploreOnly);
        binding.sortToggle.setVisibility(exploreOnly);
        binding.sectionTitle.setText(mine ? R.string.section_mine : R.string.section_popular);
    }

    private void buildChips() {
        LayoutInflater inflater = getLayoutInflater();
        addChip(inflater, getString(R.string.chip_all), null, true);
        for (int i = 0; i < Categories.IDS.length; i++) {
            addChip(inflater, Categories.LABELS[i], Categories.IDS[i], false);
        }
    }

    private void addChip(LayoutInflater inflater, String label, @Nullable String id, boolean checked) {
        Chip chip = (Chip) inflater.inflate(R.layout.view_category_chip, binding.chips, false);
        chip.setText(label);
        chip.setId(View.generateViewId());
        chip.setChecked(checked);
        chip.setOnCheckedChangeListener((c, isChecked) -> {
            if (!isChecked) return;
            category = id;
            load();
        });
        binding.chips.addView(chip);
    }

    // --- Loading ---------------------------------------------------------------------------

    private void load() {
        if (binding == null) return;
        int gen = ++loadGeneration;
        boolean cloud = FirebaseHelper.isAvailable(requireContext());
        binding.cloudOffNote.setVisibility(cloud ? View.GONE : View.VISIBLE);

        List<WallpaperCard> cards = new ArrayList<>();
        if (!mine) {
            for (String asset : FeaturedWallpapers.list(requireContext(), category)) {
                cards.add(WallpaperCard.featured(asset));
            }
        }
        if (mine || (!cloud && category == null)) {
            for (String path : ImageExporter.getSavedPaths(requireContext())) {
                cards.add(WallpaperCard.local(path));
            }
        }
        if (!cloud) {
            show(gen, cards);
            return;
        }

        Query query;
        if (mine) {
            FirebaseUser user = FirebaseHelper.currentUser(requireContext());
            if (user == null) {
                show(gen, cards);
                return;
            }
            query = FirebaseHelper.mineQuery(user.getUid());
        } else {
            query = FirebaseHelper.publicQuery(category, hot);
        }
        binding.swipe.setRefreshing(true);
        query.get().addOnCompleteListener(requireActivity(), t -> {
            if (gen != loadGeneration || binding == null) return;
            if (!t.isSuccessful()) {
                String msg = t.getException() != null ? t.getException().getMessage() : "";
                Toast.makeText(requireContext(), getString(R.string.gallery_load_failed) + ": " + msg,
                        Toast.LENGTH_LONG).show();
            } else {
                int i = 0;
                for (DocumentSnapshot doc : t.getResult().getDocuments()) {
                    CloudWallpaper wp = CloudWallpaper.from(doc);
                    String badge = null;
                    if (mine) {
                        badge = FirebaseHelper.VISIBILITY_PRIVATE.equals(wp.visibility) ? "Private" : "Public";
                    } else if (hot && i < HOT_BADGE_COUNT && wp.likesCount > 0) {
                        badge = "Hot";
                    }
                    cards.add(WallpaperCard.cloud(wp, badge));
                    i++;
                }
            }
            show(gen, cards);
        });
    }

    private void show(int gen, List<WallpaperCard> cards) {
        if (gen != loadGeneration || binding == null) return;
        binding.swipe.setRefreshing(false);
        loaded.clear();
        loaded.addAll(cards);
        applyFilter();
    }

    private void applyFilter() {
        if (binding == null) return;
        List<WallpaperCard> visible = new ArrayList<>();
        for (WallpaperCard c : loaded) {
            if (search.isEmpty() || c.title.toLowerCase(Locale.ROOT).contains(search)) visible.add(c);
        }
        adapter.submit(visible);
        binding.empty.setVisibility(visible.isEmpty() ? View.VISIBLE : View.GONE);
        binding.empty.setText(mine && FirebaseHelper.isAvailable(requireContext())
                && FirebaseHelper.currentUser(requireContext()) == null
                ? R.string.mine_empty_signed_out : R.string.gallery_empty);
    }

    // --- Card actions ----------------------------------------------------------------------

    private void onCardClick(WallpaperCard card) {
        if (card.isLocal() || card.isFeatured()) {
            // Full-screen preview, swipeable across the featured/saved cards currently shown.
            ArrayList<String> sources = new ArrayList<>();
            int index = 0;
            for (WallpaperCard c : adapter.items()) {
                if (c.isCloud()) continue;
                if (c == card) index = sources.size();
                sources.add(c.isFeatured() ? PreviewActivity.ASSET_PREFIX + c.assetPath : c.localPath);
            }
            startActivity(PreviewActivity.intent(requireContext(), sources, index));
        } else {
            startActivity(new Intent(requireContext(), WallpaperDetailActivity.class)
                    .putExtra(WallpaperDetailActivity.EXTRA_DOC_ID, card.cloudId));
        }
    }

    private void onCardLongClick(WallpaperCard card) {
        if (!card.isLocal()) return;
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.delete_saved_title)
                .setMessage(R.string.delete_saved_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.delete, (d, w) -> new Thread(() -> {
                    ImageExporter.removeSaved(requireContext().getApplicationContext(), card.localPath);
                    if (isAdded()) requireActivity().runOnUiThread(this::load);
                }).start())
                .show();
    }
}
