package com.MrBRos.wallpapercreator;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.fragment.app.Fragment;

import com.MrBRos.wallpapercreator.databinding.FragmentProfileBinding;
import com.google.firebase.auth.FirebaseUser;

/** Profile tab: who is signed in, a shortcut to their uploads, and System / Light / Dark. */
public class ProfileFragment extends Fragment {

    private FragmentProfileBinding binding;

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

        int mode = App.nightMode(requireContext());
        binding.appearance.check(mode == AppCompatDelegate.MODE_NIGHT_NO ? R.id.appearanceLight
                : mode == AppCompatDelegate.MODE_NIGHT_YES ? R.id.appearanceDark : R.id.appearanceSystem);
        binding.appearance.addOnButtonCheckedListener((group, id, checked) -> {
            if (!checked) return;
            App.setNightMode(requireContext(), id == R.id.appearanceLight ? AppCompatDelegate.MODE_NIGHT_NO
                    : id == R.id.appearanceDark ? AppCompatDelegate.MODE_NIGHT_YES
                    : AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        bindUser();
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (!hidden) bindUser();
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
}
