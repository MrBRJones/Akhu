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
 * a list of {kind: 2d|3d|motion|react|theme, path (file path or theme id), title, category}, and
 * posts each one publicly, one at a time, logging "PostAll".
 *
 * <p>With "mode"="builtins" it posts every bundled wallpaper and built-in theme instead, only
 * while the official account ({@link FirebaseHelper#OFFICIAL_UID}) is signed in. Each is copied
 * out of the app's assets first, since the uploads read files; ones already posted are noted in
 * files/postall/builtins_done.json and skipped, so a run that stops can be started again.
 *
 * <p>With "mode"="avatar" it sets the official account's profile picture to the app icon.
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
        String mode = getIntent().getStringExtra("mode");
        if ("list".equals(mode)) list();
        else if ("builtins".equals(mode)) builtins();
        else if ("avatar".equals(mode)) avatar();
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

    /** The app icon, drawn edge to edge as the launcher shows it, as the official account's picture. */
    private void avatar() {
        if (!FirebaseHelper.OFFICIAL_UID.equals(FirebaseHelper.currentUser(this).getUid())) {
            Log.w(TAG, "avatar: not the official account");
            status.setText("Sign in as the official account first.");
            return;
        }
        status.setText("Setting the picture…");
        new Thread(() -> {
            try {
                int size = 1024;
                android.graphics.drawable.Drawable icon = getDrawable(R.mipmap.ic_launcher);
                android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888);
                android.graphics.Canvas c = new android.graphics.Canvas(bmp);
                if (icon instanceof android.graphics.drawable.AdaptiveIconDrawable) {
                    // The launcher shows the middle two thirds of each 108dp layer.
                    android.graphics.drawable.AdaptiveIconDrawable a = (android.graphics.drawable.AdaptiveIconDrawable) icon;
                    int pad = size / 4;
                    for (android.graphics.drawable.Drawable layer : new android.graphics.drawable.Drawable[]{a.getBackground(), a.getForeground()}) {
                        if (layer == null) continue;
                        layer.setBounds(-pad, -pad, size + pad, size + pad);
                        layer.draw(c);
                    }
                } else {
                    icon.setBounds(0, 0, size, size);
                    icon.draw(c);
                }
                File f = new File(getCacheDir(), "official_avatar.png");
                try (FileOutputStream out = new FileOutputStream(f)) {
                    bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
                }
                bmp.recycle();
                com.google.android.gms.tasks.Task<Void> up = Social.INSTANCE.setAvatar(this, f);
                if (up == null) {
                    Log.w(TAG, "avatar: not uploaded");
                    return;
                }
                up.addOnCompleteListener(t -> {
                    Log.i(TAG, t.isSuccessful() ? "avatar set" : "avatar failed: " + t.getException());
                    runOnUiThread(() -> status.setText(t.isSuccessful() ? "Picture set" : "Picture failed"));
                });
            } catch (Exception e) {
                Log.w(TAG, "avatar", e);
            }
        }, "PostAllAvatar").start();
    }

    /** Keys of built-ins already posted (or null when posting from plan.json). */
    private JSONObject done;

    private void builtins() {
        if (!FirebaseHelper.OFFICIAL_UID.equals(FirebaseHelper.currentUser(this).getUid())) {
            Log.w(TAG, "builtins: not the official account");
            status.setText("Sign in as the official account first.");
            return;
        }
        status.setText("Getting the built-ins ready…");
        new Thread(() -> {
            try {
                done = new JSONObject(new File(dir, "builtins_done.json").exists() ? read("builtins_done.json") : "{}");
                plan = stageBuiltins();
                Log.i(TAG, "builtins: " + plan.length() + " to post, " + done.length() + " done before");
                runOnUiThread(this::postNext);
            } catch (Exception e) {
                Log.w(TAG, "builtins", e);
            }
        }, "PostAllStage").start();
    }

    /** Copies each bundled wallpaper out of the assets and plans its post, skipping ones done before. */
    private JSONArray stageBuiltins() throws Exception {
        File root = new File(getCacheDir(), "builtins");
        JSONArray out = new JSONArray();
        for (String asset : FeaturedWallpapers.list(this, null)) {
            // featured/<category>/N.jpg
            String category = asset.split("/")[1];
            File f = copyAsset(asset, new File(root, "2d/" + category + "_" + new File(asset).getName()));
            plan(out, "2d", asset, f.getAbsolutePath(), Names.of(asset), category);
        }
        for (String asset : FourDWallpapers.list(this)) {
            if (FourDWallpapers.isUser(asset)) continue;
            // With its layers and motion settings beside it, as a user-made one has.
            File dest = new File(root, "3d/" + new File(asset).getName());
            String base = asset.substring(0, asset.length() - 4), destBase = dest.getPath().substring(0, dest.getPath().length() - 4);
            copyAsset(asset, dest);
            for (String suffix : new String[]{"_l0.jpg", "_l1.webp", "_l2.webp", "_motion.txt", "_l3.webp", "_l3.txt"}) {
                copyAssetIfThere(base + suffix, new File(destBase + suffix));
            }
            plan(out, "3d", asset, dest.getAbsolutePath(), Names.of(asset), null);
        }
        for (String asset : MotionWallpapers.list(this)) {
            File f = copyAsset(asset, new File(root, "motion/" + new File(asset).getName()));
            plan(out, "motion", asset, f.getAbsolutePath(), Names.of(asset), null);
        }
        for (String asset : ReactWallpapers.bundled(this)) {
            File dest = new File(root, "react/" + new File(asset).getName());
            copyAsset(asset, dest);
            copyAssetIfThere(asset.substring(0, asset.length() - 4) + ".json",
                    new File(dest.getPath().substring(0, dest.getPath().length() - 4) + ".json"));
            plan(out, "react", asset, dest.getAbsolutePath(), Names.of(asset), null);
        }
        for (CustomTheme t : ThemeStore.builtins(this)) {
            plan(out, "builtin_theme", "theme:" + t.id, t.id, t.name, null);
        }
        return out;
    }

    private void plan(JSONArray out, String kind, String key, String path, String title, String category) throws Exception {
        if (done.has(key)) return;
        out.put(new JSONObject().put("kind", kind).put("key", key).put("path", path).put("title", title)
                .put("category", category == null ? JSONObject.NULL : category));
    }

    private File copyAsset(String asset, File dest) throws IOException {
        //noinspection ResultOfMethodCallIgnored
        dest.getParentFile().mkdirs();
        try (InputStream in = getAssets().open(asset); FileOutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[65536];
            for (int r; (r = in.read(buf)) > 0; ) out.write(buf, 0, r);
        }
        return dest;
    }

    private void copyAssetIfThere(String asset, File dest) {
        try {
            copyAsset(asset, dest);
        } catch (IOException ignored) {
            // Optional file (extra layer, motion settings).
        }
    }

    /** A built-in theme to post: its apps kept to everyday ones, so no one's other apps go public. */
    @androidx.annotation.Nullable
    private CustomTheme builtinTheme(String id) {
        for (CustomTheme t : ThemeStore.builtins(this)) {
            if (!t.id.equals(id)) continue;
            CustomTheme copy = t.copy();
            List<CustomTheme.App> everyday = new java.util.ArrayList<>();
            for (CustomTheme.App a : copy.apps) if (AppCatalog.isEveryday(a.pkg)) everyday.add(a);
            copy.apps.clear();
            copy.apps.addAll(everyday);
            return copy;
        }
        return null;
    }

    private void noteDone(String key) {
        if (done == null || key == null) return;
        try {
            done.put(key, true);
            write("builtins_done.json", done.toString());
        } catch (Exception e) {
            Log.w(TAG, "noting " + key, e);
        }
    }

    private void postNext() {
        if (next >= plan.length()) {
            Log.i(TAG, "done " + ok + "/" + plan.length());
            status.setText("Posted " + ok + " of " + plan.length());
            return;
        }
        JSONObject item = plan.optJSONObject(next++);
        String kind = item.optString("kind"), path = item.optString("path"), title = item.optString("title");
        String key = item.optString("key", null);
        String category = item.isNull("category") ? null : item.optString("category");
        status.setText("Posting " + next + " of " + plan.length() + ": " + title);
        String collection = kind.endsWith("theme") ? FirebaseHelper.THEMES : FirebaseHelper.COLLECTION;
        FirebaseHelper.UploadCallback cb = new FirebaseHelper.UploadCallback() {
            @Override
            public void onProgress(int percent) {
            }

            @Override
            public void onSuccess(String docId) {
                if (done == null) Posts.link(PostAllActivity.this, path, collection, docId);
                noteDone(key);
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
            case "react":
                FirebaseHelper.uploadReact(this, path, title, true, category, cb);
                break;
            case "builtin_theme":
                CustomTheme builtin = builtinTheme(path);
                if (builtin == null) cb.onError(new IOException("no built-in theme " + path));
                else FirebaseHelper.uploadTheme(this, builtin, ThemeStore.wallpaper(this, builtin), title, true, cb);
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
