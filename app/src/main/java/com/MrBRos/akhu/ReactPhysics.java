package com.MrBRos.akhu;

import androidx.annotation.Nullable;

/**
 * The floating characters' motion (up to {@link #MAX_BODIES}), in screen pixels (y down) and
 * seconds. Tilt pushes them downhill through water drag, a slow current keeps them drifting when
 * the phone is still, and a held finger pulls in the one it grabbed. They bounce softly off the
 * edges and off each other (squashing a little and wobbling back) and lean into their sideways
 * motion like a boat turning, never past about 30 degrees. Each turns around its pivot (the
 * centre of the float), not the image centre.
 *
 * <p>Collisions use each character's outline (the convex hull of its visible pixels), so the
 * visible edge is what touches the wall, not the picture's transparent corners. One too big to
 * move along an axis is held centred on it instead of jamming against both edges.</p>
 *
 * <p>Not thread-safe: the render thread owns it, other threads only post touches.</p>
 */
public final class ReactPhysics {

    /** Ripples kept at once; must match the shader's array size. */
    public static final int MAX_RIPPLES = 12;
    /** Characters at most. */
    public static final int MAX_BODIES = 5;
    /** How long a ripple lasts, in seconds. */
    static final float RIPPLE_LIFE = 3f;

    private static final float MAX_LEAN = (float) Math.toRadians(30);
    private static final float MAX_SQUASH = 0.05f;
    private static final float RESTITUTION = 0.45f;
    /** The unit square's corners: the outline when a character has no hull. */
    private static final float[] BOX = {0, 0, 1, 0, 1, 1, 0, 1};

    /** One floating character. */
    static final class Body {
        float x, y, vx, vy;
        /** Lean, radians (positive = clockwise on screen). */
        float angle;
        float angleVel;
        /** Squash amount (0..MAX_SQUASH) and its axis (unit vector of the contact normal). */
        float squash;
        float squashVel;
        float squashNx = 1, squashNy;
        /** Drawn size in pixels and the pivot as a fraction of it. */
        float w = 1, h = 1, pivotX = 0.5f, pivotY = 0.5f;
        /** Outline points as fractions of the image (x, y pairs). */
        float[] hull = BOX;
        float wakeTimer;
        boolean placed;
        /** Scratch for {@link #extents}. */
        final float[] ext = new float[4];

        /**
         * Offsets of the turned outline's left, right, top and bottom from the pivot. The squash is
         * left out on purpose: it is a brief wobble, and following it would shake the position.
         */
        float[] extents() {
            float cos = (float) Math.cos(angle), sin = (float) Math.sin(angle);
            float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
            for (int i = 0; i + 1 < hull.length; i += 2) {
                float cx = (hull[i] - pivotX) * w, cy = (hull[i + 1] - pivotY) * h;
                float rx = cx * cos - cy * sin, ry = cx * sin + cy * cos;
                minX = Math.min(minX, rx);
                maxX = Math.max(maxX, rx);
                minY = Math.min(minY, ry);
                maxY = Math.max(maxY, ry);
            }
            ext[0] = minX;
            ext[1] = maxX;
            ext[2] = minY;
            ext[3] = maxY;
            return ext;
        }

        /** How far the turned outline reaches from the pivot toward the unit direction (dx, dy). */
        float reach(float dx, float dy) {
            float cos = (float) Math.cos(angle), sin = (float) Math.sin(angle);
            float best = 0;
            for (int i = 0; i + 1 < hull.length; i += 2) {
                float cx = (hull[i] - pivotX) * w, cy = (hull[i + 1] - pivotY) * h;
                float rx = cx * cos - cy * sin, ry = cx * sin + cy * cos;
                best = Math.max(best, rx * dx + ry * dy);
            }
            return best;
        }
    }

    // Screen, in pixels.
    private float screenW = 1, screenH = 1, insetTop, insetBottom;

    final Body[] bodies = new Body[MAX_BODIES];
    private int count = 1;
    private float time;
    /** Water drag per second; lower drifts longer (space). */
    private float drag = 1.6f;

    // Touch (written from the UI thread).
    private volatile boolean holding;
    private volatile float touchX, touchY;
    /** The body the finger grabbed, or -1; chosen on the render thread when a touch starts. */
    private int grabbed = -1;

    /** Ripples as (x, y, age seconds, strength); age > RIPPLE_LIFE means unused. */
    final float[] ripples = new float[MAX_RIPPLES * 4];
    private int nextRipple;
    private final float[] pendingTaps = new float[16];
    private int pendingCount;

    public ReactPhysics() {
        for (int i = 0; i < MAX_RIPPLES; i++) ripples[i * 4 + 2] = RIPPLE_LIFE + 1;
        for (int i = 0; i < MAX_BODIES; i++) bodies[i] = new Body();
    }

