package com.MrBRos.wallpapercreator;

import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.MrBRos.wallpapercreator.databinding.ActivityAuthBinding;
import com.google.android.gms.tasks.Task;
import com.google.firebase.auth.AuthResult;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.auth.UserProfileChangeRequest;

/** Email/password sign-in and sign-up, driven by an auth state listener. */
public class AuthActivity extends AppCompatActivity {

    private ActivityAuthBinding binding;
    private FirebaseAuth auth;
    private final FirebaseAuth.AuthStateListener listener = a -> updateUi(a.getCurrentUser());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityAuthBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        if (!FirebaseHelper.isAvailable(this)) {
            Toast.makeText(this, R.string.cloud_disabled, Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        auth = FirebaseAuth.getInstance();
        binding.btnSignIn.setOnClickListener(v -> signIn());
        binding.btnSignUp.setOnClickListener(v -> signUp());
        binding.btnSignOut.setOnClickListener(v -> auth.signOut());
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (auth != null) auth.addAuthStateListener(listener);
    }

    @Override
    protected void onStop() {
        if (auth != null) auth.removeAuthStateListener(listener);
        super.onStop();
    }

    private void updateUi(FirebaseUser user) {
        boolean signedIn = user != null;
        binding.signedOutGroup.setVisibility(signedIn ? View.GONE : View.VISIBLE);
        binding.signedInGroup.setVisibility(signedIn ? View.VISIBLE : View.GONE);
        if (signedIn) {
            binding.signedInText.setText(getString(R.string.signed_in_as,
                    FirebaseHelper.displayNameOf(user), user.getEmail()));
        }
    }

    private String email() {
        return binding.inputEmail.getText() == null ? "" : binding.inputEmail.getText().toString().trim();
    }

    private String password() {
        return binding.inputPassword.getText() == null ? "" : binding.inputPassword.getText().toString();
    }

    private boolean validate() {
        if (TextUtils.isEmpty(email()) || password().length() < 6) {
            Toast.makeText(this, R.string.auth_invalid, Toast.LENGTH_SHORT).show();
            return false;
        }
        return true;
    }

    private void signIn() {
        if (!validate()) return;
        setBusy(true);
        handle(auth.signInWithEmailAndPassword(email(), password()), null);
    }

    private void signUp() {
        if (!validate()) return;
        setBusy(true);
        String name = binding.inputDisplayName.getText() == null ? ""
                : binding.inputDisplayName.getText().toString().trim();
        handle(auth.createUserWithEmailAndPassword(email(), password()), name);
    }

    private void handle(Task<AuthResult> task, String displayName) {
        task.addOnCompleteListener(this, t -> {
            if (!t.isSuccessful()) {
                setBusy(false);
                String msg = t.getException() != null ? t.getException().getMessage() : getString(R.string.auth_failed);
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
                return;
            }
            FirebaseUser user = t.getResult().getUser();
            if (user != null && !TextUtils.isEmpty(displayName)) {
                user.updateProfile(new UserProfileChangeRequest.Builder().setDisplayName(displayName).build())
                        .addOnCompleteListener(this, p -> finishSignedIn());
            } else {
                finishSignedIn();
            }
        });
    }

    private void finishSignedIn() {
        setBusy(false);
        Toast.makeText(this, R.string.auth_success, Toast.LENGTH_SHORT).show();
        finish();
    }

    private void setBusy(boolean busy) {
        binding.progress.setVisibility(busy ? View.VISIBLE : View.GONE);
        binding.btnSignIn.setEnabled(!busy);
        binding.btnSignUp.setEnabled(!busy);
    }
}
