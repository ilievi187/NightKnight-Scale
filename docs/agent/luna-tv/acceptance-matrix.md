# NightKnight Scale TV acceptance traceability

Date: 2026-10-29. This matrix maps the original TV-first mission to observed checks and explicit gaps. **AVD** means the hosted Android TV 1080p API 36 x86 emulator using Google SwiftShader GLES 3.0. It is a software renderer, not a physical TV GPU.

## Implemented prototype and acceptance evidence

| Original requirement | Concrete check over the current output | Observed result | Status / scope |
|---|---|---|---|
| Continue existing repository; preserve Linux | Code-bearing CI run `40790385509`: Linux format, Clippy, Rust workspace tests, Python harness | Linux job passed. Android app was added under the existing repo. Rust/Gamescope files were not changed. | **Pass** |
| Produce installable Android TV app | Android CI ran `testDebugUnitTest assembleDebug`; code-bearing APK downloaded, `file` and `unzip -t` checked | Build/tests passed. APK: `/home/nightknight/.jcode/scratch/nkscale-tv-audit-run-40790385509/app-debug.apk`; SHA-256 `b75c1131d6884aa97489ac11296d67bea2f10a565456e06785edab24f042c28e`. | **Pass for debug build/package**, not release signing |
| App launches on Android TV | CI installed APK on API 36 `tv_1080p` Android TV AVD and launched `.MainActivity` with ADB | Activity ran, created GLES programs and produced screenshots. Google TV hardware was not tested. | **Pass on Android TV AVD only** |
| Remote interaction | AVD smoke sent DPAD events through ADB to the actual app UI | Pause, Enhanced/Original selection, sharpness 8→9 were observed in app logs. This is emulator key injection, not a physical remote. | **Pass on AVD** |
| Display and GPU capability detection | Activity log/UI during AVD smoke | Display mode, GL surface and target 1920×1080, 960×540 source, SwiftShader GLES 3.0 renderer and max texture were reported. | **Pass on AVD** |
| Reproducible video test input/package | `unzip -l` on the current APK, FFprobe/full local FFmpeg decode, Android MediaPlayer AVD playback | APK contains all four H.264/YUV420p/30fps clips at 640×360, 960×540, 1280×720, and 1920×1080, 6 seconds each. All full-decoded locally. AVD selected/decoded 960×540. | **Pass for packaged synthetic video**; no channel/IPTV input |
| GPU image pipeline | `glslangValidator -l` on EASU, RCAS, present and external OES copy programs; AVD logcat showed `shaders=ready`, `video_frame=uploaded` | MediaPlayer → `SurfaceTexture`/OES → GLES RGBA copy → EASU → RCAS. Runtime rendered under Google SwiftShader. | **Pass for GLES software AVD execution**; not hardware GPU validation |
| Original vs enhanced comparison | AVD paused a decoded frame, captured Enhanced, switched to Bilinear, captured again, cropped the unobstructed rightmost 240×1080 region | Latest checked run: 11,125 / 259,200 pixels differed (4.2921%), mean absolute RGB channel difference 0.170013. | **Pass for changed pixels**; not a perceptual quality score or proof the image is better |
| Requested multi-algorithm quality comparison (bilinear/EASU/EASU+RCAS/EASU+NR) | Current UI modes and AVD A/B captures | Only Bilinear Original and EASU+RCAS Enhanced are selectable. There is no EASU-only or denoise mode, no NR output, and no reference-corpus/perceptual-quality comparison. | **Partial**; only original-vs-enhanced pixel difference was tested |
| Video playback advances | Two AVD screenshots one second apart while MediaPlayer was playing; same 240×1080 crop compared | 33,676 / 259,200 pixels differed (12.9923%), mean absolute difference 7.868062. Decoded-frame upload counter/log also advanced. | **Pass on AVD** |
| Remote pause, mode switching and sharpness | AVD DPAD script launched playback, paused the video, selected Original/Bilinear, then changed RCAS 8→9 | Pause, output toggle and sharpness input were logged. The scripted smoke did not send a resume/play event. | **Pause/A-B/sharpness pass on AVD**; resume UI code not explicitly exercised |
| FPS | Renderer callback counter/log | `render_fps=3.0159607` on the checked SwiftShader video run. This is `onDrawFrame` callback cadence, not physical display-present FPS. | **Measured on software AVD only** |
| Rendering completion/latency | Smoke waited for sparse 120-frame `glFinish()` sample while Enhanced mode was active | `render_completion_ms=105.02203 frame=120 mode=enhanced method=glFinish`; a later Bilinear sample was 15.206015 ms at frame 240. It includes CPU submission and synchronization, not a pure GPU timestamp or end-to-end presentation latency. | **Measured with explicitly scoped proxy on software AVD** |
| 720p/1080p→4K mapping | Android JVM policy tests for 720p, 1080p, 1440p, and 4K target source selection | 4K selects 1920×1080 source by policy. Runtime AVD was 1080p only. | **Policy test pass; 4K runtime unverified**. 720p→4K is 9× area, outside FSR1's documented 1–4× area range |
| NR/compression artifact reduction/deinterlacing | Source/UI/roadmap inspection | No denoising, compression-artifact reduction, or deinterlacing path exists. | **Not implemented** |
| AUTO/QUALITY/BALANCED/PERFORMANCE presets | UI/settings inspection | No named preset selector or adaptive performance policy exists. | **Not implemented** |

