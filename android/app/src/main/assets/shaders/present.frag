#version 300 es
precision highp float;

uniform sampler2D uInput;
uniform int uFlipY;
in vec2 vTexCoord;
out vec4 fragColor;

void main() {
    vec2 uv = vTexCoord;
    if (uFlipY != 0) {
        uv.y = 1.0 - uv.y;
    }
    fragColor = texture(uInput, uv);
}
