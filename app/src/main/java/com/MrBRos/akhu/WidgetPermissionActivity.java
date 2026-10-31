package com.MrBRos.akhu;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;

import androidx.annotation.NonNull;

/**
 * Invisible screen a widget opens to ask for the permission it needs (calendar for the next
 * event, rough location for the weather), since widgets can't ask themselves. Redraws the widgets
 * with the answer and closes.
 */
public class WidgetPermissionActivity extends Activity {

    private static final String EXTRA_PERMISSION = "permission";

    static Intent intent(Context context, String permission) {
        return new Intent(context, WidgetPermissionActivity.class).putExtra(EXTRA_PERMISSION, permission);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String p = getIntent().getStringExtra(EXTRA_PERMISSION);
        // Only the two permissions the widgets use.
        if (!Manifest.permission.READ_CALENDAR.equals(p) && !Manifest.permission.ACCESS_COARSE_LOCATION.equals(p)) {
            finish();
            return;
        }
        if (checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED) {
            done();
        } else if (savedInstanceState == null) {
            requestPermissions(new String[]{p}, 1);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        done();
    }

    private void done() {
        ThemedWidgetProvider.updateAll(this);
        new WeatherWidgetProvider().fetchAsync(this, true);
        finish();
    }
}
