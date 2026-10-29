# NightKnight Scale TV (prototype)

This Android application is part of the existing NightKnight-Scale repository. It is a fullscreen foreground demo for Android TV / Google TV, not a second product and not a transparent system-wide picture filter.

## Current implementation

- Leanback launcher entry and touchscreen-optional declaration.
- OpenGL ES 3.0 capability requirement and GPU renderer/version/max-texture detection.
- Generated half-resolution synthetic detail chart sized to keep the demo upscale at approximately 2× per axis (4× image area).
- Four packaged, deterministic 6-second H.264 test clips at 640×360, 960×540, 1280×720 and 1920×1080, encoded as YUV420p at 30 fps. The selected clip is approximately half of the current processed target per axis.
- Android MediaPlayer decoding into a `SurfaceTexture` external OES texture. A GLES copy pass writes each decoded frame into an RGBA 2D texture before the existing EASU and RCAS passes.
- A GLSL ES port of AMD FidelityFX FSR 1.0's 32-bit EASU and RCAS algorithms, executed in separate GPU fragment passes.
- Four D-pad-selectable comparison paths: bilinear original, EASU only, FSR 1.0 EASU+RCAS, and FSR 1.0 with an optional mild spatial noise-reduction prefilter.
- D-pad-focusable output controls and a LEFT/RIGHT-adjustable RCAS sharpness slider.
- Display-mode, app GL-surface, actual processed-target, render-callback FPS and sparse synchronized render-completion diagnostics.

The packaged clips are synthetic test-pattern video, not television or IPTV content. Android MediaPlayer decodes them into an app-owned SurfaceTexture. The GLES renderer copies each external OES frame to a 2D texture before the selected filter passes. The optional NR mode is a mild 3×3 edge-aware spatial prefilter, not temporal or codec-aware noise/artifact reduction. A generated chart remains as a fallback if video surface setup or decoding fails. Emulator CI verified playback, changing frames, all four output-mode transitions, and pairwise pixel differences on the same paused frame. This uses Google SwiftShader software GLES and does not prove target-TV hardware decoder/GPU behavior or perceptual image improvement. The UI has no MediaProjection, VirtualDisplay, foreground capture service, or cross-app processing. Compression-artifact reduction and deinterlacing are not implemented. Exiting or covering this activity does not enhance other apps. No DRM or protected-content bypass is implemented or permitted.

## Build

Requirements: JDK 17, Gradle 8.13, Android SDK Platform 36 and Android SDK Build Tools 35 or newer. From this directory:

```sh
gradle testDebugUnitTest assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`. No APK is checked into the Git repository. GitHub PR CI builds this APK, runs the JVM tests, prints the APK SHA-256, and uploads it as a seven-day artifact. CI run `40832233004` passed Linux format/lint/tests, Android build/JVM tests, and Android TV emulator GLES/remote smoke. Its API 36 Android TV 1080p AVD with Google SwiftShader GLES 3.0 decoded the 960×540 H.264 clip, uploaded SurfaceTexture/OES frames, and processed them at 1920×1080. Remote input exercised pause, all four filter modes, and sharpness 8→9; captured paused-frame crops differed between bilinear, EASU-only, FSR1, and FSR1+NR. Render callback FPS was 3.5586345. A sparse Enhanced `glFinish()` completion sample was 142.5533 ms, and Bilinear's later sample was 37.44116 ms. These are software-emulator observations, not physical-TV/target-GPU performance, display-present FPS, or evidence of perceptual quality. The downloaded APK is `/home/nightknight/.jcode/scratch/nkscale-final-evidence-40832233004/apk/app-debug.apk`, SHA-256 `0378af518c80c6c99b40dcd85426d894c4dd56bd12ad7c186487dece7d366ed7`; `file` identified it as an Android package and `unzip -t` passed. The local host still lacks Gradle/SDK tools.

To regenerate the test clips after intentionally removing the existing assets, run `bash scripts/generate-test-videos.sh`. The script refuses to overwrite any existing clip. Each asset has been fully decoded locally with FFmpeg. MediaPlayer/SurfaceTexture video decode, changing-frame upload, pause, all four mode transitions, and same-frame pixel-difference assertions were verified in a Google SwiftShader Android TV emulator. On the paused 240×1080 crop, bilinear differed from EASU-only at 10,341/259,200 pixels (3.9896%), FSR1 at 10,342/259,200 (3.9900%), and FSR1+NR at 10,503/259,200 (4.0521%). EASU-only vs FSR1 differed at 473/259,200 pixels (0.1825%), FSR1 vs FSR1+NR at 7,831/259,200 (3.0212%), and FSR1 sharpness 8 vs 9 at 125/259,200 (0.0482%). Two moving captures one second apart differed at 40,590/259,200 pixels (15.6597%). These are pixel-difference checks on synthetic test imagery, not perceptual quality scores. A physical TV test remains required for decoder/GPU/performance support claims.

## Measurement scope

- **Display mode** is obtained from the Android default display mode API.
- **GL surface** is the dimensions passed to `GLSurfaceView.Renderer.onSurfaceChanged`.
- **Processed target** is the intermediate output size after respecting a 4096-pixel prototype cap and the GLES maximum texture size. If that differs from the GL surface, the processed image is bilinearly presented to fill the surface.
- **Render FPS** is the observed `onDrawFrame` callback rate, not an independently measured physical-display presentation rate.
- **Decoded video frames** counts frames copied from the `SurfaceTexture` into the app's RGBA input texture. It is not the source decoder's requested FPS or a guaranteed display-present rate.
- **Submit→GPU-finish sample** measures the elapsed CPU time from the start of a sampled draw callback until `glFinish()` returns. It is sampled once every 120 callbacks to avoid serializing every frame. It may include previously queued GPU work and is not a pure GPU timestamp or end-to-end presentation latency.

## Shader provenance

The EASU and RCAS shaders are GLSL ES 3.0 ports based on AMD's FidelityFX FSR 1.0 32-bit shader algorithm. The EASU pass uses explicit `texelFetch` neighborhood loads in place of the upstream Vulkan GLSL `textureGather` callbacks. The RCAS pass uses texel fetches from its 3×3 neighborhood. See [`../third_party/AMD-FSR-LICENSE.txt`](../third_party/AMD-FSR-LICENSE.txt). The port has passed standalone GLSL ES compilation with `glslangValidator`, but its output has not yet been run on Android hardware or compared against the AMD reference sample.

## Next gate

The Android TV emulator smoke confirms app launch, shader linking, H.264 MediaPlayer decode, changing frames, D-pad navigation across four filters, pause, RCAS adjustment, pixel differences between modes, and render diagnostics under SwiftShader. The next gate is a physical Android TV/Google TV, where decoder/GPU behavior, color/orientation and performance must be independently verified. A MediaProjection capture, if implemented later, must use the system consent flow and display a separate processed preview; it cannot be described as replacing the source app's pixels.
