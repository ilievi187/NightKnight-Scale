# NightKnight Scale TV (prototype)

This Android application is part of the existing NightKnight-Scale repository. It is a fullscreen foreground demo for Android TV / Google TV, not a second product and not a transparent system-wide picture filter.

## Current implementation

- Leanback launcher entry and touchscreen-optional declaration.
- OpenGL ES 3.0 capability requirement and GPU renderer/version/max-texture detection.
- Generated half-resolution synthetic detail chart sized to keep the demo upscale at approximately 2× per axis (4× image area).
- Four packaged, deterministic 6-second H.264 test clips at 640×360, 960×540, 1280×720 and 1920×1080, encoded as YUV420p at 30 fps. The selected clip is approximately half of the current processed target per axis.
- Optional SAF `ACTION_OPEN_DOCUMENT` picker for local video, with Android TV fallback to the system photo picker when the OS resolves the document action to its placeholder stub. Read grants are persisted only when the provider offers persistable access; the fallback picker URI is session-scoped.
- Actual decoder-reported source dimensions update GLES textures on the GL thread. EASU multi-pass upscaling keeps each stage within 2× per axis and targets the largest aspect-preserving size allowed by the display, 4096/GLES edge limit, and a 128 MiB soft RGBA render-target budget.
- GPU allocations are mode-specific: OFF keeps only the source texture; PERFORMANCE adds the EASU output and exact-size ping-pong stages; BALANCED adds RCAS; QUALITY lazily adds its NR target. RCAS writes directly into the aspect-fit default-framebuffer viewport when that viewport matches the EASU target; otherwise it uses a same-size scratch target.
- Preset and RCAS sharpness are saved with app preferences and restored at launch.
- D-pad diagnostics show source/output/surface geometry, effective processing mode and explicit allocation/shader fallbacks, EASU stage count, mode-specific RGBA target estimate, renderer/GLES limits, nominal source FPS when known, SurfaceTexture callback cadence, consumed media-timestamp cadence, renderer callback cadence, callback-coalescing estimates, decoder lag, CPU render/GL-submit duration, asynchronous GPU timer-query samples when supported, and unavailable physical-display presentation timing.

The bundled clips are synthetic test-pattern video, not television/IPTV content. The player currently uses framework `MediaPlayer` with an app-owned external OES `SurfaceTexture`; normal filtering stays on the RGBA-copy reference route. A direct OES→EASU path is not enabled merely because its shader compiles: it remains disabled until an explicit debug-only pixel comparison passes for the current source/geometry, and a mismatch or unavailable program leaves the RGBA-copy fallback active. OFF may present the decoded OES texture directly because it does not run FSR. If the RGBA source/FBO cannot be allocated, the renderer reports the effective fallback; if a later upload fails while the RGBA input remains valid, the generated chart remains available as the processing input. A user-selected clip is opened with the OS media picker, using SAF persistable grants when available and Photo Picker fallback when Android TV exposes only a document-action stub. The GL path uses EASU/RCAS plus an optional mild 3×3 spatial NR filter. Callback/coalescing estimates are renderer-side, not exact hardware decoder/display counters. Color/HDR fidelity and rotation metadata still need on-device visual validation. This remains a foreground preview, not a cross-app enhancer; no MediaProjection capture, HDMI input, tuner, DRM bypass, codec-aware restoration, or deinterlacing is implemented.

## Build

Requirements: JDK 17, Gradle 8.13, Android SDK Platform 36 and Android SDK Build Tools 35 or newer. From this directory:

```sh
gradle testDebugUnitTest assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`. No APK is checked into the Git repository. PR CI runs Android JVM tests, builds the APK, installs it on an API 36 Android TV emulator, and uploads both APK and smoke evidence as seven-day artifacts. CI/SwiftShader results are emulator-only; they do not establish TCL decoder/GPU performance or visual quality. Use the TCL checklist in [`../docs/tcl-55c645-test-checklist.md`](../docs/tcl-55c645-test-checklist.md) for the first physical-device run.

