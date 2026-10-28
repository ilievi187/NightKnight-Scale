# NightKnight Scale TV (prototype)

This Android application is part of the existing NightKnight-Scale repository. It is a fullscreen foreground demo for Android TV / Google TV, not a second product and not a transparent system-wide picture filter.

## Current implementation

- Leanback launcher entry and touchscreen-optional declaration.
- OpenGL ES 3.0 capability requirement and GPU renderer/version/max-texture detection.
- Generated half-resolution synthetic detail chart sized to keep the demo upscale at approximately 2× per axis (4× image area).
- A GLSL ES port of AMD FidelityFX FSR 1.0's 32-bit EASU and RCAS algorithms, executed in separate GPU fragment passes.
- A bilinear original path for A/B comparison.
- D-pad-focusable output controls and a LEFT/RIGHT-adjustable RCAS sharpness slider.
- Display-mode, app GL-surface, actual processed-target, render-callback FPS and sparse synchronized render-completion diagnostics.

The generated chart is a synthetic still image, not a decoded video source. The UI has no MediaProjection, VirtualDisplay, foreground capture service, cross-app processing, noise reduction, compression-artifact reduction, or deinterlacing. Exiting or covering this activity does not enhance other apps. No DRM or protected-content bypass is implemented or permitted.

## Build

Requirements: JDK 17, Gradle 8.13, Android SDK Platform 36 and Android SDK Build Tools 35 or newer. From this directory:

```sh
gradle testDebugUnitTest assembleDebug
```

The debug APK, if the build succeeds, is written to `app/build/outputs/apk/debug/app-debug.apk`. No APK is checked into the repository. On the initial development host used for this prototype, Gradle, Android SDK, SDK tools and Android devices/emulators were not available, so source implementation is not an APK-build or device-validation claim.

## Measurement scope

- **Display mode** is obtained from the Android default display mode API.
- **GL surface** is the dimensions passed to `GLSurfaceView.Renderer.onSurfaceChanged`.
- **Processed target** is the intermediate output size after respecting a 4096-pixel prototype cap and the GLES maximum texture size. If that differs from the GL surface, the processed image is bilinearly presented to fill the surface.
- **Render FPS** is the observed `onDrawFrame` callback rate, not an independently measured physical-display presentation rate.
- **Submit→GPU-finish sample** measures the elapsed CPU time from the start of a sampled draw callback until `glFinish()` returns. It is sampled once every 120 callbacks to avoid serializing every frame. It may include previously queued GPU work and is not a pure GPU timestamp or end-to-end presentation latency.

## Shader provenance

The EASU and RCAS shaders are GLSL ES 3.0 ports based on AMD's FidelityFX FSR 1.0 32-bit shader algorithm. The EASU pass uses explicit `texelFetch` neighborhood loads in place of the upstream Vulkan GLSL `textureGather` callbacks. The RCAS pass uses texel fetches from its 3×3 neighborhood. See [`../third_party/AMD-FSR-LICENSE.txt`](../third_party/AMD-FSR-LICENSE.txt). The port has passed standalone GLSL ES compilation with `glslangValidator`, but its output has not yet been run on Android hardware or compared against the AMD reference sample.

## Next gate

Build and run this app on an Android TV emulator/device. Confirm shader linking, upright image output, remote navigation, original/enhanced differences, and the reported metrics before adding a decoder/capture path. A MediaProjection capture, if implemented later, must use the system consent flow and display a separate processed preview; it cannot be described as replacing the source app's pixels.
