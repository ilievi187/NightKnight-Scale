# Luna TV Rust-native engine V2 checkpoint — 2026-11-03

## Goal and safety boundary

Continue the Android TV renderer migration incrementally on `feature/luna-gpu-engine`, preserving the Kotlin GLES path as a fallback. The immediate milestone is a small Rust `cdylib` integrated by Gradle/NDK and used only for deterministic EASU stage planning. Each JNI result batches all stage dimensions; JNI is never called per frame, pass, or pixel. This is architecture groundwork, not a claim that Rust improves GPU throughput. Do not move GLES calls off the existing GLSurfaceView GL thread, alter AMD FSR equations, enable the failed direct-OES EASU path, access the physical TV, merge PR #5/#6, or advertise unverified native 4K.

## Baseline and repository evidence

- Repo worktree: `/home/nightknight/Projects/NightKnight-Scale-Luna-GPU`
- Branch/HEAD: `feature/luna-gpu-engine` / `7b0e472550814d1844bbaac8ecc2885139078198`; clean at audit start.
- PR #6: open, draft, based on `feature/android-tv-fsr-prototype`; PR #5 is also open against that base. Preserve both and do not merge either.
- PR #6's latest recorded head checks passed: PR run `41382520775` and push run `41382513893` (Linux format/Clippy/tests, Android unit/build, Android TV x86 SwiftShader GLES/remote smoke). Fresh PR read confirmed all listed checks successful for head `7b0e472`.
- Local baseline: `cargo fmt --all -- --check`, `cargo clippy --workspace --all-targets -- -D warnings`, `cargo test --workspace` (13 Rust tests), and `python3 -m unittest discover -s scripts -p 'test_*.py'` (35 tests) passed.
- Android local build was attempted but cannot run with this host setup: only Gradle 9.8.1 is installed (AGP 8.13.2 rejects Gradle 9.6+ internal API removal); `/opt/android-sdk` contains only `build-tools`, no NDK or platform. CI's tested Android route uses Gradle 8.13/JDK 17/SDK 36. No ADB command was run.
- PR #6 records 114 Kotlin/JUnit tests on the validated Android route and a passing API 36 TV emulator smoke. These are historical emulator checks, not PowerVR evidence.

## Reproducible performance baseline (emulator only)

Source: PR #6 body and CI artifacts for the exact baseline head. Input is 960x540/30 fps, callback metrics are callbacks/s (not display FPS), CPU times are wall-time around GLES submission (may contain driver stalls), and RGBA values are planner estimates.

| preset | windows | SurfaceTexture callbacks/s | renderer callbacks/s | CPU GL submit avg ms/window | CPU pass submit avg ms | estimated RGBA allocation / logical writes per frame |
|---|---:|---:|---:|---:|---|---:|
| QUALITY | 1 | 5.55 | 6.11 | 5.431 | NR .023; EASU .045; RCAS .070 | 11.87 / 19.78 MiB |
| BALANCED | 3 | 6.83 | 7.55 | 15.267 | EASU .049; RCAS .060 | 9.89 / 17.80 MiB |
| PERFORMANCE | 1 | 6.91 | 7.15 | 8.015 | EASU .044; present .042 | 9.89 / 9.89 MiB |
| OFF | 1 | 16.94 | 15.97 | 11.934 | present .574 | 1.98 / 1.98 MiB |

Timer queries and physical-display presentation timestamps were unavailable. The emulator did not sustain 30 renderer callbacks/s in FSR modes; SwiftShader does not identify the PowerVR bottleneck. For 540p→4K the estimated A path is 9.89 MiB allocated / 17.80 MiB logical writes per frame; B and C each 41.53 / 73.17 MiB; D 39.55 / 71.19 MiB. These are estimates, not measured GPU bandwidth or image quality.

