#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
// Reactive wallpaper background. u_mode picks the scene: 0 water seen from above (caustic light,
// optional pool tiles), 1 space (nebula and twinkling stars), 2 falling leaves, 3 snow, 4 sakura
// petals, 5 sky with drifting clouds. Or the user's photo when u_hasPhoto is set. Ripples from
// touches, bounces and the wake bend every scene. u_effects adds layers on top (bits: 1 bubbles,
// 2 sparkles, 4 fireflies, 8 rain, 16 sun rays; 32, the glow, is drawn with the characters).
precision highp float;

in vec2 v_texCoord;
out vec4 fragColor;

uniform vec2 u_res;          // surface size, pixels
uniform float u_time;
uniform float u_mode;
uniform vec3 u_shallow;      // first colour (top / light)
uniform vec3 u_deep;         // second colour (bottom / dark)
uniform vec3 u_light;
uniform float u_tiles;       // 1 = pool tiles on the floor
uniform float u_hasPhoto;
uniform sampler2D u_photo;
uniform vec2 u_photoScale;   // cover-crop: uv = 0.5 + (uv - 0.5) * scale
uniform float u_hasVideo;    // the background is a video (u_video) rather than u_photo
uniform samplerExternalOES u_video;
uniform mat4 u_videoMatrix;  // the video frame's texture transform (its y points up)
uniform vec4 u_ripples[12];  // x, y (pixels, y down), age (s), strength
uniform int u_effects;

