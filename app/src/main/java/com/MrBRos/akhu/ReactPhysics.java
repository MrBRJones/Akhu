package com.MrBRos.akhu;

/**
 * The floating character's motion, in screen pixels (y down) and seconds. Tilt pushes it
 * downhill through water drag, a slow current keeps it drifting when the phone is still, and a
 * held finger pulls it in. It bounces softly off the edges (squashing a little against the wall
 * and wobbling back) and leans into its sideways motion like a boat turning, never past about 30
 * degrees. It turns around its pivot (the centre of the float), not the image centre.
 *
 * <p>Not thread-safe: the render thread owns it, other threads only post touches.</p>
 */
public final class ReactPhysics {

    /** Ripples kept at once; must match the shader's array size. */
    public static final int MAX_RIPPLES = 12;
    /** How long a ripple lasts, in seconds. */
    static final float RIPPLE_LIFE = 3f;

    private static final float MAX_LEAN = (float) Math.toRadians(30);
    private static final float MAX_SQUASH = 0.05f;

    // Screen and character, in pixels.
    private float screenW = 1, screenH = 1, insetTop, insetBottom;
    /** The character's drawn size, and its pivot as a fraction of it. */
    private float charW = 1, charH = 1, pivotX = 0.5f, pivotY = 0.5f;
    private boolean placed;

    // State.
    float x, y, vx, vy;
    /** Lean, radians (positive = clockwise on screen). */
    float angle;
    private float angleVel;
    /** Squash amount (0..MAX_SQUASH) and its axis (unit vector of the wall's normal). */
    float squash;
    private float squashVel;
    float squashNx = 1, squashNy;
    private float time;
    private float wakeTimer;
    /** Water drag per second; lower drifts longer (space). */
    private float drag = 1.6f;
    /** Scratch for {@link #extents}, reused every frame. */
    private final float[] ext = new float[4];

    // Touch (written from the UI thread).
    private volatile boolean holding;
    private volatile float touchX, touchY;

    /** Ripples as (x, y, age seconds, strength); age > RIPPLE_LIFE means unused. */
    final float[] ripples = new float[MAX_RIPPLES * 4];
    private int nextRipple;
    private final float[] pendingTaps = new float[16];
    private int pendingCount;

    public ReactPhysics() {
        for (int i = 0; i < MAX_RIPPLES; i++) ripples[i * 4 + 2] = RIPPLE_LIFE + 1;
    }

    /** The screen size, and the strips at the top and bottom it keeps clear of (status and nav bars). */
    public void setScreen(float w, float h, float top, float bottom) {
        screenW = Math.max(1, w);
        screenH = Math.max(1, h);
        insetTop = top;
        insetBottom = bottom;
        if (!placed) {
            x = screenW / 2;
            y = screenH * 0.45f;
            placed = true;
        }
    }

