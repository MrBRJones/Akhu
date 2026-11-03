#version 300 es
// Places the character quad: a_position is the unit square (0..1), turned into screen pixels by
// u_matrix (2x2 linear part) and u_offset, then into clip space.
layout(location = 0) in vec2 a_position;
uniform vec4 u_matrix;   // m00, m01, m10, m11
uniform vec2 u_offset;   // pixels, y down
uniform vec2 u_res;
out vec2 v_texCoord;
void main() {
    vec2 px = vec2(u_matrix.x * a_position.x + u_matrix.y * a_position.y,
                   u_matrix.z * a_position.x + u_matrix.w * a_position.y) + u_offset;
    gl_Position = vec4(px.x / u_res.x * 2.0 - 1.0, 1.0 - px.y / u_res.y * 2.0, 0.0, 1.0);
    v_texCoord = a_position;
}
