#version 300 es
precision highp float;
precision highp int;

// GLES 3.0 port of AMD FidelityFX FSR 1.0 EASU (single-channel 32-bit path).
// Neighborhood loads replace Vulkan textureGather callbacks; direction, adaptive
// Lanczos-like taps, and the 2x2 anti-ringing clamp follow the upstream algorithm.
uniform sampler2D uInput;
uniform vec2 uInputSize;
uniform vec2 uOutputSize;
out vec4 fragColor;

vec3 loadInput(ivec2 p) {
    ivec2 limit = ivec2(uInputSize) - ivec2(1);
    return texelFetch(uInput, clamp(p, ivec2(0), limit), 0).rgb;
}

float luma2(vec3 c) {
    return c.b * 0.5 + (c.r * 0.5 + c.g);
}

void easuSet(
    inout vec2 dir,
    inout float len,
    float weight,
    float lA,
    float lB,
    float lC,
    float lD,
    float lE
) {
    float dc = lD - lC;
    float cb = lC - lB;
    float lenX = max(abs(dc), abs(cb));
    float dirX = lD - lB;
    dir.x += dirX * weight;
    lenX = clamp(abs(dirX) / max(lenX, 1.0e-6), 0.0, 1.0);
    len += lenX * lenX * weight;

    float ec = lE - lC;
    float ca = lC - lA;
    float lenY = max(abs(ec), abs(ca));
    float dirY = lE - lA;
    dir.y += dirY * weight;
    lenY = clamp(abs(dirY) / max(lenY, 1.0e-6), 0.0, 1.0);
    len += lenY * lenY * weight;
}

void easuTap(
    inout vec3 accumColor,
    inout float accumWeight,
    vec2 offset,
    vec2 dir,
    vec2 len,
    float lob,
    float clipPoint,
    vec3 color
) {
    vec2 v = vec2(
        offset.x * dir.x + offset.y * dir.y,
        offset.x * -dir.y + offset.y * dir.x
    );
    v *= len;
    float d2 = min(dot(v, v), clipPoint);
    float wB = 0.4 * d2 - 1.0;
    float wA = lob * d2 - 1.0;
    wB *= wB;
    wA *= wA;
    wB = 1.5625 * wB - 0.5625;
    float weight = wB * wA;
    accumColor += color * weight;
    accumWeight += weight;
}

void main() {
    vec2 scale = uInputSize / uOutputSize;
    vec2 pp = gl_FragCoord.xy * scale - 0.5;

    // Android Bitmap uploads put row zero at GL texture y=0. Flip the source
    // coordinate so the chart remains upright in the normal bottom-left GL space.
    pp.y = (uInputSize.y - 1.0) - pp.y;
    ivec2 fp = ivec2(floor(pp));
    pp -= floor(pp);

    vec3 b = loadInput(fp + ivec2( 0, -1));
    vec3 c = loadInput(fp + ivec2( 1, -1));
    vec3 e = loadInput(fp + ivec2(-1,  0));
    vec3 f = loadInput(fp + ivec2( 0,  0));
    vec3 g = loadInput(fp + ivec2( 1,  0));
    vec3 h = loadInput(fp + ivec2( 2,  0));
    vec3 i = loadInput(fp + ivec2(-1,  1));
    vec3 j = loadInput(fp + ivec2( 0,  1));
    vec3 k = loadInput(fp + ivec2( 1,  1));
    vec3 l = loadInput(fp + ivec2( 2,  1));
    vec3 n = loadInput(fp + ivec2( 0,  2));
    vec3 o = loadInput(fp + ivec2( 1,  2));

    float bL = luma2(b);
    float cL = luma2(c);
    float eL = luma2(e);
    float fL = luma2(f);
    float gL = luma2(g);
    float hL = luma2(h);
    float iL = luma2(i);
    float jL = luma2(j);
    float kL = luma2(k);
    float lL = luma2(l);
    float nL = luma2(n);
    float oL = luma2(o);

    vec2 dir = vec2(0.0);
    float len = 0.0;
    easuSet(dir, len, (1.0 - pp.x) * (1.0 - pp.y), bL, eL, fL, gL, jL);
    easuSet(dir, len,          pp.x  * (1.0 - pp.y), cL, fL, gL, hL, kL);
    easuSet(dir, len, (1.0 - pp.x) *          pp.y, fL, iL, jL, kL, nL);
    easuSet(dir, len,          pp.x  *          pp.y, gL, jL, kL, lL, oL);

    float dirR = dot(dir, dir);
    bool zeroDirection = dirR < (1.0 / 32768.0);
    if (zeroDirection) {
        dir = vec2(1.0, 0.0);
    } else {
        dir *= inversesqrt(dirR);
    }

    len *= 0.5;
    len *= len;
    float stretch = dot(dir, dir) / max(max(abs(dir.x), abs(dir.y)), 1.0e-6);
    vec2 len2 = vec2(1.0 + (stretch - 1.0) * len, 1.0 - 0.5 * len);
    float lob = 0.5 + (0.25 - 0.04 - 0.5) * len;
    float clipPoint = 1.0 / max(lob, 1.0e-6);

    vec3 min4 = min(min(f, g), min(j, k));
    vec3 max4 = max(max(f, g), max(j, k));
    vec3 accumColor = vec3(0.0);
    float accumWeight = 0.0;
    easuTap(accumColor, accumWeight, vec2( 0.0, -1.0) - pp, dir, len2, lob, clipPoint, b);
    easuTap(accumColor, accumWeight, vec2( 1.0, -1.0) - pp, dir, len2, lob, clipPoint, c);
    easuTap(accumColor, accumWeight, vec2(-1.0,  1.0) - pp, dir, len2, lob, clipPoint, i);
    easuTap(accumColor, accumWeight, vec2( 0.0,  1.0) - pp, dir, len2, lob, clipPoint, j);
    easuTap(accumColor, accumWeight, vec2( 0.0,  0.0) - pp, dir, len2, lob, clipPoint, f);
    easuTap(accumColor, accumWeight, vec2(-1.0,  0.0) - pp, dir, len2, lob, clipPoint, e);
    easuTap(accumColor, accumWeight, vec2( 1.0,  1.0) - pp, dir, len2, lob, clipPoint, k);
    easuTap(accumColor, accumWeight, vec2( 2.0,  1.0) - pp, dir, len2, lob, clipPoint, l);
    easuTap(accumColor, accumWeight, vec2( 2.0,  0.0) - pp, dir, len2, lob, clipPoint, h);
    easuTap(accumColor, accumWeight, vec2( 1.0,  0.0) - pp, dir, len2, lob, clipPoint, g);
    easuTap(accumColor, accumWeight, vec2( 1.0,  2.0) - pp, dir, len2, lob, clipPoint, o);
    easuTap(accumColor, accumWeight, vec2( 0.0,  2.0) - pp, dir, len2, lob, clipPoint, n);

    vec3 upscaled = clamp(accumColor / max(accumWeight, 1.0e-6), min4, max4);
    fragColor = vec4(upscaled, 1.0);
}