The packaged clips are still used as the deterministic rendering regression. `android/scripts/tv-emulator-smoke.sh` also drives the four named profiles, records active-decoder per-mode CPU/renderer samples to `performance-samples-active.logcat.txt`, checks persisted settings across process restart, and invokes `tv-local-media-smoke.sh` to select locally indexed videos via the Android TV Photo Picker fallback. A debug-only first-frame EASU check compares direct-OES output against the RGBA-copy reference at pixel level; only a passing comparison enables direct input for that debug session, while a mismatch is recorded and retains the RGBA-copy route. The smoke always preserves logcat, including failed equivalence details. The local smoke verifies 25/30/50/60 fps H.264 inputs, 576p and portrait aspect shapes, decoder-reported dimensions, moving output, and multi-pass counts where expected. These are emulator regression signals, not physical PowerVR benchmarks or comparisons against AMD's reference corpus.

## Measurement scope

- **Display mode** is obtained from the Android default display mode API.
- **GL surface** is the dimensions passed to `GLSurfaceView.Renderer.onSurfaceChanged`.
- **Processed target** fits the source display aspect ratio inside the surface/GLES limits (up to 4096 pixels per edge). The 128 MiB soft RGBA estimate is selected-mode-specific: source texture, optional NR target, EASU output/intermediate allocations, and RCAS scratch only when the fitted viewport differs from the processing target. Decoder/OES surfaces, driver alignment, UI and compositor memory are excluded. If a texture/FBO allocation fails, the renderer reports the requested/effective path and falls back to bilinear RGBA input or direct external-OES presentation where possible.
- **Nominal source FPS** is shown for the packaged 30 fps clips and for local tracks only when the extracted track metadata includes a positive frame-rate value. Otherwise it is N/A; callback cadence is never used as a proxy for nominal source FPS.
- **SurfaceTexture callback cadence** counts main-looper frame-available callbacks; **consumed media-timestamp cadence** measures the timestamps of frames actually consumed by the GL thread. Neither is a hardware decoder output counter.
- **Renderer callback cadence/frame intervals** report `onDrawFrame` callback timing, not physical-display presentation FPS. Estimated skipped callbacks count coalesced frame-available signals, not exact decoder/display drops.
- **CPU render/GL-submit duration** is CPU wall time around `onDrawFrame` and individual GLES stages, measured separately by requested mode over two-second windows; it can include stalls inside driver calls, is not GPU execution time, and excludes the EGL swap. **GPU execution time** is sampled asynchronously every 120 renderer callbacks only when `GL_EXT_disjoint_timer_query` is available; disjoint samples are discarded. **Display presentation timing** remains N/A because this GLSurfaceView path does not expose a physical scanout timestamp. Production telemetry uses neither `glFinish()` nor `glReadPixels()`.
- **Automatic mode fallback** uses only known positive nominal source FPS and two consecutive renderer-callback interval windows exceeding the source-frame budget by 10%. It steps down one processing stage and reports requested/effective modes; this is a cadence safeguard, not proof of GPU limitation or display FPS.

## Shader provenance

The EASU and RCAS shaders are GLSL ES 3.0 ports based on AMD's FidelityFX FSR 1.0 32-bit shader algorithm. The RGBA reference path uses explicit `texelFetch` neighborhood loads in place of the upstream Vulkan GLSL `textureGather` callbacks. When the OES ESSL3 extension is available, the optional EASU direct-input variant changes only source sampling and shares the same EASU equations; it remains disabled until the opt-in debug-only pixel comparison passes for the active source/geometry. A failed comparison is reported with MAE, per-channel error, max-error location and PSNR, then retains the RGBA-copy route. The RCAS pass uses texel fetches from its 3×3 neighborhood. See [`../third_party/AMD-FSR-LICENSE.txt`](../third_party/AMD-FSR-LICENSE.txt). Both shader variants pass standalone GLSL ES compilation and the Android TV emulator smoke checks runtime program compilation, but this does not establish equivalence on the physical PowerVR BXE-4-32 or perceptual equivalence to AMD's reference corpus.

## Compatibility and limitations

See [`../docs/android-tv-compatibility.md`](../docs/android-tv-compatibility.md) for the source-by-source Android TV/EON/HDMI/MediaProjection boundary, cited platform limitations, and explicit next release gates. In short: this app processes its own bundled or user-selected local video only; it is not a system-wide enhancer, does not directly intercept EON/other apps/HDMI, and does not bypass DRM. The player remains framework MediaPlayer, not Media3; Android TV Photo Picker fallback is validated in emulator smoke only and must be checked on the TCL.
