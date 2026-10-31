# NightKnight Scale TV (prototype)

This Android application is part of the existing NightKnight-Scale repository. It is a fullscreen foreground demo for Android TV / Google TV, not a second product and not a transparent system-wide picture filter.

## Current implementation

- Leanback launcher entry and touchscreen-optional declaration.
- OpenGL ES 3.0 capability requirement and GPU renderer/version/max-texture detection.
- Generated half-resolution synthetic detail chart sized to keep the demo upscale at approximately 2× per axis (4× image area).
- Four packaged, deterministic 6-second H.264 test clips at 640×360, 960×540, 1280×720 and 1920×1080, encoded as YUV420p at 30 fps. The selected clip is approximately half of the current processed target per axis.
- Optional SAF `ACTION_OPEN_DOCUMENT` picker for local video with persisted read access where supported; framework MediaPlayer decodes into an external OES SurfaceTexture, and a GPU pass copies frames to a 2D RGBA texture.
- Actual decoder-reported source dimensions are sent to the GL thread. The RGBA input/NR textures are reallocated to match; the processed target is capped at 2× per axis of that source (4× area) and the remaining display fill is presented to the app surface.
- A GLSL ES port of AMD FidelityFX FSR 1.0's 32-bit EASU and RCAS algorithms, executed in separate GPU fragment passes.
- Four D-pad-selectable comparison paths: bilinear original, EASU only, FSR 1.0 EASU+RCAS, and FSR 1.0 with an optional mild spatial noise-reduction prefilter.
- D-pad-focusable output controls and a LEFT/RIGHT-adjustable RCAS sharpness slider.
- Display-mode, app GL-surface, actual processed-target, render-callback FPS and sparse synchronized render-completion diagnostics.

The packaged clips are synthetic test-pattern video, not television or IPTV content. Android MediaPlayer decodes them or a user-selected SAF video URI into an app-owned SurfaceTexture. SAF access is requested through the system document picker and no filesystem-wide permission is used. The GLES renderer copies each external OES frame to a 2D texture before the selected filter passes. The optional NR mode is a mild 3×3 edge-aware spatial prefilter, not temporal or codec-aware noise/artifact reduction. A generated chart remains as a fallback if video surface setup or decoding fails. Emulator CI previously verified packaged-clip playback, pause and resume with subsequent frame motion, all four output-mode transitions, and non-identical crops for selected same-frame filter comparisons. SAF selection itself still requires a dedicated emulator/device acceptance test. This uses Google SwiftShader software GLES and does not prove target-TV hardware decoder/GPU behavior or perceptual image improvement. The UI has no MediaProjection, VirtualDisplay, foreground capture service, or cross-app processing. Compression-artifact reduction and deinterlacing are not implemented. Exiting or covering this activity does not enhance other apps. No DRM or protected-content bypass is implemented or permitted.

## Build

Requirements: JDK 17, Gradle 8.13, Android SDK Platform 36 and Android SDK Build Tools 35 or newer. From this directory:

```sh
gradle testDebugUnitTest assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`. No APK is checked into the Git repository. GitHub PR CI builds this APK, runs the JVM tests, prints the APK SHA-256, and uploads it as a seven-day artifact. CI run `40834327084` passed Linux format/lint/tests, Android build/JVM tests, and Android TV emulator GLES/remote smoke. The API 36 Android TV 1080p AVD with Google SwiftShader GLES 3.0 decoded its 960×540 H.264 clip, uploaded SurfaceTexture/OES frames, and processed them at 1920×1080. Remote input exercised pause, resume, all four filter modes, and sharpness 8→9. Two post-resume captures one second apart differed at 35,755/259,200 crop pixels (13.7944%), confirming playback advanced after resume. Callback FPS was 4.780774. Sparse submit-to-`glFinish` samples were 175.7694 ms at frame 120 in FSR1 mode and 114.018845 ms at frame 240 in EASU-only mode. These are software-emulator observations, not physical-TV/target-GPU performance, display-present FPS, or evidence of perceptual quality. The downloaded debug APK is `/home/nightknight/.jcode/scratch/nkscale-final-resume-40834327084/apk/app-debug.apk`, SHA-256 `5bc1b77b5c41e90de9c7f4ac8d7fb295654e10936fb8b97a221a9936f661bf2c`; `file` identified it as an Android package and `unzip -t` passed. The local host still lacks Gradle/SDK tools.

To regenerate the test clips after intentionally removing the existing assets, run `bash scripts/generate-test-videos.sh`. The script refuses to overwrite any existing clip. Each asset in the latest APK was extracted and fully decoded with FFmpeg. MediaPlayer/SurfaceTexture video decode, changing-frame upload, remote pause/resume, all four mode transitions, and same-frame pixel-difference assertions were verified in CI run `40834327084` on a Google SwiftShader Android TV emulator. In its paused 240×1080 crop, bilinear differed from EASU-only at 11,599/259,200 pixels (4.4749%), FSR1 at 11,603/259,200 (4.4765%), and FSR1+NR at 11,695/259,200 (4.5120%). EASU-only vs FSR1 differed at 655/259,200 pixels (0.2527%), FSR1 vs FSR1+NR at 8,965/259,200 (3.4587%), and FSR1 sharpness 8 vs 9 at 162/259,200 (0.0625%). Two running captures one second apart differed at 31,221/259,200 pixels (12.0451%); two captures one second apart after remote resume differed at 35,755/259,200 (13.7944%). These are pixel-difference checks on synthetic test imagery, not perceptual quality scores. A physical TV test remains required for decoder/GPU/performance support claims.

## Measurement scope

- **Display mode** is obtained from the Android default display mode API.
- **GL surface** is the dimensions passed to `GLSurfaceView.Renderer.onSurfaceChanged`.
- **Processed target** is the intermediate output size after respecting a 4096-pixel prototype cap and the GLES maximum texture size. If that differs from the GL surface, the processed image is bilinearly presented to fill the surface.
- **Render FPS** is the observed `onDrawFrame` callback rate, not an independently measured physical-display presentation rate.
- **Decoded video frames** counts frames copied from the `SurfaceTexture` into the app's RGBA input texture. It is not the source decoder's requested FPS or a guaranteed display-present rate.
- **Submit→GPU-finish sample** measures the elapsed CPU time from the start of a sampled draw callback until `glFinish()` returns. It is sampled once every 120 callbacks to avoid serializing every frame. It may include previously queued GPU work and is not a pure GPU timestamp or end-to-end presentation latency.

## Shader provenance

The EASU and RCAS shaders are GLSL ES 3.0 ports based on AMD's FidelityFX FSR 1.0 32-bit shader algorithm. The EASU pass uses explicit `texelFetch` neighborhood loads in place of the upstream Vulkan GLSL `textureGather` callbacks. The RCAS pass uses texel fetches from its 3×3 neighborhood. See [`../third_party/AMD-FSR-LICENSE.txt`](../third_party/AMD-FSR-LICENSE.txt). The port has passed standalone GLSL ES compilation with `glslangValidator`, but its output has not yet been run on Android hardware or compared against the AMD reference sample.

## Compatibility and limitations

See [`../docs/android-tv-compatibility.md`](../docs/android-tv-compatibility.md) for the source-by-source Android TV/EON/HDMI/MediaProjection boundary, cited platform limitations, and explicit next release gates. In short: this app processes its own bundled or user-selected local video only; it is not a system-wide enhancer, does not directly intercept EON/other apps/HDMI, and does not bypass DRM. The player is framework MediaPlayer (not Media3) and local SAF selection still needs runtime acceptance coverage.