    /** The screen size, and the strips at the top and bottom it keeps clear of (status and nav bars). */
    public void setScreen(float w, float h, float top, float bottom) {
        screenW = Math.max(1, w);
        screenH = Math.max(1, h);
        insetTop = top;
        insetBottom = bottom;
    }

    /** How many characters float (1..{@link #MAX_BODIES}). */
    public void setCount(int n) {
        count = Math.max(1, Math.min(MAX_BODIES, n));
        if (grabbed >= count) grabbed = -1;
    }

    public int count() {
        return count;
    }

    /** Character {@code i}'s drawn size in pixels, its pivot (fractions of that size) and its outline. */
    public void setCharacter(int i, float w, float h, float pivotFx, float pivotFy, @Nullable float[] hull) {
        Body b = bodies[i];
        b.w = Math.max(1, w);
        b.h = Math.max(1, h);
        b.pivotX = pivotFx;
        b.pivotY = pivotFy;
        b.hull = hull != null && hull.length >= 6 ? hull : BOX;
        if (!b.placed) {
            // Spread out: the first in the middle, the others around it.
            float fx = i == 0 ? 0.5f : 0.22f + 0.56f * ((i * 0.618f + 0.1f) % 1f);
            float fy = i == 0 ? 0.45f : 0.2f + 0.6f * (i - 1) / Math.max(1, MAX_BODIES - 2);
            b.x = screenW * fx;
            b.y = screenH * fy;
            b.placed = true;
        }
    }

    public void setDrag(float perSecond) {
        drag = perSecond;
    }

    /** A finger is down at (x, y), or lifted when {@code down} is false. Any thread. */
    public void touch(boolean down, float tx, float ty) {
        touchX = tx;
        touchY = ty;
        holding = down;
    }

    /** A tap or touch at (x, y): a ripple there. Any thread. */
    public synchronized void tap(float tx, float ty) {
        if (pendingCount * 2 >= pendingTaps.length) return;
        pendingTaps[pendingCount * 2] = tx;
        pendingTaps[pendingCount * 2 + 1] = ty;
        pendingCount++;
    }

    /** Advances by {@code dt} seconds with tilt (-1..1 each way, toward screen right and down). */
    public void step(float dt, float tiltX, float tiltY) {
        dt = Math.min(dt, 1 / 20f);
        time += dt;
        synchronized (this) {
            for (int i = 0; i < pendingCount; i++) addRipple(pendingTaps[i * 2], pendingTaps[i * 2 + 1], 1f);
            pendingCount = 0;
        }
        for (int i = 0; i < MAX_RIPPLES; i++) ripples[i * 4 + 2] += dt;

        float unit = Math.min(screenW, screenH);
        boolean hold = holding;
        float fx = touchX, fy = touchY;
        if (!hold) grabbed = -1;
        else if (grabbed < 0) grabbed = nearest(fx, fy);

        for (int i = 0; i < count; i++) {
            Body b = bodies[i];
            // Tilt: full tilt reaches the same top speed (1.5 screens a second) whatever the drag;
            // with little drag (space) it just takes longer to get going and to stop.
            float ax = tiltX * unit * 1.5f * drag;
            float ay = tiltY * unit * 1.5f * drag;
            // A slow, wandering current so it never sits still; each one's is a little different.
            float t = time + i * 7.3f;
            ax += unit * 0.05f * (float) (Math.sin(t * 0.21) + 0.6 * Math.sin(t * 0.53 + 1.3));
            ay += unit * 0.05f * (float) (Math.cos(t * 0.17 + 0.4) + 0.6 * Math.sin(t * 0.41 + 2.1));
            if (i == grabbed) {
                // Drift toward the finger like a float pulled through water.
                ax += (fx - b.x) * 2.2f;
                ay += (fy - b.y) * 2.2f;
            }
            float slow = (float) Math.exp(-drag * dt);
            b.vx = (b.vx + ax * dt) * slow;
            b.vy = (b.vy + ay * dt) * slow;
            b.x += b.vx * dt;
            b.y += b.vy * dt;

            // Lean into sideways motion, like a boat turning, with a gentle idle sway.
            float target = clamp(b.vx / unit * 0.9f, -MAX_LEAN, MAX_LEAN) + 0.04f * (float) Math.sin(t * 0.8);
            b.angleVel += ((target - b.angle) * 18f - b.angleVel * 5.5f) * dt;
            b.angle = clamp(b.angle + b.angleVel * dt, -MAX_LEAN, MAX_LEAN);
        }

        for (int i = 0; i < count; i++) for (int j = i + 1; j < count; j++) collide(bodies[i], bodies[j], unit);
        for (int i = 0; i < count; i++) walls(bodies[i], unit);

        for (int i = 0; i < count; i++) {
            Body b = bodies[i];
            // Squash springs back with a small wobble.
            b.squashVel += (-b.squash * 260f - b.squashVel * 9f) * dt;
            b.squash = clamp(b.squash + b.squashVel * dt, -MAX_SQUASH, MAX_SQUASH);

            // A little wake behind it while it moves.
            float speed = (float) Math.hypot(b.vx, b.vy);
            b.wakeTimer -= dt;
            if (speed > unit * 0.12f && b.wakeTimer <= 0) {
                b.wakeTimer = 0.35f;
                float back = Math.max(b.w, b.h) * 0.35f / speed;
                addRipple(b.x - b.vx * back, b.y - b.vy * back, Math.min(0.6f, speed / unit) / (float) Math.sqrt(count));
            }
        }
    }

