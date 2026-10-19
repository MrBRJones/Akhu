package com.MrBRos.wallpapercreator;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.MrBRos.wallpapercreator.databinding.FragmentThemesBinding;

import java.util.ArrayList;
import java.util.List;

/** Themes tab: "Theme Creation" banner plus a grid of saved themes, each a live home screen mock. */
public class ThemesFragment extends Fragment {

    private FragmentThemesBinding binding;
    private final List<CustomTheme> themes = new ArrayList<>();
    private final Adapter adapter = new Adapter();

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        binding = FragmentThemesBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        binding.themeGrid.setLayoutManager(new GridLayoutManager(requireContext(), 2));
        binding.themeGrid.setAdapter(adapter);
        binding.btnCreateTheme.setOnClickListener(v -> createTheme(requireContext()));
    }

    /** Opens the editor on a new theme. */
    public static void createTheme(Context context) {
        context.startActivity(new Intent(context, ThemeEditorActivity.class));
    }

    @Override
    public void onResume() {
        super.onResume();
        reload();
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (!hidden && binding != null) reload();
    }

    private void reload() {
        Context app = requireContext().getApplicationContext();
        new Thread(() -> {
            List<CustomTheme> list = ThemeStore.list(app);
            if (getActivity() == null) return;
            getActivity().runOnUiThread(() -> {
                if (binding == null) return;
                themes.clear();
                themes.addAll(list);
                adapter.notifyDataSetChanged();
                binding.emptyNote.setVisibility(list.isEmpty() ? View.VISIBLE : View.GONE);
            });
        }, "ThemesLoad").start();
    }

    @Override
    public void onDestroyView() {
        binding = null;
        super.onDestroyView();
    }

    private class Adapter extends RecyclerView.Adapter<Adapter.Holder> {

        class Holder extends RecyclerView.ViewHolder {
            final HomeMockView mock;
            final TextView title;

            Holder(View v) {
                super(v);
                mock = v.findViewById(R.id.mock);
                title = v.findViewById(R.id.title);
                mock.setWallpaperMax(640);
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
            h.itemView.setOnClickListener(v -> startActivity(new Intent(requireContext(), ThemePreviewActivity.class)
                    .putExtra(ThemePreviewActivity.EXTRA_ID, t.id)));
        }

        @Override
        public int getItemCount() {
            return themes.size();
        }
    }
}
