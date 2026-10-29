package com.MrBRos.akhu;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.MrBRos.akhu.databinding.FragmentLiveBinding;

/** Motion tab: a grid of motion videos and 4D motion images. */
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
        updateGridTilt();
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        binding = FragmentLiveBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        bindMotionGrid();
    }

    /**
     * "Videos" and "4D motion" tabs share one grid; tapping a tile opens the swipeable
     * full-screen preview over that tab's items.
     */
    private void bindMotionGrid() {
        java.util.List<String> videos = MotionWallpapers.list(requireContext());
        // Only the bundled 3D images; the user's own are in Profile -> 3D.
        for (String f : FourDWallpapers.list(requireContext())) if (!FourDWallpapers.isUser(f)) fourD.add(f);
        WallpaperCardAdapter adapter = new WallpaperCardAdapter(card -> {
            boolean isFourD = card.isFourD();
            java.util.List<String> items = isFourD ? fourD : videos;
            String prefix = isFourD ? PreviewActivity.FOURD_PREFIX : PreviewActivity.VIDEO_PREFIX;
            java.util.ArrayList<String> sources = new java.util.ArrayList<>();
            for (String s : items) sources.add(prefix + s);
            startActivity(PreviewActivity.intent(requireContext(), sources, items.indexOf(card.assetPath)));
        });
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

    @Override
    public void onResume() {
        super.onResume();
        updateGridTilt();
    }

    @Override
    public void onDestroyView() {
        binding = null;
        super.onDestroyView();
    }
}
