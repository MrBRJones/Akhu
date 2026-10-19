#version 300 es
precision highp float;

in vec2 v_texCoord;
out vec4 fragColor;

uniform sampler2D u_texture;
uniform float u_time;
uniform vec2 u_resolution;
uniform vec2 u_texel;

// 1. Motion / Displacement
uniform bool u_motion_enabled;
uniform float u_motion_speed;
uniform float u_motion_amplitude;
uniform float u_motion_frequency;

// 2. Blur
uniform bool u_blur_enabled;
uniform float u_blur_radius;
uniform float u_blur_samples;

// 3. Color Shift
uniform bool u_colorshift_enabled;
uniform float u_colorshift_hue;
uniform float u_colorshift_saturation;
uniform float u_colorshift_contrast;

// 4. Glow
uniform bool u_glow_enabled;
uniform float u_glow_intensity;
uniform float u_glow_threshold;
uniform float u_glow_radius;

// 5. Noise / Grain
uniform bool u_noise_enabled;
uniform float u_noise_scale;
uniform float u_noise_intensity;
uniform float u_noise_speed;

// Particles: posSize = (x, y, size, alpha), color = (r, g, b, 0)
const int MAX_PARTICLES = 64;
uniform vec4 u_particle_posSize[MAX_PARTICLES];
uniform vec4 u_particle_color[MAX_PARTICLES];
uniform int u_particle_count;

const float TAU = 6.2831853;

float hash(vec2 p) {
    p = fract(p * vec2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}

float luma(vec3 c) {
    return dot(c, vec3(0.299, 0.587, 0.114));
}

vec3 hueRotate(vec3 c, float degrees) {
    float a = radians(degrees);
    // Rotate chroma in YIQ space.
    mat3 toYiq = mat3(0.299, 0.596, 0.211,
                      0.587, -0.274, -0.523,
                      0.114, -0.322, 0.312);
    mat3 toRgb = mat3(1.0, 1.0, 1.0,
                      0.956, -0.272, -1.106,
                      0.621, -0.647, 1.703);
    vec3 yiq = toYiq * c;
    float cs = cos(a), sn = sin(a);
    yiq.yz = mat2(cs, sn, -sn, cs) * yiq.yz;
    return toRgb * yiq;
}

void main() {
    vec2 uv = v_texCoord;

    // 1. Motion / Displacement
    if (u_motion_enabled) {
        float t = u_time * u_motion_speed;
        uv += vec2(sin(uv.y * u_motion_frequency + t),
                   cos(uv.x * u_motion_frequency + t * 1.3)) * u_motion_amplitude;
        uv = clamp(uv, 0.0, 1.0);
    }

    // 2. Blur (ring samples around the pixel)
    vec3 color;
    if (u_blur_enabled && u_blur_radius > 0.0) {
        int samples = int(clamp(u_blur_samples, 4.0, 16.0));
        vec3 sum = texture(u_texture, uv).rgb;
        float weight = 1.0;
        for (int i = 0; i < 16; i++) {
            if (i >= samples) break;
            float a = TAU * float(i) / float(samples);
            vec2 dir = vec2(cos(a), sin(a)) * u_texel;
            sum += texture(u_texture, uv + dir * u_blur_radius).rgb;
            sum += texture(u_texture, uv + dir * u_blur_radius * 0.5).rgb;
            weight += 2.0;
        }
        color = sum / weight;
    } else {
        color = texture(u_texture, uv).rgb;
    }

    // 3. Color Shift
    if (u_colorshift_enabled) {
        color = hueRotate(color, u_colorshift_hue);
        color = mix(vec3(luma(color)), color, u_colorshift_saturation);
        color = (color - 0.5) * u_colorshift_contrast + 0.5;
    }

    // 4. Glow (bright-pass bloom approximation)
    if (u_glow_enabled) {
        vec3 bloom = vec3(0.0);
        for (int i = 0; i < 8; i++) {
            float a = TAU * float(i) / 8.0;
            vec2 off = vec2(cos(a), sin(a)) * u_texel * u_glow_radius;
            vec3 s = texture(u_texture, uv + off).rgb;
            bloom += s * smoothstep(u_glow_threshold, 1.0, luma(s));
        }
        color += bloom / 8.0 * u_glow_intensity;
    }

    // 5. Noise / Grain
    if (u_noise_enabled) {
        vec2 p = floor(v_texCoord * u_noise_scale) + floor(u_time * u_noise_speed * 24.0);
        color += (hash(p) - 0.5) * u_noise_intensity;
    }

    // Particle pass, composited last with distance falloff.
    vec2 screen = gl_FragCoord.xy / u_resolution;
    vec2 aspect = vec2(u_resolution.x / u_resolution.y, 1.0);
    for (int i = 0; i < u_particle_count && i < MAX_PARTICLES; i++) {
        vec4 ps = u_particle_posSize[i];
        float d = length((screen - ps.xy) * aspect);
        float falloff = 1.0 - smoothstep(0.0, ps.z, d);
        color += u_particle_color[i].rgb * falloff * falloff * ps.w;
    }

    fragColor = vec4(clamp(color, 0.0, 1.0), 1.0);
}
