package com.MrBRos.akhu;

import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.MrBRos.akhu.databinding.ActivityAuthBinding;
import com.google.android.gms.tasks.Task;
import com.google.firebase.auth.AuthResult;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

/**
 * Google or email/password sign-in and sign-up, driven by an auth state listener. Opened with
 * {@link #EXTRA_ADD} it signs in another account while one is signed in (the account switcher's
 * "Add account"); the one signed in before stays switchable, see {@link Accounts}.
 */
public class AuthActivity extends AppCompatActivity {

    /** Sign in to another account even though one is signed in. */
    public static final String EXTRA_ADD = "add";
    /** Email to fill in, for an account whose kept sign-in lapsed. */
    public static final String EXTRA_EMAIL = "email";

    private boolean adding;
    /** The pending sign-in is a new account, so it is sent a verification email. */
    private boolean verifyOnSignUp;

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
        adding = getIntent().getBooleanExtra(EXTRA_ADD, false);
        String email = getIntent().getStringExtra(EXTRA_EMAIL);
        if (email != null) binding.inputEmail.setText(email);
        if (adding) {
            // Keeps the account signed in now, so it can be switched back to.
            Social.claim(this);
            Accounts.rememberCurrent(this);
        }
        binding.btnSignIn.setOnClickListener(v -> signIn());
        binding.btnSignUp.setOnClickListener(v -> signUp());
        binding.googleGroup.setVisibility(GoogleAuth.isAvailable(this) ? View.VISIBLE : View.GONE);
        binding.btnGoogle.setOnClickListener(v -> signInWithGoogle());
        binding.btnSignOut.setOnClickListener(v -> {
            // Signing out takes the account off this phone's switcher too.
            FirebaseUser user = auth.getCurrentUser();
            if (user != null) Accounts.forget(this, user.getUid());
            auth.signOut();
            Accounts.noteActive(this, "");
        });
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
        boolean signedIn = user != null && !adding;
        binding.signedOutGroup.setVisibility(signedIn ? View.GONE : View.VISIBLE);
        binding.signedInGroup.setVisibility(signedIn ? View.VISIBLE : View.GONE);
        binding.btnSignUp.setVisibility(signedIn ? View.GONE : View.VISIBLE);
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
        verifyOnSignUp = false;
        setBusy(true);
        handle(auth.signInWithEmailAndPassword(email(), password()));
    }

    /** A Google account signs in, or is made an Akhu account the first time. */
    private void signInWithGoogle() {
        verifyOnSignUp = false;
        setBusy(true);
        GoogleAuth.request(this, credential -> {
            handle(auth.signInWithCredential(credential));
            return kotlin.Unit.INSTANCE;
        }, message -> {
            setBusy(false);
            if (message != null) Toast.makeText(this, message, Toast.LENGTH_LONG).show();
            return kotlin.Unit.INSTANCE;
        });
    }

    private void signUp() {
        if (!validate()) return;
        setBusy(true);
        verifyOnSignUp = true;
        handle(auth.createUserWithEmailAndPassword(email(), password()));
    }

    private void handle(Task<AuthResult> task) {
        task.addOnCompleteListener(this, t -> {
            if (!t.isSuccessful()) {
                setBusy(false);
                String msg = t.getException() != null ? t.getException().getMessage() : getString(R.string.auth_failed);
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
                return;
            }
            // A new account is sent its verification link; it can browse until it's tapped.
            if (verifyOnSignUp) Verify.send(this);
            finishSignedIn();
        });
    }

    private void finishSignedIn() {
        setBusy(false);
        Social.claim(this);
        Accounts.rememberCurrent(this);
        Toast.makeText(this, R.string.auth_success, Toast.LENGTH_SHORT).show();
        setResult(RESULT_OK);
        finish();
    }

    private void setBusy(boolean busy) {
        binding.progress.setVisibility(busy ? View.VISIBLE : View.GONE);
        binding.btnSignIn.setEnabled(!busy);
        binding.btnSignUp.setEnabled(!busy);
        binding.btnGoogle.setEnabled(!busy);
    }
}
