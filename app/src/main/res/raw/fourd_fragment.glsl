#version 300 es
precision highp float;

// 4D (layered parallax): three pre-cut layers slide rigidly by the device tilt, scaled per layer,
// and are composited back to front. Rigid layers keep textures crisp; the holes behind each
// cut-out were inpainted offline so nothing smears or doubles when layers separate.
// Textures are premultiplied (Android bitmaps), so compositing is "src + dst * (1 - srcA)".

in vec2 v_texCoord;
out vec4 fragColor;

uniform sampler2D u_far;
uniform sampler2D u_mid;
uniform sampler2D u_near;
uniform vec2 u_offset;   // tilt in uv units (already multiplied by strength)
uniform vec2 u_scale;    // cover-crop + overscan window size, in texture units
uniform vec2 u_center;   // window centre: the subject's focal point, kept inside the texture
uniform vec3 u_factors;  // per-layer slide: far, mid, near
// Optional extra cut-out between mid and near, with its own slide (already turned on the CPU).
uniform sampler2D u_extra;
uniform vec2 u_extraOffset;
uniform float u_hasExtra;

void main() {
    vec2 base = u_center + (v_texCoord - 0.5) * u_scale;
    vec3 c = texture(u_far, clamp(base + u_offset * u_factors.x, 0.0, 1.0)).rgb;
    vec4 m = texture(u_mid, clamp(base + u_offset * u_factors.y, 0.0, 1.0));
    c = m.rgb + c * (1.0 - m.a);
    vec4 e = texture(u_extra, clamp(base + u_extraOffset, 0.0, 1.0)) * u_hasExtra;
    c = e.rgb + c * (1.0 - e.a);
    vec4 n = texture(u_near, clamp(base + u_offset * u_factors.z, 0.0, 1.0));
    c = n.rgb + c * (1.0 - n.a);
    fragColor = vec4(c, 1.0);
}