    /** The character's drawn size in pixels and its pivot (fractions of that size). */
    public void setCharacter(float w, float h, float pivotFx, float pivotFy) {
        charW = Math.max(1, w);
        charH = Math.max(1, h);
        pivotX = pivotFx;
        pivotY = pivotFy;
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
        // Tilt: full tilt reaches the same top speed (1.5 screens a second) whatever the drag;
        // with little drag (space) it just takes longer to get going and to stop.
        float ax = tiltX * unit * 1.5f * drag;
        float ay = tiltY * unit * 1.5f * drag;
        // A slow, wandering current so it never sits still.
        ax += unit * 0.05f * (float) (Math.sin(time * 0.21) + 0.6 * Math.sin(time * 0.53 + 1.3));
        ay += unit * 0.05f * (float) (Math.cos(time * 0.17 + 0.4) + 0.6 * Math.sin(time * 0.41 + 2.1));
        if (holding) {
            // Drift toward the finger like a float pulled through water.
            ax += (touchX - x) * 2.2f;
            ay += (touchY - y) * 2.2f;
        }
        float slow = (float) Math.exp(-drag * dt);
        vx = (vx + ax * dt) * slow;
        vy = (vy + ay * dt) * slow;
        x += vx * dt;
        y += vy * dt;

        // Lean into sideways motion, like a boat turning, with a gentle idle sway.
        float target = clamp(vx / unit * 0.9f, -MAX_LEAN, MAX_LEAN) + 0.04f * (float) Math.sin(time * 0.8);
        angleVel += ((target - angle) * 18f - angleVel * 5.5f) * dt;
        angle = clamp(angle + angleVel * dt, -MAX_LEAN, MAX_LEAN);

        collide(unit);

        // Squash springs back with a small wobble.
        squashVel += (-squash * 260f - squashVel * 9f) * dt;
        squash = clamp(squash + squashVel * dt, -MAX_SQUASH, MAX_SQUASH);

        // A little wake behind it while it moves.
        float speed = (float) Math.hypot(vx, vy);
        wakeTimer -= dt;
        if (speed > unit * 0.12f && wakeTimer <= 0) {
            wakeTimer = 0.35f;
            float back = Math.max(charW, charH) * 0.35f / speed;
            addRipple(x - vx * back, y - vy * back, Math.min(0.6f, speed / unit));
        }
    }

    /** Keeps the whole (turned, squashed) character on screen and bounces it off the edges. */
    private void collide(float unit) {
        float[] ext = extents();
        float left = 0, right = screenW, top = insetTop, bottom = screenH - insetBottom;
        if (x + ext[0] < left) {
            x = left - ext[0];
            bounce(vx < 0 ? -vx : 0, 1, 0, unit);
            if (vx < 0) vx = -vx * 0.45f;
        } else if (x + ext[1] > right) {
            x = right - ext[1];
            bounce(vx > 0 ? vx : 0, -1, 0, unit);
            if (vx > 0) vx = -vx * 0.45f;
        }
        if (y + ext[2] < top) {
            y = top - ext[2];
            bounce(vy < 0 ? -vy : 0, 0, 1, unit);
            if (vy < 0) vy = -vy * 0.45f;
        } else if (y + ext[3] > bottom) {
            y = bottom - ext[3];
            bounce(vy > 0 ? vy : 0, 0, -1, unit);
            if (vy > 0) vy = -vy * 0.45f;
        }
    }

    private void bounce(float impact, float nx, float ny, float unit) {
        if (impact < unit * 0.08f) return;
        squashNx = nx;
        squashNy = ny;
        squashVel += Math.min(1.2f, impact / unit * 1.5f);
        if (impact > unit * 0.3f) addRipple(x - nx * charW * 0.4f, y - ny * charH * 0.4f, Math.min(1f, impact / unit));
    }

    /** Offsets of the turned character's left, right, top and bottom edges from its pivot. */
    float[] extents() {
        float cos = (float) Math.cos(angle), sin = (float) Math.sin(angle);
        float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (int i = 0; i < 4; i++) {
            float cx = ((i & 1) == 0 ? -pivotX : 1 - pivotX) * charW;
            float cy = ((i & 2) == 0 ? -pivotY : 1 - pivotY) * charH;
            float rx = cx * cos - cy * sin, ry = cx * sin + cy * cos;
            minX = Math.min(minX, rx);
            maxX = Math.max(maxX, rx);
            minY = Math.min(minY, ry);
            maxY = Math.max(maxY, ry);
        }
        // The visible character is a bit smaller than its box; let the corners tuck past the edge.
        float slack = 0.06f;
        ext[0] = minX * (1 - slack);
        ext[1] = maxX * (1 - slack);
        ext[2] = minY * (1 - slack);
        ext[3] = maxY * (1 - slack);
        return ext;
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

    float pivotX() {
        return pivotX;
    }

    float pivotY() {
        return pivotY;
    }

    float charW() {
        return charW;
    }

    float charH() {
        return charH;
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
