package com.MrBRos.akhu;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;

import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentSnapshot;

import java.util.ArrayList;
import java.util.List;

/**
 * Debug-only tool: sets the shown name on all the signed-in user's wallpapers to the name new
 * posts use now (so old posts stop showing an account-derived name). Logs "FixNames".
 */
public class FixNamesActivity extends Activity {

    private static final String TAG = "FixNames";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView status = new TextView(this);
        status.setPadding(48, 160, 48, 48);
        setContentView(status);
        FirebaseUser user = FirebaseHelper.currentUser(this);
        if (user == null) {
            Log.w(TAG, "not signed in");
            return;
        }
        String name = FirebaseHelper.postedName(this, user);
        status.setText("Renaming to " + name + "…");
        FirebaseHelper.mineQuery(user.getUid()).get().addOnCompleteListener(t -> {
            if (!t.isSuccessful()) {
                Log.w(TAG, "list failed", t.getException());
                return;
            }
            List<Task<Void>> updates = new ArrayList<>();
            for (DocumentSnapshot d : t.getResult().getDocuments()) {
                if (!name.equals(d.getString("displayName"))) updates.add(d.getReference().update("displayName", name));
            }
            Tasks.whenAllComplete(updates).addOnCompleteListener(all -> {
                int ok = 0;
                for (Task<Void> u : updates) if (u.isSuccessful()) ok++;
                String done = "done " + ok + "/" + updates.size();
                Log.i(TAG, done);
                status.setText(done);
            });
        });
    }
}
