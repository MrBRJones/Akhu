package com.MrBRos.akhu;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.service.wallpaper.WallpaperService;

import androidx.core.content.ContextCompat;

import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/** Live wallpaper entry point. Creates GL engines and relays settings reloads to them. */
public class LiveWallpaperService extends WallpaperService {

    public static final String ACTION_RELOAD = "com.MrBRos.akhu.RELOAD_LIVE_WALLPAPER";

    private final Set<LiveWallpaperEngine> engines = new CopyOnWriteArraySet<>();

    private final BroadcastReceiver reloadReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            for (LiveWallpaperEngine engine : engines) {
                engine.reload();
            }
        }
    };

    /** Sends a package-scoped reload broadcast to any running engine. */
    public static void requestReload(Context context) {
        context.sendBroadcast(new Intent(ACTION_RELOAD).setPackage(context.getPackageName()));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        ContextCompat.registerReceiver(this, reloadReceiver, new IntentFilter(ACTION_RELOAD),
                ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    @Override
    public void onDestroy() {
        unregisterReceiver(reloadReceiver);
        super.onDestroy();
    }

    @Override
    public Engine onCreateEngine() {
        LiveWallpaperEngine engine = new LiveWallpaperEngine(this);
        engines.add(engine);
        return engine;
    }

    void onEngineDestroyed(LiveWallpaperEngine engine) {
        engines.remove(engine);
    }
}
