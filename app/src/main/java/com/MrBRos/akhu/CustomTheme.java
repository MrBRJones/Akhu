package com.MrBRos.akhu;

import android.content.ComponentName;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * A user-made home screen theme: a wallpaper plus a set of apps whose icons are restyled. Stored
 * by {@link ThemeStore} as theme.json beside wallpaper.jpg and per-app icon photos.
 */
public class CustomTheme {

    /** Icon outline. */
    public static final int SHAPE_CIRCLE = 0, SHAPE_SQUIRCLE = 1, SHAPE_ROUNDED = 2, SHAPE_TEARDROP = 3;
    public static final int SHAPE_SQUARE = 4, SHAPE_HEXAGON = 5, SHAPE_LEAF = 6, SHAPE_FLOWER = 7,
            SHAPE_COOKIE = 8, SHAPE_SHIELD = 9;
    /** Themed (shown as "Solid"): app glyph on a colour. Glass: glyph on frosted see-through. Original: the app's own icon. */
    public static final int STYLE_THEMED = 0, STYLE_GLASS = 1, STYLE_ORIGINAL = 2;
    /**
     * Gradient: colour fading light to dark. Outline: coloured ring, no fill. Neon: dark tile with a
     * glowing rim and glyph. Pastel: softened colour. Minimal: the glyph alone. Raised: a 3D button
     * with a drop shadow. Sticker: colour inside a white border.
     */
    public static final int STYLE_GRADIENT = 3, STYLE_OUTLINE = 4, STYLE_NEON = 5, STYLE_PASTEL = 6,
            STYLE_MINIMAL = 7, STYLE_RAISED = 8, STYLE_STICKER = 9;

    public String id;
    public String name;
    public int shape = SHAPE_SQUIRCLE;
    public int style = STYLE_THEMED;
    public int bgColor = 0xFF2F6BFF;
    public int glyphColor = 0xFFFFFFFF;
    /** Clock widget placed with the theme: none, time with the date, time alone, or the day and date. */
    public static final int WIDGET_NONE = 0, WIDGET_CLOCK = 1, WIDGET_TIME = 2, WIDGET_DATE = 3;
    public int widget = WIDGET_NONE;
    public int widgetColor = 0xFFFFFFFF;
    /** Bold rather than light lettering. */
    public boolean widgetBold;
    /** The widget's card, styled like the icons: one of the STYLE_ values (Minimal = no card). */
    public int widgetStyle = STYLE_MINIMAL;
    public static final int WIDGET_SHAPE_ROUNDED = 0, WIDGET_SHAPE_PILL = 1, WIDGET_SHAPE_SQUARE = 2;
    public int widgetShape = WIDGET_SHAPE_ROUNDED;
    public int widgetBg = 0xFF2F6BFF;
    /** A picture behind the clock (widget_&lt;n&gt;.jpg), or null. */
    @Nullable
    public String widgetPhoto;
    /** Text for the Note widget. */
    public String widgetNote = "";
    /** The Countdown widget's event and its day (local midnight, ms; 0 = not set). */
    public String countdownTitle = "";
    public long countdownDate;
    public long updated;
    public final List<App> apps = new ArrayList<>();

    /** One restyled app. {@code photo} means the user picked an image for this icon (icon_&lt;n&gt;.png). */
    public static class App {
        public String pkg;
        public String activity;
        public String label;
        @Nullable
        public String photo;

        public App(String pkg, String activity, String label) {
            this.pkg = pkg;
            this.activity = activity;
            this.label = label;
        }

        public ComponentName component() {
            return new ComponentName(pkg, activity);
        }
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("name", name);
        o.put("shape", shape);
        o.put("style", style);
        o.put("bg", bgColor);
        o.put("glyph", glyphColor);
        o.put("updated", updated);
        o.put("widget", widget);
        o.put("widgetColor", widgetColor);
        o.put("widgetBold", widgetBold);
        o.put("widgetStyle", widgetStyle);
        o.put("widgetShape", widgetShape);
        o.put("widgetBg", widgetBg);
        if (widgetPhoto != null) o.put("widgetPhoto", widgetPhoto);
        o.put("widgetNote", widgetNote);
        o.put("countdownTitle", countdownTitle);
        o.put("countdownDate", countdownDate);
        JSONArray arr = new JSONArray();
        for (App a : apps) {
            JSONObject j = new JSONObject();
            j.put("pkg", a.pkg);
            j.put("activity", a.activity);
            j.put("label", a.label);
            if (a.photo != null) j.put("photo", a.photo);
            arr.put(j);
        }
        o.put("apps", arr);
        return o;
    }

    public static CustomTheme fromJson(JSONObject o) throws JSONException {
        CustomTheme t = new CustomTheme();
        t.id = o.getString("id");
        t.name = o.optString("name", "");
        t.shape = o.optInt("shape", SHAPE_SQUIRCLE);
        t.style = o.optInt("style", STYLE_THEMED);
        t.bgColor = o.optInt("bg", t.bgColor);
        t.glyphColor = o.optInt("glyph", t.glyphColor);
        t.updated = o.optLong("updated");
        t.widget = o.optInt("widget", WIDGET_NONE);
        t.widgetColor = o.optInt("widgetColor", t.widgetColor);
        t.widgetBold = o.optBoolean("widgetBold");
        t.widgetStyle = o.optInt("widgetStyle", STYLE_MINIMAL);
        t.widgetShape = o.optInt("widgetShape", WIDGET_SHAPE_ROUNDED);
        t.widgetBg = o.optInt("widgetBg", t.widgetBg);
        t.widgetPhoto = o.has("widgetPhoto") ? o.getString("widgetPhoto") : null;
        t.widgetNote = o.optString("widgetNote", "");
        t.countdownTitle = o.optString("countdownTitle", "");
        t.countdownDate = o.optLong("countdownDate");
        JSONArray arr = o.optJSONArray("apps");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject j = arr.getJSONObject(i);
                App a = new App(j.getString("pkg"), j.getString("activity"), j.optString("label", ""));
                a.photo = j.has("photo") ? j.getString("photo") : null;
                t.apps.add(a);
            }
        }
        return t;
    }

    public CustomTheme copy() {
        try {
            return fromJson(toJson());
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }
}