float hash(vec2 p) {
    p = fract(p * vec2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}

// Smooth wavy noise in 0..1 from crossed sines. Hash-based value noise came out blocky on some
// phones' GPUs; this has no cells, so it cannot step.
float waves(vec2 p) {
    return 0.5 + 0.25 * (sin(p.x * 1.7 + sin(p.y * 1.3) * 1.5) + sin(p.y * 1.9 + sin(p.x * 1.1 + 1.7) * 1.5));
}

float fbm(vec2 p) {
    float v = 0.0, a = 0.5;
    mat2 turn = mat2(0.8, -0.6, 0.6, 0.8);
    for (int i = 0; i < 4; i++) {
        v += a * waves(p);
        p = turn * p * 2.1 + vec2(3.1, 1.7);
        a *= 0.5;
    }
    return v / 0.9375;
}

mat2 rot(float a) {
    float c = cos(a), s = sin(a);
    return mat2(c, -s, s, c);
}

// Caustic light: the classic iterated-sine water pattern ("Tileable Water Caustic"). It expects
// p in the 0..2pi tile shifted far from the origin, which gives thin bright lines on dark.
float caustic(vec2 uv, float t) {
    vec2 p = mod(uv * 6.28318, 6.28318) - 250.0;
    vec2 i = p;
    float c = 1.0;
    float inten = 0.005;
    for (int n = 0; n < 5; n++) {
        float tt = t * (1.0 - (3.5 / float(n + 1)));
        i = p + vec2(cos(tt - i.x) + sin(tt + i.y), sin(tt - i.y) + cos(tt + i.x));
        c += 1.0 / length(vec2(p.x / (sin(i.x + tt) / inten), p.y / (cos(i.y + tt) / inten)));
    }
    c /= 5.0;
    c = 1.17 - pow(c, 1.4);
    return clamp(pow(abs(c), 8.0), 0.0, 1.0);
}

// One layer of falling things (kind 2 leaf, 3 snowflake, 4 petal): colour and coverage.
vec4 falling(vec2 p, float t, float density, float speed, float size, float kind, float seed) {
    vec2 q = p * density;
    q.y -= t * speed * density;
    q.x += sin(t * 0.6 + q.y * 0.35 + seed) * 0.35;
    vec2 cell = floor(q);
    float h = hash(cell + seed);
    if (h > 0.5) return vec4(0.0);
    vec2 off = (vec2(hash(cell + 1.3 + seed), hash(cell + 7.1 + seed)) - 0.5) * 0.5;
    vec2 d = fract(q) - 0.5 - off;
    // Tumbling as it falls.
    d = rot(t * (0.6 + h * 2.4) + h * 40.0) * d;
    float s = size * (0.7 + h * 0.8);
    float cover;
    vec3 col;
    if (kind < 2.5) {
        // Leaf: a pointed oval with a slightly darker middle vein.
        vec2 e = d / vec2(s, s * 0.48);
        float r = length(e) + abs(e.x) * 0.25;
        cover = smoothstep(1.0, 0.85, r);
        float pick = hash(cell + 3.7 + seed);
        col = pick < 0.33 ? vec3(0.93, 0.45, 0.12) : pick < 0.66 ? vec3(0.80, 0.22, 0.10) : vec3(0.96, 0.72, 0.20);
        col *= 1.0 - 0.25 * smoothstep(0.06, 0.0, abs(e.y));
    } else if (kind < 3.5) {
        // Snowflake: a soft dot.
        cover = smoothstep(s * 0.42, s * 0.12, length(d)) * 0.95;
        col = vec3(1.0);
    } else {
        // Petal: a small notched oval.
        vec2 e = d / vec2(s * 0.75, s * 0.5);
        float r = length(e) - 0.25 * smoothstep(0.0, 1.0, max(0.0, e.x)) * smoothstep(0.35, 0.0, abs(e.y));
        cover = smoothstep(1.0, 0.8, r);
        col = mix(vec3(1.0, 0.75, 0.84), vec3(1.0, 0.93, 0.96), hash(cell + 5.5 + seed));
    }
    return vec4(col, cover);
}

// The optional effect layers over the scene. p is in units of the screen's short side (y down),
// y is 0 at the top and 1 at the bottom, ext is the screen's size in those units.
vec3 effects(vec3 color, vec2 p, float y, float t, vec2 ext) {
    if ((u_effects & 16) != 0) {
        // Sun rays: slanted shafts of light from the top, slowly shifting.
        vec2 q = rot(0.35) * p;
        float r = 0.5 + 0.5 * sin(q.x * 6.0 + sin(q.x * 2.3 + t * 0.25) * 2.0 + t * 0.15);
        r *= 0.55 + 0.45 * sin(q.x * 13.0 - t * 0.4);
        color += u_light * pow(r, 3.0) * 0.22 * smoothstep(1.1, 0.0, y);
    }
    if ((u_effects & 8) != 0) {
        // Rain: thin falling streaks, and rings where drops land.
        vec2 q = p * vec2(26.0, 3.0);
        q.y -= t * 7.0;
        q.x += q.y * 0.08;
        vec2 cell = floor(q);
        float h = hash(cell);
        if (h < 0.35) {
            vec2 f = fract(q) - vec2(0.5 + (hash(cell + 3.3) - 0.5) * 0.6, 0.5);
            float streak = smoothstep(0.06, 0.0, abs(f.x)) * smoothstep(0.45, 0.0, abs(f.y));
            color = mix(color, vec3(0.85, 0.92, 1.0), streak * 0.35);
        }
        vec2 rq = p * 4.0;
        vec2 rc = floor(rq);
        float phase = fract(t * 0.8 + hash(rc + 9.1));
        // Each ring stays inside its own cell (offset + radius < 0.5), or it would be cut off.
        vec2 at = rc + 0.5 + (vec2(hash(rc + 1.7), hash(rc + 4.2)) - 0.5) * 0.3;
        float d = length(rq - at);
        float ring = smoothstep(0.03, 0.0, abs(d - phase * 0.28)) * (1.0 - phase);
        color += u_light * ring * 0.45;
    }
    if ((u_effects & 1) != 0) {
        // Bubbles rising and wobbling, two layers.
        for (int l = 0; l < 2; l++) {
            float fl = float(l);
            float dens = 4.0 + fl * 3.0;
            vec2 q = p * dens;
            q.y += t * (0.35 + fl * 0.15) * dens * 0.25;
            q.x += sin(q.y * 1.3 + t * 1.5 + fl * 2.0) * 0.12;
            vec2 cell = floor(q);
            float h = hash(cell + fl * 17.0);
            if (h > 0.3) continue;
            vec2 f = fract(q) - 0.5 - (vec2(hash(cell + 2.2), hash(cell + 6.6)) - 0.5) * 0.5;
            float rr = 0.07 + 0.12 * hash(cell + 8.8);
            float d = length(f);
            float rim = smoothstep(0.03, 0.0, abs(d - rr));
            float fill = smoothstep(rr, rr * 0.6, d) * 0.12;
            float spot = smoothstep(rr * 0.35, 0.0, length(f + rr * vec2(0.35, 0.35)));
            color = mix(color, vec3(1.0), clamp(rim * 0.55 + fill + spot * 0.8, 0.0, 1.0));
        }
    }
    if ((u_effects & 2) != 0) {
        // Sparkles: little four-point stars twinkling in and out.
        vec2 q = p * 8.0;
        vec2 cell = floor(q);
        float h = hash(cell + 21.0);
        if (h < 0.3) {
            vec2 f = fract(q) - 0.5 - (vec2(hash(cell + 3.1), hash(cell + 5.3)) - 0.5) * 0.4;
            float tw = pow(max(0.0, sin(t * (1.2 + h * 4.0) + h * 60.0)), 8.0);
            f = rot(t * 0.5 + h * 6.0) * f;
            float star = smoothstep(0.02, 0.0, abs(f.x)) * smoothstep(0.2, 0.0, abs(f.y))
                       + smoothstep(0.02, 0.0, abs(f.y)) * smoothstep(0.2, 0.0, abs(f.x));
            float core = exp(-dot(f, f) * 600.0);
            color += mix(u_light, vec3(1.0), 0.5) * (star * 0.9 + core) * tw;
        }
    }
    if ((u_effects & 4) != 0) {
        // Fireflies: warm dots wandering slowly, each pulsing.
        for (int i = 0; i < 10; i++) {
            float fi = float(i);
            vec2 at = vec2(0.5 + 0.45 * sin(t * 0.13 * (1.0 + fi * 0.17) + fi * 3.1),
                           0.5 + 0.45 * sin(t * 0.11 * (1.0 + fi * 0.23) + fi * 1.7)) * ext;
            float d = length(p - at);
            float pulse = 0.5 + 0.5 * sin(t * (1.5 + fi * 0.2) + fi * 2.0);
            color += vec3(1.0, 0.85, 0.35) * (exp(-d * d * 2500.0) * 1.2 + exp(-d * d * 180.0) * 0.25) * pulse;
        }
    }
    return color;
}

void main() {
    // Pixel position with y down, to match the physics.
    vec2 q = vec2(v_texCoord.x, 1.0 - v_texCoord.y) * u_res;
    float unit = min(u_res.x, u_res.y);

    // Ripples push the scene around and catch the light.
    vec2 disp = vec2(0.0);
    float shine = 0.0;
    for (int k = 0; k < 12; k++) {
        vec4 r = u_ripples[k];
        if (r.z > 3.0) continue;
        vec2 d = q - r.xy;
        float dist = length(d);
        float radius = r.z * unit * 0.32;
        float band = (dist - radius) / (unit * 0.035);
        float fade = r.w * exp(-r.z * 1.4) * exp(-band * band);
        float wave = sin((dist - radius) / (unit * 0.011)) * fade;
        disp += d / max(dist, 1.0) * wave * unit * 0.010;
        shine += wave;
    }
    vec2 p = (q + disp) / unit;
    float y = (q.y + disp.y) / u_res.y;

    vec3 color;
    if (u_hasPhoto > 0.5) {
        vec2 uv = (q + disp) / u_res;
        uv = 0.5 + (uv - 0.5) * u_photoScale;
        color = u_hasVideo > 0.5
            ? texture(u_video, (u_videoMatrix * vec4(uv.x, 1.0 - uv.y, 0.0, 1.0)).xy).rgb
            : texture(u_photo, uv).rgb;
        color += u_light * caustic(p * 0.9, u_time * 0.5 + 23.0) * 0.2;
        color += u_light * shine * 0.10;
    } else if (u_mode < 0.5) {
        // Water: shallow in the middle, deeper toward the bottom, with slow broad swells.
        float swell = 0.5 + 0.5 * sin(p.x * 2.1 + u_time * 0.25) * sin(p.y * 1.7 - u_time * 0.2);
        color = mix(u_shallow, u_deep, clamp(0.25 + 0.55 * y + 0.2 * swell, 0.0, 1.0));
        if (u_tiles > 0.5) {
            // Square pool tiles with lighter grout, wobbling with the surface.
            vec2 w = p + 0.012 * vec2(sin(p.y * 9.0 + u_time * 1.3), sin(p.x * 8.0 + u_time * 1.1));
            vec2 g = abs(fract(w * 7.0) - 0.5);
            float grout = smoothstep(0.455, 0.49, max(g.x, g.y));
            color = mix(color, mix(color, vec3(1.0), 0.55), grout);
        }
        color += u_light * caustic(p * 0.9, u_time * 0.5 + 23.0) * 0.55;
        color += u_light * shine * 0.10;
    } else if (u_mode < 1.5) {
        // Space: deep gradient, slowly turning nebula clouds, three layers of twinkling stars.
        color = mix(u_shallow * 0.35, u_deep, y);
        vec2 n = p * 1.4 + vec2(u_time * 0.01, -u_time * 0.006);
        float neb = fbm(n + fbm(n * 1.7 + u_time * 0.02));
        color += u_shallow * smoothstep(0.35, 0.85, neb) * 0.9;
        color += vec3(0.55, 0.25, 0.65) * smoothstep(0.55, 0.95, fbm(n * 2.3 + 4.0)) * 0.5;
        for (int l = 0; l < 3; l++) {
            float dens = 22.0 + float(l) * 18.0;
            vec2 sp = p * dens + vec2(u_time * 0.15 * float(l + 1), 0.0);
            vec2 cell = floor(sp);
            float h = hash(cell + float(l) * 13.1);
            if (h > 0.92) {
                vec2 c = vec2(hash(cell + 2.1), hash(cell + 5.9)) - 0.5;
                float d = length(fract(sp) - 0.5 - c * 0.6);
                float tw = 0.6 + 0.4 * sin(u_time * (1.0 + h * 3.0) + h * 50.0);
                color += u_light * smoothstep(0.09, 0.0, d) * tw * (1.2 - float(l) * 0.3);
            }
        }
        color += u_light * shine * 0.12;
    } else if (u_mode < 4.5) {
        // Falling things over a soft gradient: leaves, snow or petals, near layers bigger and faster.
        color = mix(u_shallow, u_deep, clamp(y + 0.15 * (fbm(p * 1.2 + u_time * 0.03) - 0.5), 0.0, 1.0));
        if (u_mode > 2.5 && u_mode < 3.5) {
            // Snow: a faint glow low in the sky.
            color += u_light * 0.08 * smoothstep(0.4, 1.0, 1.0 - y);
        }
        for (int l = 0; l < 3; l++) {
            float fl = float(l);
            vec4 f = falling(p, u_time, 7.0 - fl * 2.0, 0.05 + fl * 0.05, 0.16 + fl * 0.05, u_mode, fl * 31.7);
            // Far layers are paler, as if through the air.
            vec3 c = mix(color, f.rgb, 0.55 + fl * 0.2);
            color = mix(color, c, f.a);
        }
        color += u_light * shine * 0.08;
    } else {
        // Sky: blue fading up, clouds drifting by in two layers.
        color = mix(u_deep, u_shallow, clamp(y * 1.2, 0.0, 1.0));
        float far = fbm(p * vec2(1.2, 2.4) + vec2(u_time * 0.015, 0.0));
        float near = fbm(p * vec2(0.8, 1.6) + vec2(u_time * 0.035, 3.0));
        color = mix(color, vec3(0.94, 0.97, 1.0), smoothstep(0.5, 0.75, far) * 0.6);
        color = mix(color, vec3(1.0), smoothstep(0.55, 0.8, near) * 0.85);
        color += u_light * shine * 0.06;
    }
    if (u_effects != 0) color = effects(color, p, y, u_time, u_res / unit);
    fragColor = vec4(color, 1.0);
}
