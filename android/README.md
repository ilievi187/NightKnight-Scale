# NightKnight Scale TV (prototype)

This Android application is part of the existing NightKnight-Scale repository. It is a fullscreen foreground demo for Android TV / Google TV, not a second product and not a transparent system-wide picture filter.

## Current implementation

- Leanback launcher entry and touchscreen-optional declaration.
- OpenGL ES 3.0 capability requirement and GPU renderer/version/max-texture detection.
- Generated half-resolution synthetic detail chart sized to keep the demo upscale at approximately 2× per axis (4× image area).
- Four packaged, deterministic 6-second H.264 test clips at 640×360, 960×540, 1280×720 and 1920×1080, encoded as YUV420p at 30 fps. The selected clip is approximately half of the current processed target per axis.
- Optional SAF `ACTION_OPEN_DOCUMENT` picker for local video, with Android TV fallback to the system photo picker when the OS resolves the document action to its placeholder stub. Read grants are persisted only when the provider offers persistable access; the fallback picker URI is session-scoped.
- Actual decoder-reported source dimensions update GLES textures on the GL thread. EASU multi-pass upscaling keeps each stage within 2× per axis and targets the largest aspect-preserving size permitted by the display, 4096/GLES edge limit and a 128 MiB soft estimated RGBA render-target budget. Upscaled outputs are reduced when necessary; if decoded source allocations alone exceed the budget, the diagnostics dialog flags that irreducible condition.
- Four named D-pad presets: OFF → bilinear; QUALITY → edge-aware NR + EASU + RCAS; BALANCED → EASU + RCAS; PERFORMANCE → EASU only. Each preset maps to a distinct real render path.
- Preset and RCAS sharpness are saved with app preferences and restored at launch.
- D-pad diagnostics dialog shows source/output/surface size, aspect-fit viewport, EASU stage count, RGBA target-memory estimate, renderer, input callback cadence, render cadence, frame intervals, render submit time, frame callback/upload counts, estimated callback coalescing and decoder lag events.

The bundled clips are synthetic test-pattern video, not television/IPTV content. The player currently uses framework `MediaPlayer` with an app-owned external OES `SurfaceTexture`; the renderer copies decoded frames into RGBA textures before the filter passes. A user-selected clip is opened with the OS media picker, using SAF persistable grants when available and Photo Picker fallback when Android TV exposes only a document-action stub. The GL path uses EASU/RCAS plus an optional mild 3×3 spatial NR filter. Callback/drop estimates are renderer-side and are not exact hardware decoder/display counters. Color/HDR fidelity and rotation metadata still need on-device visual validation. This remains a foreground preview, not a cross-app enhancer; no MediaProjection capture, HDMI input, tuner, DRM bypass, codec-aware restoration, or deinterlacing is implemented.

## Build

Requirements: JDK 17, Gradle 8.13, Android SDK Platform 36 and Android SDK Build Tools 35 or newer. From this directory:

```sh
gradle testDebugUnitTest assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`. No APK is checked into the Git repository. PR CI runs Android JVM tests, builds the APK, installs it on an API 36 Android TV emulator, and uploads both APK and smoke evidence as seven-day artifacts. CI/SwiftShader results are emulator-only; they do not establish TCL decoder/GPU performance or visual quality. Use the TCL checklist in [`../docs/tcl-55c645-test-checklist.md`](../docs/tcl-55c645-test-checklist.md) for the first physical-device run.

The packaged clips are still used as the deterministic rendering regression. `android/scripts/tv-emulator-smoke.sh` also drives the four named profiles, checks persisted settings across process restart, and invokes `tv-local-media-smoke.sh` to select locally indexed videos via the Android TV Photo Picker fallback. The local smoke verifies 25/30/50/60 fps H.264 inputs, 576p and portrait aspect shapes, decoder-reported dimensions, moving output, and multi-pass counts where expected. Difference checks are regression signals, not perceptual-quality scores. No physical TV test has been run.

## Measurement scope

- **Display mode** is obtained from the Android default display mode API.
- **GL surface** is the dimensions passed to `GLSurfaceView.Renderer.onSurfaceChanged`.
- **Processed target** fits source aspect ratio inside display and GLES texture limits, up to a prototype cap of 4096 pixels per edge and a 128 MiB soft RGBA8 pipeline render-target budget. For upscaling targets, the policy reduces target size if its estimate exceeds that budget; decoded source allocations are irreducible and can still exceed it. Estimate includes input, NR, output, optional RCAS and reusable EASU intermediate textures; it excludes decoder/OES surfaces, driver alignment, UI and compositor memory, so it is not total process GPU memory.
- **Render FPS/frame interval** report `onDrawFrame` cadence/intervals, not physical-display presentation timing.
- **OES upload cadence** is the rate at which `SurfaceTexture` frames were consumed/copied by the GL thread, not the nominal source FPS or exact hardware decoder output rate.
- **Estimated skipped callbacks** counts multiple frame-available signals coalesced before a GL update; it is not an exact decoder drop counter.
- **CPU GL-submit time** is measured before GPU completion; the sparse submit-to-`glFinish` sample measures CPU time until the GPU queue synchronizes, not pure GPU duration or presentation latency.

## Shader provenance

The EASU and RCAS shaders are GLSL ES 3.0 ports based on AMD's FidelityFX FSR 1.0 32-bit shader algorithm. The EASU pass uses explicit `texelFetch` neighborhood loads in place of the upstream Vulkan GLSL `textureGather` callbacks. The RCAS pass uses texel fetches from its 3×3 neighborhood. See [`../third_party/AMD-FSR-LICENSE.txt`](../third_party/AMD-FSR-LICENSE.txt). The port has passed standalone GLSL ES compilation with `glslangValidator`, but its output has not yet been run on Android hardware or compared against the AMD reference sample.

## Compatibility and limitations

See [`../docs/android-tv-compatibility.md`](../docs/android-tv-compatibility.md) for the source-by-source Android TV/EON/HDMI/MediaProjection boundary, cited platform limitations, and explicit next release gates. In short: this app processes its own bundled or user-selected local video only; it is not a system-wide enhancer, does not directly intercept EON/other apps/HDMI, and does not bypass DRM. The player remains framework MediaPlayer, not Media3; Android TV Photo Picker fallback is validated in emulator smoke only and must be checked on the TCL.
