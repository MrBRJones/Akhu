package com.MrBRos.akhu;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.MrBRos.akhu.databinding.FragmentLiveBinding;
import com.bumptech.glide.Glide;
import com.bumptech.glide.signature.ObjectKey;
import com.google.android.material.materialswitch.MaterialSwitch;

import java.io.File;

/** Live tab: current live image preview, quick effect toggles, set live and full settings. */
public class LiveFragment extends Fragment {

    private FragmentLiveBinding binding;
    private TiltSensor gridTilt;
    private boolean fourDTabShown;
    private java.util.List<String> fourD = new java.util.ArrayList<>();
    @Nullable
    private java.util.function.IntConsumer showTab;

    /**
     * The 4D grid's shared tilt sensor runs only while the 4D tab is selected and this screen is
     * actually on screen (resumed and not hidden behind another bottom-nav tab).
     */
    private void updateGridTilt() {
        boolean want = fourDTabShown && isResumed() && !isHidden() && FourDTileView.isSupported();
        if (want) {
            if (gridTilt == null) gridTilt = new TiltSensor(requireContext());
            gridTilt.start();
            FourDTileView.setSharedTilt(gridTilt);
        } else if (gridTilt != null) {
            gridTilt.stop();
            FourDTileView.setSharedTilt(null);
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        updateGridTilt();
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (!hidden) refreshFourD();
        updateGridTilt();
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        binding = FragmentLiveBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        binding.btnSetLive.setOnClickListener(v -> {
            File source = currentSource();
            if (source == null) {
                Toast.makeText(requireContext(), R.string.pick_first, Toast.LENGTH_SHORT).show();
                ((MainActivity) requireActivity()).selectTab(R.id.nav_create);
                return;
            }
            CreateFragment.applyLive(requireActivity(), source);
        });
        binding.btnAllSettings.setOnClickListener(v ->
                startActivity(new Intent(requireContext(), LiveWallpaperSettingsActivity.class)));
        bindMotionGrid();
    }

    /**
     * "Videos" and "4D motion" tabs share one grid; tapping a tile opens the swipeable
     * full-screen preview over that tab's items.
     */
    private void bindMotionGrid() {
        java.util.List<String> videos = MotionWallpapers.list(requireContext());
        fourD = FourDWallpapers.list(requireContext());
        WallpaperCardAdapter adapter = new WallpaperCardAdapter(card -> {
            boolean isFourD = card.isFourD();
            java.util.List<String> items = isFourD ? fourD : videos;
            String prefix = isFourD ? PreviewActivity.FOURD_PREFIX : PreviewActivity.VIDEO_PREFIX;
            java.util.ArrayList<String> sources = new java.util.ArrayList<>();
            for (String s : items) sources.add(prefix + s);
            startActivity(PreviewActivity.intent(requireContext(), sources, items.indexOf(card.assetPath)));
        });
        // Long-press starts multi-select; only the user's own 3D images can be picked.
        GridSelection<WallpaperCard> selection = adapter.enableSelection(this, CardActions.user3d(this::refreshFourD));
        int widthDp = getResources().getConfiguration().screenWidthDp;
        binding.motionGrid.setLayoutManager(new androidx.recyclerview.widget.GridLayoutManager(
                requireContext(), Math.max(3, widthDp / 180)));
        binding.motionGrid.setAdapter(adapter);

        showTab = tab -> {
            java.util.List<WallpaperCard> cards = new java.util.ArrayList<>();
            if (tab == 1) {
                for (String f : fourD) cards.add(WallpaperCard.fourD(f));
            } else {
                for (String v : videos) cards.add(WallpaperCard.motion(v));
            }
            binding.fourdHint.setVisibility(tab == 1 ? View.VISIBLE : View.GONE);
            adapter.submit(cards);
            fourDTabShown = tab == 1;
            updateGridTilt();
        };
        binding.motionTabs.addOnTabSelectedListener(new com.google.android.material.tabs.TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(com.google.android.material.tabs.TabLayout.Tab tab) {
                selection.endSelection();
                showTab.accept(tab.getPosition());
            }

            @Override
            public void onTabUnselected(com.google.android.material.tabs.TabLayout.Tab tab) {
            }

            @Override
            public void onTabReselected(com.google.android.material.tabs.TabLayout.Tab tab) {
            }
        });
        showTab.accept(binding.motionTabs.getSelectedTabPosition() < 0 ? 0 : binding.motionTabs.getSelectedTabPosition());
    }

    /** Picks up 4D images made or deleted since the grid was filled. */
    private void refreshFourD() {
        if (binding == null || showTab == null) return;
        java.util.List<String> now = FourDWallpapers.list(requireContext());
        if (now.equals(fourD)) return;
        fourD = now;
        if (binding.motionTabs.getSelectedTabPosition() == 1) showTab.accept(1);
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshFourD();
        bindPreview();
        buildToggles();
        updateGridTilt();
    }

    @Override
    public void onDestroyView() {
        binding = null;
        super.onDestroyView();
    }

    @Nullable
    private File currentSource() {
        String path = EffectConfig.prefs(requireContext()).getString(EffectConfig.KEY_CURRENT_PATH, null);
        if (path == null || !new File(path).exists()) path = ImageExporter.getLatestPath(requireContext());
        return path == null ? null : new File(path);
    }

    private void bindPreview() {
        File source = currentSource();
        if (source == null) {
            binding.preview.setImageResource(R.drawable.ic_nav_motion);
            binding.previewHint.setVisibility(View.VISIBLE);
            return;
        }
        binding.previewHint.setVisibility(View.GONE);
        Glide.with(this).load(source).signature(new ObjectKey(source.lastModified()))
                .centerCrop().into(binding.preview);
    }

    /** One switch per effect layer plus particles; each change is pushed to the running engine. */
    private void buildToggles() {
        SharedPreferences prefs = EffectConfig.prefs(requireContext());
        EffectConfig cfg = EffectConfig.load(requireContext());
        binding.toggles.removeAllViews();
        LayoutInflater inflater = getLayoutInflater();
        for (int i = 0; i < EffectConfig.LAYERS.length; i++) {
            EffectConfig.Layer layer = EffectConfig.LAYERS[i];
            MaterialSwitch sw = (MaterialSwitch) inflater.inflate(R.layout.view_effect_toggle, binding.toggles, false);
            sw.setText(layer.label);
            sw.setChecked(cfg.isEnabled(i));
            sw.setOnCheckedChangeListener((b, checked) -> {
                prefs.edit().putBoolean(layer.enabledKey(), checked).apply();
                LiveWallpaperService.requestReload(requireContext());
            });
            binding.toggles.addView(sw);
        }
        MaterialSwitch particles = (MaterialSwitch) inflater.inflate(R.layout.view_effect_toggle, binding.toggles, false);
        particles.setText(R.string.particles);
        particles.setChecked(cfg.particleCount > 0);
        particles.setOnCheckedChangeListener((b, checked) -> {
            prefs.edit().putInt(EffectConfig.KEY_PARTICLE_COUNT, checked ? EffectConfig.PARTICLE_DEF : 0).apply();
            LiveWallpaperService.requestReload(requireContext());
        });
        binding.toggles.addView(particles);
    }
}
