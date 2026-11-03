#version 300 es
// The character (premultiplied alpha), or its soft shadow on the water when u_shadow is 1.
precision mediump float;
in vec2 v_texCoord;
out vec4 fragColor;
uniform sampler2D u_tex;
uniform float u_shadow;
uniform vec2 u_texel;    // blur step for the shadow
void main() {
    if (u_shadow > 0.5) {
        float a = 0.0;
        for (int i = -2; i <= 2; i++) {
            for (int j = -2; j <= 2; j++) {
                a += texture(u_tex, v_texCoord + vec2(float(i), float(j)) * u_texel).a;
            }
        }
        a /= 25.0;
        fragColor = vec4(0.0, 0.02, 0.06, 1.0) * a * 0.32;
    } else {
        fragColor = texture(u_tex, v_texCoord);
    }
}
