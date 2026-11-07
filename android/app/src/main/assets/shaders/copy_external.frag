#version 100
#extension GL_OES_EGL_image_external : require
precision highp float;

uniform samplerExternalOES uInput;
uniform mat4 uTextureTransform;
varying vec2 vTexCoord;

void main() {
    vec2 uv = (uTextureTransform * vec4(vTexCoord, 0.0, 1.0)).xy;
    gl_FragColor = texture2D(uInput, uv);
}