The existing debug comparison of direct OES EASU versus the RGBA-copy reference failed: MAE 8.1395 LSB, max error 255, PSNR 17.27 dB. Keep direct OES EASU disabled. EASU/RCAS equations and their attribution remain unchanged.

## Audit findings by bottleneck class

- CPU/render submission: `FsrDemoRenderer.kt` is a 2,113-line renderer/lifecycle/policy owner. It repeats program/texture/FBO/viewport/attribute calls for each pass; `bindQuad` re-enables attributes and resets pointers per draw. This is CPU/driver submission overhead to measure, not proof of a GPU bottleneck. The steady frame path otherwise reuses a direct quad buffer and persistent render targets.
- CPU allocations: the rotated-video fallback in `updateDecodedVideoFrame` creates two 16-float arrays and multiplies matrices per consumed frame when the SurfaceTexture matrix lacks the video rotation. Most texture/FBO/bitmap allocations happen at setup, resize, or preset changes, not per frame. Diagnostics snapshots and stage timing lists are periodic/UI work.
- Driver synchronization: `SurfaceTexture.updateTexImage`, `glDraw*`, and other GLES calls can block on decoder/driver work; CPU timing around them is not GPU execution time. `eglSwapBuffers` is outside renderer CPU-submit timing. GPU timer query is asynchronous and optional.
- GPU/memory bandwidth: EASU performs 12 neighborhood loads per output fragment; RCAS uses a 5-tap cross. The pipeline copies OES to RGBA before EASU because equivalence failed, then writes the EASU stages and RCAS. 4K multi-pass path estimates are materially larger than strategy A. Keep shader equations intact and validate actual-device measurements before changing pass topology or formats.
- FBO/state: source and processing FBOs/textures are retained/reused, intermediate ping-pong slots are bounded to the stages, FBO completeness is checked after allocation, and RCAS writes directly to the default framebuffer when geometry permits. Resize/preset changes release/reallocate processing targets. Full-surface passes still clear the default framebuffer before drawing; a future skip-clear optimization needs an image regression.
- Decoder/lifecycle: `updateTexImage` and transform access occur on the GL thread; the frame-available listener runs on main and only updates atomics/rate state and requests a dirty render. Preserve that context/thread ownership. MediaPlayer and selected local-video input remain Android-owned.
- API/driver: manifest requires GLES 3.0, not GLES 3.2. GLES 3.2, `GL_EXT_disjoint_timer_query`, OES shader support, 4K EGL buffers, and PowerVR BXE behavior are device/driver capabilities, not guarantees. Existing bounds checks use actual callback/EGL sizes, GLES limits and complete FBO state before validating output.
- Measurement: renderer callbacks, SurfaceTexture callbacks, source FPS, app-window FrameMetrics, CPU submit time, optional GPU timer query, and display presentation are distinct. Do not call callbacks physical FPS or planner bytes measured memory/bandwidth. CI is x86 SwiftShader, not PowerVR.

## Implementation progress (Android-CI verification still pending)

