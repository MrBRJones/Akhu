#version 300 es
// Reactive wallpaper background. u_mode picks the scene: 0 water seen from above (caustic light,
// optional pool tiles), 1 space (nebula and twinkling stars), 2 falling leaves, 3 snow, 4 sakura
// petals, 5 sky with drifting clouds. Or the user's photo when u_hasPhoto is set. Ripples from
// touches, bounces and the wake bend every scene.
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
uniform vec4 u_ripples[12];  // x, y (pixels, y down), age (s), strength

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
        color = texture(u_photo, uv).rgb;
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
    fragColor = vec4(color, 1.0);
}
