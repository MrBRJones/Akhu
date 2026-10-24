package com.MrBRos.wallpapercreator;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.MrBRos.wallpapercreator.databinding.FragmentProfileBinding;
import com.google.android.material.tabs.TabLayout;
import com.google.firebase.auth.FirebaseUser;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Profile tab: who is signed in, then everything saved on this phone in 2D, 3D, Motion and Themes tabs. */
public class ProfileFragment extends Fragment {

    private FragmentProfileBinding binding;
    private WallpaperCardAdapter adapter2d, adapter3d, adapterMotion;
    private final List<GridSelection<?>> selections = new ArrayList<>();
    private final List<CustomTheme> themes = new ArrayList<>();
    private ThemeAdapter adapterThemes;
    /** Which tab is open; kept across visits to the Profile tab. */
    private static int selectedTab;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        binding = FragmentProfileBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        binding.btnAccount.setOnClickListener(v -> {
            if (!FirebaseHelper.isAvailable(requireContext())) {
                Toast.makeText(requireContext(), R.string.cloud_disabled, Toast.LENGTH_LONG).show();
                return;
            }
            startActivity(new Intent(requireContext(), AuthActivity.class));
        });
        binding.btnMyUploads.setOnClickListener(v -> ((MainActivity) requireActivity()).showMine());

        // Long-press any card to start selecting; Share and Delete then work on all the ticked ones.
        adapter2d = new WallpaperCardAdapter(card -> open(adapter2d, card));
        selections.add(adapter2d.enableSelection(this, CardActions.saved2d(this::loadSaved)));
        adapter3d = new WallpaperCardAdapter(card -> open(adapter3d, card));
        selections.add(adapter3d.enableSelection(this, CardActions.user3d(this::loadSaved)));
        // Motion videos reopen in the editor, where they can be trimmed again, downloaded or set.
        adapterMotion = new WallpaperCardAdapter(
                card -> startActivity(VideoEditorActivity.intent(requireContext(), new File(card.localPath), false, null)));
        selections.add(adapterMotion.enableSelection(this, CardActions.savedMotion(this::loadSaved)));
        adapterThemes = new ThemeAdapter();
        selections.add(adapterThemes.selection);
        setUpGrid(binding.grid2d, adapter2d);
        setUpGrid(binding.grid3d, adapter3d);
        setUpGrid(binding.gridMotion, adapterMotion);
        setUpGrid(binding.gridThemes, adapterThemes);