- Added `crates/nightknight-render-core` (`rlib` + `cdylib`) with six Rust unit tests. Test-first evidence: stage-planner tests failed on missing API, then passed; JNI flattening test failed on missing helper, then passed.
- Added a narrow `jni` 0.22.4 boundary with no Rust GLES calls or mutable GL globals. Host release library exported `Java_org_nightknight_scale_tv_NativeEasuStagePlanner_nativePlanEasuStages`; a JVM loaded the host `.so`, compared eight schedule cases with Kotlin, and verified a full 540p→4K plan uses exactly one JNI call (`render_plan_jni_calls=1`). This is host ABI evidence, not Android ABI evidence.
- Kotlin retains and tests its fallback. Sixty-three focused Kotlin/JUnit cases passed via local `kotlinc` + JUnit: 61 planner/geometry/scaling/output-policy cases plus two new coalesced-GL-event tests. The overflow, derived-intermediate, and coalesced-event tests were observed RED before their fixes. These are not a substitute for the full Android Gradle suite.
- The full Python suite passes 52 tests, including synthetic APK ABI checks, exact per-event plan-record checks, native-4K C-plan-to-effective-buffer matching for both 4K/C and 1080p/A fallback, NDK environment fallback, geometry no-replan, mode-transition/tracker, log-snapshot and checksum-stability guards. The fallback matcher accepts only `requested_buffer=3840×2160` or `1920×1080`.
- Pinned the Android native build to Rust/Cargo `1.99.0` and NDK `27.2.12479018`; Gradle tracks both toolchain pins and the builder validates the actual compiler. Fixed NDK discovery to try `ANDROID_NDK_ROOT` when `ANDROID_NDK_HOME` is invalid, and corrected the NDK r27 macOS ARM64 host tag to `darwin-x86_64`.
- Fixed review findings: UI keeps `requestedMode` separate from the GL-owned active mode; a coalesced GL event applies the latest request and rebuilds resources before drawing. If `onSurfaceChanged` adopts first, it resets stale runtime fallback state before planning. On valid surface target creation, `createOrReplaceTargets()` updates `lastModeOnGlThread` on success and allocation fallback; a new regression guard traces this path. Identical geometry/configuration callbacks skip JNI planning.
- Smoke assertions reject extra records for exact preset/strategy events, permit legitimate resize callbacks only when each C plan record proves one JNI call, and match the same C plan record to verified output: effective C plus `linear_final=true` on 4K, or effective A plus `linear_final=false` at 1080p. The fallback matcher accepts only legitimate 4K/1080p requested buffers because a validated already-1080p surface may not issue another callback. The EXIT trap writes a separate `logcat-on-exit.txt`, leaving checksum-covered `logcat.txt` unchanged; initial planner Logcat is also saved before later resets.
- Local `cargo fmt`, Clippy, 19 Rust workspace tests, 63 focused Kotlin/JUnit tests, 52 Python tests, 2 vertex/5 fragment GLSL shaders, shell syntax, staged/unstaged `git diff --check`, and host JNI smoke pass. Android Gradle/NDK build and emulator assertions still await hosted CI.
- The previous review batch found a fallback matcher defect, now fixed; its separate applied-mode-tracker concern was checked against `createOrReplaceTargets()` success/fallback assignments and covered with a regression guard. A fresh verification review is pending before commit/push. Local Android build remains blocked by Gradle 9.8.1 vs AGP 8.13.2 and absent SDK platform/NDK/Android Rust targets. No physical ADB command has been run.

## TODO state at checkpoint

1. [x] Confirm instructions, target worktree/branch/clean state, PR coordination, current CI.
2. [x] Audit renderer, shaders, architecture, baseline metrics and local verification limits.
3. [x] Add a Rust render-core crate, deterministic EASU stage scheduling, and Rust tests.
4. [x] Add narrow JNI wrapper, safe Kotlin fallback/validation, and host-JVM JNI smoke.
5. [ ] Verify Gradle/NDK compilation and APK packaging for `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`.
6. [ ] Verify Android JVM tests and emulator-native/Kotlin parity plus actual Rust use in the live render plan.
7. [~] Run local workspace/Python/Kotlin/GLSL checks (passed); finish the three review passes on the final diff.
8. [ ] Update draft PR #6 with verified build/test/ABI evidence and limitations; keep it draft and leave PR #5 untouched.

## Blockers and next action

The local machine lacks supported Gradle 8.13, Android platform/NDK and Rust Android targets; a local Android/NDK build cannot be claimed. Review-fix checks are green locally. Next, run fresh read-only reviews on this exact worktree, then commit and push the validated slice so hosted PR CI can run NDK ABI builds, APK verification, and emulator JNI smoke. Diagnose any CI failure from its exact job before editing. Do not run ADB against `192.168.1.198:5555`, mark PR #6 ready, or merge either open PR.