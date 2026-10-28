#version 300 es
precision highp float;
precision highp int;

// GLES 3.0 port of AMD FidelityFX FSR 1.0 RCAS, 32-bit path.
uniform sampler2D uInput;
uniform float uSharpnessScale;
out vec4 fragColor;

vec3 loadPixel(ivec2 p, ivec2 size) {
    return texelFetch(uInput, clamp(p, ivec2(0), size - ivec2(1)), 0).rgb;
}

float safeReciprocal(float value) {
    float signValue = value < 0.0 ? -1.0 : 1.0;
    return 1.0 / (signValue * max(abs(value), 1.0e-6));
}

void main() {
    ivec2 size = textureSize(uInput, 0);
    ivec2 p = ivec2(gl_FragCoord.xy);
    vec3 b = loadPixel(p + ivec2( 0, -1), size);
    vec3 d = loadPixel(p + ivec2(-1,  0), size);
    vec3 e = loadPixel(p, size);
    vec3 f = loadPixel(p + ivec2( 1,  0), size);
    vec3 h = loadPixel(p + ivec2( 0,  1), size);

    vec3 min4 = min(min(b, d), min(f, h));
    vec3 max4 = max(max(b, d), max(f, h));
    vec3 hitMin = min(min4, e) / max(4.0 * max4, vec3(1.0e-6));
    vec3 denomMax = 4.0 * min4 - vec3(4.0);
    vec3 hitMax = (vec3(1.0) - max(max4, e)) * vec3(
        safeReciprocal(denomMax.r),
        safeReciprocal(denomMax.g),
        safeReciprocal(denomMax.b)
    );

    vec3 lobeByChannel = max(-hitMin, hitMax);
    float lobe = max(-0.1875, min(max(max(lobeByChannel.r, lobeByChannel.g), lobeByChannel.b), 0.0));
    lobe *= uSharpnessScale;

    // Matches FSR RCAS's 5-tap cross resolve. This prototype uses the standard
    // path without the optional RCAS denoise permutation.
    float rcpL = 1.0 / (4.0 * lobe + 1.0);
    vec3 result = (lobe * (b + d + f + h) + e) * rcpL;
    fragColor = vec4(clamp(result, 0.0, 1.0), 1.0);
}
