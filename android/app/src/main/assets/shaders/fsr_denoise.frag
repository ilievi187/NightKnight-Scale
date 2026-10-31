#version 300 es
precision highp float;
precision highp int;

// Optional, mild 3x3 bilateral spatial smoothing before EASU.
// This is a simple experimental NR prefilter, not a temporal or codec-aware filter.
uniform sampler2D uInput;
uniform float uStrength;
out vec4 fragColor;

vec3 loadInput(ivec2 p, ivec2 size) {
    return texelFetch(uInput, clamp(p, ivec2(0), size - ivec2(1)), 0).rgb;
}

float luma2(vec3 c) {
    return c.b * 0.5 + (c.r * 0.5 + c.g);
}

void addSample(
    inout vec3 colorSum,
    inout float weightSum,
    vec3 center,
    vec3 sampleColor,
    float spatialWeight
) {
    float rangeDistance = abs(luma2(center) - luma2(sampleColor));
    float rangeWeight = exp(-rangeDistance * 10.0);
    float weight = spatialWeight * rangeWeight;
    colorSum += sampleColor * weight;
    weightSum += weight;
}

void main() {
    ivec2 size = textureSize(uInput, 0);
    ivec2 p = ivec2(gl_FragCoord.xy);
    vec3 center = loadInput(p, size);
    vec3 colorSum = vec3(0.0);
    float weightSum = 0.0;

    addSample(colorSum, weightSum, center, loadInput(p + ivec2(-1, -1), size), 1.0);
    addSample(colorSum, weightSum, center, loadInput(p + ivec2( 0, -1), size), 2.0);
    addSample(colorSum, weightSum, center, loadInput(p + ivec2( 1, -1), size), 1.0);
    addSample(colorSum, weightSum, center, loadInput(p + ivec2(-1,  0), size), 2.0);
    addSample(colorSum, weightSum, center, center, 4.0);
    addSample(colorSum, weightSum, center, loadInput(p + ivec2( 1,  0), size), 2.0);
    addSample(colorSum, weightSum, center, loadInput(p + ivec2(-1,  1), size), 1.0);
    addSample(colorSum, weightSum, center, loadInput(p + ivec2( 0,  1), size), 2.0);
    addSample(colorSum, weightSum, center, loadInput(p + ivec2( 1,  1), size), 1.0);

    vec3 smoothed = colorSum / max(weightSum, 1.0e-6);
    vec3 result = mix(center, smoothed, clamp(uStrength, 0.0, 0.35));
    fragColor = vec4(clamp(result, 0.0, 1.0), 1.0);
}