## Cross-app processing, capture, DRM and integration requirements

| Original requirement | Concrete check/evidence | Observed result | Status / scope |
|---|---|---|---|
| Enhance other apps after exiting enhancer | Manifest/component/source inspection and observed app flow | This is a foreground video preview. It only processes its own selected input while running. No scanout, SurfaceFlinger, or Hardware Composer replacement is present. | **Not implemented**; no transparent/system-wide replacement claim |
| Normal Android app limitations | Manifest and architecture inspection | No privileged/system API is called by this app. The public implemented path is its own MediaPlayer surface. | **Documented boundary** |
| Consent-based MediaProjection/VirtualDisplay | Manifest/source search | No MediaProjection, VirtualDisplay, foreground capture service, screen-capture consent dialog, revocation callback, or recursion test exists. A future flow must be separate, consented, and shown as preview only. | **Not implemented/not tested** |
| SurfaceControl/SurfaceFlinger/HWC privileged path | Android source search | No privileged capture, scanout, or compositor hooks. | **Not implemented/not tested** |
| OEM picture-enhancement interfaces/custom firmware | Source/config inspection | No manufacturer API or firmware integration. | **Not implemented/not tested** |
| DRM/protected surfaces | Manifest/source inspection | No capture/bypass code exists. Protected content behavior was not tested because no capture adapter exists. | **No bypass**; protected-surface behavior remains unverified |
| Recursive self-capture and consent revocation | Capture lifecycle inspection | No capture session exists to recurse or revoke. | **Not applicable until a capture path exists** |
| Local user-selected file/live IPTV/channel source | Activity/UI/source adapter inspection | The app automatically chooses among its four packaged clips. No Storage Access Framework picker, tuner, or IPTV URL input exists. | **Not implemented** |
| Continue in background after app exits | Manifest/service inspection | No background service. Video processing stops when the foreground activity pauses/exits. | **Not implemented** |
| Affordable/physical Android TV hardware performance | Local `adb devices -l` and CI AVD renderer evidence | No local physical device is attached. CI uses software SwiftShader, not a TV GPU. | **Hardware support/performance blocked and unclaimed** |

## Reproducible evidence locations

- Code-bearing all-green PR CI run: `40790385509` (Linux, Android build/JVM tests, Android TV video smoke). A later documentation-only run `40788733512` also passed all three jobs.
- Current verified APK: `/home/nightknight/.jcode/scratch/nkscale-tv-audit-run-40790385509/app-debug.apk`, SHA-256 `b75c1131d6884aa97489ac11296d67bea2f10a565456e06785edab24f042c28e`. Debug CI artifact, not a release build.
- Current smoke screenshots/logcat: `/home/nightknight/.jcode/scratch/nkscale-tv-audit-run-40790385509/smoke/`.
- Four video clips and non-overwriting generator: `android/app/src/main/assets/test-videos/` and `android/scripts/generate-test-videos.sh`.
- Automated Android TV scenario: `android/scripts/tv-emulator-smoke.sh`.

**Conclusion:** The verified representative path is a foreground Android TV AVD app decoding a packaged synthetic H.264 clip and feeding GLES EASU+RCAS, with D-pad A/B, pause, sharpness, FPS and synchronized completion diagnostics. It is not a system-wide enhancer or a hardware-support certification. User-selectable TV/IPTV input, OEM/privileged integration, NR/AR reduction, deinterlacing, and physical TV testing remain open.
