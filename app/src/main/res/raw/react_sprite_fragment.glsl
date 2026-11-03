#version 300 es
// The character (premultiplied alpha); its soft shadow on the water when u_shadow is 1; or, when
// u_shadow is 2, a wider glowing halo in u_glow (drawn under the character, so only the rim shows).
precision mediump float;
in vec2 v_texCoord;
out vec4 fragColor;
uniform sampler2D u_tex;
uniform float u_shadow;
uniform vec2 u_texel;    // blur step for the shadow
uniform vec3 u_glow;
uniform float u_pulse;

// The picture's alpha, and nothing outside it (the shadow quad is grown past the picture).
float alphaAt(vec2 uv) {
    vec2 inside = step(vec2(0.0), uv) * step(uv, vec2(1.0));
    return texture(u_tex, clamp(uv, 0.0, 1.0)).a * inside.x * inside.y;
}
void main() {
    if (u_shadow > 0.5) {
        bool glow = u_shadow > 1.5;
        vec2 stepUv = glow ? u_texel * 2.2 : u_texel;
        float a = 0.0;
        for (int i = -2; i <= 2; i++) {
            for (int j = -2; j <= 2; j++) {
                a += alphaAt(v_texCoord + vec2(float(i), float(j)) * stepUv);
            }
        }
        a /= 25.0;
        fragColor = glow ? vec4(u_glow, 1.0) * min(1.0, a * 1.6) * 0.85 * u_pulse
                         : vec4(0.0, 0.02, 0.06, 1.0) * a * 0.32;
    } else {
        fragColor = texture(u_tex, v_texCoord);
    }
}
