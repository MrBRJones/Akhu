package com.MrBRos.akhu;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;

import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Debug-only tool that posts the signed-in user's own wallpapers through the same upload code
 * as the app's Post buttons. With extra "mode"="list" it writes the user's existing posts to
 * files/postall/mine.json (external app storage); otherwise it reads files/postall/plan.json,
 * a list of {kind: 2d|3d|motion|theme, path (file path or theme id), title, category}, and
 * posts each one publicly, one at a time, logging "PostAll".
 */
public class PostAllActivity extends Activity {

    private static final String TAG = "PostAll";
    private TextView status;
    private File dir;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        status = new TextView(this);
        status.setPadding(48, 160, 48, 48);
        setContentView(status);
        dir = getExternalFilesDir("postall");
        if (FirebaseHelper.currentUser(this) == null) {
            Log.w(TAG, "not signed in");
            return;
        }
        if ("list".equals(getIntent().getStringExtra("mode"))) list();
        else post();
    }

    private void list() {
        status.setText("Listing…");
        FirebaseHelper.mineQuery(FirebaseHelper.currentUser(this).getUid()).get().addOnCompleteListener(t -> {
            JSONArray out = new JSONArray();
            try {
                if (t.isSuccessful()) {
                    for (DocumentSnapshot d : t.getResult().getDocuments()) add(out, d, FirebaseHelper.COLLECTION);
                }
                FirebaseFirestore.getInstance().collection(FirebaseHelper.THEMES)
                        .whereEqualTo("uid", FirebaseHelper.currentUser(this).getUid()).get().addOnCompleteListener(t2 -> {
                            try {
                                if (t2.isSuccessful()) {
                                    for (DocumentSnapshot d : t2.getResult().getDocuments()) add(out, d, FirebaseHelper.THEMES);
                                }
                                write("mine.json", out.toString(1));
                                Log.i(TAG, "listed " + out.length());
                                status.setText("Listed " + out.length());
                            } catch (Exception e) {
                                Log.w(TAG, "list", e);
                            }
                        });
            } catch (Exception e) {
                Log.w(TAG, "list", e);
            }
        });
    }

    private static void add(JSONArray out, DocumentSnapshot d, String collection) throws Exception {
        out.put(new JSONObject().put("collection", collection).put("id", d.getId())
                .put("title", d.getString("title")).put("mediaType", d.getString("mediaType"))
                .put("visibility", d.getString("visibility")).put("thumbnailUrl", d.getString("thumbnailUrl")));
    }

    private JSONArray plan;
    private int next;
    private int ok;

    private void post() {
        try {
            plan = new JSONArray(read("plan.json"));
        } catch (Exception e) {
            Log.w(TAG, "no plan", e);
            return;
        }
        postNext();
    }

    private void postNext() {
        if (next >= plan.length()) {
            Log.i(TAG, "done " + ok + "/" + plan.length());
            status.setText("Posted " + ok + " of " + plan.length());
            return;
        }
        JSONObject item = plan.optJSONObject(next++);
        String kind = item.optString("kind"), path = item.optString("path"), title = item.optString("title");
        String category = item.isNull("category") ? null : item.optString("category");
        status.setText("Posting " + next + " of " + plan.length() + ": " + title);
        String collection = "theme".equals(kind) ? FirebaseHelper.THEMES : FirebaseHelper.COLLECTION;
        FirebaseHelper.UploadCallback cb = new FirebaseHelper.UploadCallback() {
            @Override
            public void onProgress(int percent) {
            }

            @Override
            public void onSuccess(String docId) {
                Posts.link(PostAllActivity.this, path, collection, docId);
                ok++;
                Log.i(TAG, "posted " + kind + " \"" + title + "\" " + docId);
                postNext();
            }

            @Override
            public void onError(Exception e) {
                Log.w(TAG, "failed " + kind + " \"" + title + "\": " + e.getMessage());
                postNext();
            }
        };
        switch (kind) {
            case "2d":
                FirebaseHelper.uploadWallpaper(this, new File(path), title, true, category, cb);
                break;
            case "3d":
                FirebaseHelper.uploadFourD(this, path, title, true, category, cb);
                break;
            case "motion":
                // The upload deletes the file it sends, so it sends a copy.
                try {
                    File copy = new File(getCacheDir(), "postall_" + next + ".mp4");
                    java.nio.file.Files.copy(new File(path).toPath(), copy.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    FirebaseHelper.uploadVideo(this, copy, title, true, category, cb);
                } catch (IOException e) {
                    cb.onError(e);
                }
                break;
            case "theme":
                CustomTheme theme = null;
                List<CustomTheme> mine = ThemeStore.list(this);
                for (CustomTheme t : mine) if (t.id.equals(path)) theme = t;
                if (theme == null) {
                    cb.onError(new IOException("no theme " + path));
                    break;
                }
                FirebaseHelper.uploadTheme(this, theme, ThemeStore.wallpaper(this, theme), title, true, cb);
                break;
            default:
                cb.onError(new IOException("unknown kind " + kind));
        }
    }

    private String read(String name) throws IOException {
        try (InputStream in = new FileInputStream(new File(dir, name))) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int r; (r = in.read(buf)) > 0; ) out.write(buf, 0, r);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private void write(String name, String text) throws IOException {
        try (FileOutputStream out = new FileOutputStream(new File(dir, name))) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }
}