    private int nearest(float tx, float ty) {
        int best = 0;
        double bestD = Double.MAX_VALUE;
        for (int i = 0; i < count; i++) {
            double d = Math.hypot(bodies[i].x - tx, bodies[i].y - ty);
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        return best;
    }

    /**
     * Keeps the whole (turned) outline on screen and bounces it off the edges. On an axis where it
     * can't fit at all it is held in the middle, so it never flips between the two edges.
     */
    private void walls(Body b, float unit) {
        float[] ext = b.extents();
        float left = 0, right = screenW, top = insetTop, bottom = screenH - insetBottom;
        if (ext[1] - ext[0] >= right - left) {
            b.x = (left + right) / 2f - (ext[0] + ext[1]) / 2f;
            b.vx *= 0.5f;
        } else if (b.x + ext[0] < left) {
            b.x = left - ext[0];
            bounce(b, b.vx < 0 ? -b.vx : 0, 1, 0, unit);
            if (b.vx < 0) b.vx = -b.vx * RESTITUTION;
        } else if (b.x + ext[1] > right) {
            b.x = right - ext[1];
            bounce(b, b.vx > 0 ? b.vx : 0, -1, 0, unit);
            if (b.vx > 0) b.vx = -b.vx * RESTITUTION;
        }
        if (ext[3] - ext[2] >= bottom - top) {
            b.y = (top + bottom) / 2f - (ext[2] + ext[3]) / 2f;
            b.vy *= 0.5f;
        } else if (b.y + ext[2] < top) {
            b.y = top - ext[2];
            bounce(b, b.vy < 0 ? -b.vy : 0, 0, 1, unit);
            if (b.vy < 0) b.vy = -b.vy * RESTITUTION;
        } else if (b.y + ext[3] > bottom) {
            b.y = bottom - ext[3];
            bounce(b, b.vy > 0 ? b.vy : 0, 0, -1, unit);
            if (b.vy > 0) b.vy = -b.vy * RESTITUTION;
        }
    }

    /** Pushes two overlapping characters apart along the line between them and bounces them. */
    private void collide(Body a, Body b, float unit) {
        float dx = b.x - a.x, dy = b.y - a.y;
        float d = (float) Math.hypot(dx, dy);
        if (d < 1e-3f) {
            dx = 1;
            dy = 0;
            d = 1e-3f;
        } else {
            dx /= d;
            dy /= d;
        }
        float overlap = a.reach(dx, dy) + b.reach(-dx, -dy) - d;
        if (overlap <= 0) return;
        a.x -= dx * overlap / 2f;
        a.y -= dy * overlap / 2f;
        b.x += dx * overlap / 2f;
        b.y += dy * overlap / 2f;
        float closing = (a.vx - b.vx) * dx + (a.vy - b.vy) * dy;
        if (closing <= 0) return;
        float j = closing * (1 + RESTITUTION) / 2f;
        a.vx -= j * dx;
        a.vy -= j * dy;
        b.vx += j * dx;
        b.vy += j * dy;
        bounce(a, closing / 2f, -dx, -dy, unit);
        bounce(b, closing / 2f, dx, dy, unit);
    }

    private void bounce(Body b, float impact, float nx, float ny, float unit) {
        if (impact < unit * 0.08f) return;
        b.squashNx = nx;
        b.squashNy = ny;
        b.squashVel += Math.min(1.2f, impact / unit * 1.5f);
        if (impact > unit * 0.3f) addRipple(b.x - nx * b.w * 0.4f, b.y - ny * b.h * 0.4f, Math.min(1f, impact / unit));
    }

    private void addRipple(float rx, float ry, float strength) {
        int i = nextRipple;
        nextRipple = (nextRipple + 1) % MAX_RIPPLES;
        ripples[i * 4] = rx;
        ripples[i * 4 + 1] = ry;
        ripples[i * 4 + 2] = 0;
        ripples[i * 4 + 3] = strength;
    }

    float time() {
        return time;
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
