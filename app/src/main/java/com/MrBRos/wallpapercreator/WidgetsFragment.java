package com.MrBRos.wallpapercreator;

import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.pm.ShortcutManagerCompat;
import androidx.fragment.app.Fragment;

import com.MrBRos.wallpapercreator.databinding.FragmentWidgetsBinding;
import com.bumptech.glide.Glide;

import java.io.File;

/** Widgets tab: preview of the quick-set widget, pin widget / shortcut, set latest now. */
public class WidgetsFragment extends Fragment {

    private FragmentWidgetsBinding binding;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        binding = FragmentWidgetsBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        binding.btnPinWidget.setOnClickListener(v -> pinWidget());
        binding.btnPinShortcut.setOnClickListener(v -> pinShortcut());
        binding.btnSetLatest.setOnClickListener(v -> setLatest());
    }

    @Override
    public void onResume() {
        super.onResume();
        String latest = ImageExporter.getLatestPath(requireContext());
        boolean has = latest != null;
        if (has) {
            Glide.with(this).load(new File(latest)).centerCrop().into(binding.widgetPreview);
        } else {
            binding.widgetPreview.setImageResource(R.drawable.ic_wallpaper);
        }
        binding.noSavedNote.setVisibility(has ? View.GONE : View.VISIBLE);
        binding.btnPinShortcut.setEnabled(has);
        binding.btnSetLatest.setEnabled(has);
    }

    @Override
    public void onDestroyView() {
        binding = null;
        super.onDestroyView();
    }

    private void pinWidget() {
        AppWidgetManager mgr = AppWidgetManager.getInstance(requireContext());
        if (!mgr.isRequestPinAppWidgetSupported()) {
            Toast.makeText(requireContext(), R.string.pin_unsupported, Toast.LENGTH_LONG).show();
            return;
        }
        mgr.requestPinAppWidget(new ComponentName(requireContext(), WallpaperWidgetProvider.class), null, null);
    }

    private void pinShortcut() {
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(requireContext())) {
            Toast.makeText(requireContext(), R.string.pin_unsupported, Toast.LENGTH_LONG).show();
            return;
        }
        ShortcutManagerCompat.requestPinShortcut(requireContext(), ShortcutHelper.build(requireContext()), null);
    }

    private void setLatest() {
        android.content.Context app = requireContext().getApplicationContext();
        binding.btnSetLatest.setEnabled(false);
        new Thread(() -> {
            int msg;
            try {
                msg = WidgetSetWallpaperReceiver.setLatest(app) ? R.string.wallpaper_set : R.string.no_saved_wallpapers;
            } catch (Exception e) {
                msg = R.string.wallpaper_set_failed;
            }
            int finalMsg = msg;
            if (getActivity() == null) return;
            getActivity().runOnUiThread(() -> {
                Toast.makeText(app, finalMsg, Toast.LENGTH_SHORT).show();
                if (binding != null) binding.btnSetLatest.setEnabled(true);
            });
        }, "SetLatest").start();
    }
}