        binding.profileTabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                for (GridSelection<?> s : selections) s.endSelection();
                selectedTab = tab.getPosition();
                showPage();
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) {
            }

            @Override
            public void onTabReselected(TabLayout.Tab tab) {
            }
        });
        TabLayout.Tab tab = binding.profileTabs.getTabAt(selectedTab);
        if (tab != null) tab.select();
        showPage();
    }

    /** Shows the selected tab's grid and hides the other three. */
    private void showPage() {
        View[] pages = {binding.page2d, binding.page3d, binding.pageMotion, binding.pageThemes};
        for (int i = 0; i < pages.length; i++) pages[i].setVisibility(i == selectedTab ? View.VISIBLE : View.GONE);
    }

    private void setUpGrid(RecyclerView grid, RecyclerView.Adapter<?> adapter) {
        int widthDp = getResources().getConfiguration().screenWidthDp;
        grid.setLayoutManager(new GridLayoutManager(requireContext(), Math.max(3, widthDp / 150)));
        grid.setAdapter(adapter);
    }

    @Override
    public void onResume() {
        super.onResume();
        bindUser();
        loadSaved();
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (!hidden) {
            bindUser();
            loadSaved();
        }
    }

    @Override
    public void onDestroyView() {
        binding = null;
        super.onDestroyView();
    }

    private void bindUser() {
        if (binding == null) return;
        boolean cloud = FirebaseHelper.isAvailable(requireContext());
        FirebaseUser user = FirebaseHelper.currentUser(requireContext());
        if (user != null) {
            binding.name.setText(FirebaseHelper.displayNameOf(user));
            binding.subtitle.setText(user.getEmail());
            binding.btnAccount.setText(R.string.account);
        } else {
            binding.name.setText(R.string.not_signed_in);
            binding.subtitle.setText(cloud ? R.string.sign_in_hint : R.string.cloud_off_short);
            binding.btnAccount.setText(R.string.sign_in);
        }
        binding.btnMyUploads.setVisibility(user != null ? View.VISIBLE : View.GONE);
    }

    /** Reads the saved lists off the main thread, then fills each grid (or shows its empty note). */
    private void loadSaved() {
        if (binding == null) return;
        Context app = requireContext().getApplicationContext();
        new Thread(() -> {
            List<WallpaperCard> twoD = new ArrayList<>(), threeD = new ArrayList<>(), motion = new ArrayList<>();
            for (String p : ImageExporter.getSavedPaths(app)) twoD.add(WallpaperCard.local(p));
            for (String p : FourDWallpapers.list(app)) if (FourDWallpapers.isUser(p)) threeD.add(WallpaperCard.fourD(p));
            for (String p : MotionWallpapers.saved(app)) motion.add(WallpaperCard.savedMotion(p));
            List<CustomTheme> themeList = ThemeStore.list(app);
            if (getActivity() == null) return;
            getActivity().runOnUiThread(() -> {
                if (binding == null) return;
                show(adapter2d, twoD, binding.empty2d);
                show(adapter3d, threeD, binding.empty3d);
                show(adapterMotion, motion, binding.emptyMotion);
                themes.clear();
                themes.addAll(themeList);
                adapterThemes.notifyDataSetChanged();
                adapterThemes.selection.retain();
                binding.emptyThemes.setVisibility(themes.isEmpty() ? View.VISIBLE : View.GONE);
            });
        }, "ProfileLoad").start();
    }

    private static void show(WallpaperCardAdapter adapter, List<WallpaperCard> cards, TextView empty) {
        adapter.submit(cards);
        empty.setVisibility(cards.isEmpty() ? View.VISIBLE : View.GONE);
    }

    /** Full-screen preview, swipeable across that tab's items. */
    private void open(WallpaperCardAdapter adapter, WallpaperCard card) {
        ArrayList<String> sources = new ArrayList<>();
        int index = 0;
        for (WallpaperCard c : adapter.items()) {
            if (c == card) index = sources.size();
            sources.add(adapter == adapter3d ? PreviewActivity.FOURD_PREFIX + c.assetPath : c.localPath);
        }
        startActivity(PreviewActivity.intent(requireContext(), sources, index));
    }

    private class ThemeAdapter extends RecyclerView.Adapter<ThemeAdapter.Holder> {

        final GridSelection<CustomTheme> selection = new GridSelection<>(ProfileFragment.this, this, () -> themes,
                new ThemeSelectActions(requireContext(), ProfileFragment.this::loadSaved));

        class Holder extends RecyclerView.ViewHolder {
            final HomeMockView mock;
            final TextView title;
            final ImageView check;

            Holder(View v) {
                super(v);
                mock = v.findViewById(R.id.mock);
                title = v.findViewById(R.id.title);
                check = v.findViewById(R.id.selectCheck);
                mock.setWallpaperMax(480);
            }
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_theme_card, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            CustomTheme t = themes.get(position);
            h.mock.setTheme(t);
            h.title.setText(t.name);
            ThemeSelectActions.bindCheck(h.check, selection, t);
            h.itemView.setOnClickListener(v -> {
                if (selection.onTap(t)) return;
                startActivity(new Intent(requireContext(), ThemePreviewActivity.class)
                        .putExtra(ThemePreviewActivity.EXTRA_ID, t.id));
            });
            h.itemView.setOnLongClickListener(v -> selection.onLongPress(t));
        }

        @Override
        public int getItemCount() {
            return themes.size();
        }
    }
}
