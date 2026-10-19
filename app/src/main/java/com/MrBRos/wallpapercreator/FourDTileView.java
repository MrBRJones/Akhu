package com.MrBRos.wallpapercreator;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.util.LruCache;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Grid tile that renders a 4D wallpaper with live tilt, using an AGSL {@link RuntimeShader}
 * (Android 13+) that mirrors res/raw/fourd_fragment.glsl: three pre-cut layers sliding rigidly.
 * No GL context per tile: the shader runs inside the normal view render pass. All tiles read one
 * shared {@link TiltSensor}. On older Android the view stays empty and the static thumbnail
 * beneath shows instead.
 */
public class FourDTileView extends View {

    private static final String AGSL =
            "uniform shader far;\n"
            + "uniform shader mid;\n"
            + "uniform shader near;\n"
            + "uniform float2 viewSize;\n"
            + "uniform float2 layerSize;\n"
            + "uniform float2 scale;\n"
            + "uniform float2 center;\n"
            + "uniform float2 offset;\n"
            + "uniform float3 factors;\n"
            + "uniform shader extra;\n"
            + "uniform float2 extraOffset;\n"
            // AGSL can't pass shaders into functions, so only the coordinate math is shared.
            + "float2 px(float2 uv) { return clamp(uv, 0.0, 1.0) * layerSize; }\n"
            + "half4 main(float2 p) {\n"
            + "  float2 base = center + (p / viewSize - 0.5) * scale;\n"
            + "  half3 c = far.eval(px(base + offset * factors.x)).rgb;\n"
            + "  half4 m = mid.eval(px(base + offset * factors.y));\n"
            + "  c = m.rgb + c * (1.0 - m.a);\n"
            + "  half4 e = extra.eval(px(base + extraOffset));\n"
            + "  c = e.rgb + c * (1.0 - e.a);\n"
            + "  half4 n = near.eval(px(base + offset * factors.z));\n"
            + "  c = n.rgb + c * (1.0 - n.a);\n"
            + "  return half4(c, 1.0);\n"
            + "}\n";

    /** A touch stronger than full screen so the motion reads at tile size. */
    private static final float TILE_BOOST = 1.25f;
    /** Layers are 1080 px wide; a tile needs about a quarter of that. */
    private static final int TILE_SAMPLE_SIZE = 2;

    private static final LruCache<String, Bitmap[]> CACHE = new LruCache<>(12);
    private static final ExecutorService LOADER = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** Set by the screen hosting the grid while 4D tiles are visible; null pauses all tiles. */
    @Nullable
    private static TiltSensor sharedTilt;

    public static void setSharedTilt(@Nullable TiltSensor tilt) {
        sharedTilt = tilt;
    }

    public static boolean isSupported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU;
    }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Object shader; // RuntimeShader, typed as Object so the class loads on older Android
    private Bitmap[] layers;
    private String asset;
    private float lastX = Float.NaN, lastY = Float.NaN;
    @Nullable
    private float[] extraMotion;
    private final float[] extraOffset = new float[2];
    private float[] motion = FourDWallpapers.DEFAULT_MOTION;

    private final Runnable frame = new Runnable() {
        @Override
        public void run() {
            TiltSensor tilt = sharedTilt;
            if (tilt != null && layers != null && isShown()) {
                float x = tilt.x(), y = tilt.y();
                if (Math.abs(x - lastX) > 0.002f || Math.abs(y - lastY) > 0.002f) invalidate();
            }
            postOnAnimation(this);
        }
    };

    public FourDTileView(Context context) {
        this(context, null);
    }

    public FourDTileView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    /** Shows a bundled 4D wallpaper, e.g. "fourd/1.jpg"; decodes off the main thread. */
    public void setAsset(String asset) {
        if (!isSupported() || asset.equals(this.asset)) return;
        this.asset = asset;
        layers = null;
        shader = null;
        invalidate();
        Bitmap[] cached = CACHE.get(asset);
        if (cached != null) {
            bind(cached);
            return;
        }
        Context app = getContext().getApplicationContext();
        LOADER.execute(() -> {
            Bitmap[] loaded = FourDWallpapers.load(app, asset, TILE_SAMPLE_SIZE);
            if (loaded == null) return;
            CACHE.put(asset, loaded);
            MAIN.post(() -> {
                if (asset.equals(this.asset)) bind(loaded);
            });
        });
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private void bind(Bitmap[] loaded) {
        layers = loaded;
        RuntimeShader rs = new RuntimeShader(AGSL);
        String[] names = {"far", "mid", "near"};
        for (int i = 0; i < 3; i++) {
            rs.setInputShader(names[i], new BitmapShader(loaded[i], Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
        }
        // Images without an extra layer get a transparent one, so the shader stays the same.
        Bitmap extra = loaded[3] != null ? loaded[3] : Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
        rs.setInputShader("extra", new BitmapShader(extra, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
        rs.setFloatUniform("layerSize", loaded[0].getWidth(), loaded[0].getHeight());
        motion = FourDWallpapers.motion(getContext(), asset);
        rs.setFloatUniform("factors", FourDRenderer.FACTOR_FAR, motion[1], FourDRenderer.FACTOR_NEAR);
        extraMotion = loaded[3] != null ? FourDWallpapers.extraMotion(getContext(), asset) : null;
        shader = rs;
        paint.setShader(rs);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (!isSupported() || shader == null || layers == null || getWidth() == 0) return;
        drawShader(canvas);
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private void drawShader(Canvas canvas) {
        RuntimeShader rs = (RuntimeShader) shader;
        float w = getWidth(), h = getHeight();
        // Cover-crop to the tile's aspect ratio, then overscan so edges never show.
        float viewAspect = w / h;
        float imageAspect = (float) layers[0].getWidth() / layers[0].getHeight();
        float sx = 1f, sy = 1f;
        if (imageAspect > viewAspect) sx = viewAspect / imageAspect;
        else sy = imageAspect / viewAspect;
        TiltSensor tilt = sharedTilt;
        lastX = tilt == null ? 0f : tilt.x();
        lastY = tilt == null ? 0f : tilt.y();
        float strength = FourDRenderer.STRENGTH * TILE_BOOST * motion[0];
        rs.setFloatUniform("viewSize", w, h);
        rs.setFloatUniform("scale", sx * FourDRenderer.OVERSCAN, sy * FourDRenderer.OVERSCAN);
        float[] focal = Focal.get(getContext(), asset);
        float[] center = Focal.windowCenter(focal[0], focal[1], sx * FourDRenderer.OVERSCAN, sy * FourDRenderer.OVERSCAN);
        rs.setFloatUniform("center", center[0], center[1]);
        float ox = -lastX * strength * sx, oy = lastY * strength * sy;
        rs.setFloatUniform("offset", ox, oy);
        if (extraMotion != null) {
            FourDWallpapers.extraOffset(extraMotion, ox, oy, layers[0].getWidth(), layers[0].getHeight(), extraOffset);
            rs.setFloatUniform("extraOffset", extraOffset[0], extraOffset[1]);
        } else {
            rs.setFloatUniform("extraOffset", 0f, 0f);
        }
        canvas.drawRect(0, 0, w, h, paint);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (isSupported()) postOnAnimation(frame);
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(frame);
        super.onDetachedFromWindow();
    }
}
