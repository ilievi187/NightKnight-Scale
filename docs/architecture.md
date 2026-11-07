# Architecture

## Goals and trust boundaries

NKScale is split into three responsibilities:

1. **Control UI (`nkscale-ui`)** — edits settings, discovers Vulkan and SDL displays, starts/stops a supervised backend process, and stores a user profile. It is not in a per-frame loop.
2. **Backend boundary (`nkscale-core`)** — validates settings, constructs a direct argv vector, serializes profiles, and enumerates the displays exposed by SDL2.
3. **Frame renderer** — for the first usable mode, this is the user's installed **Gamescope** process. Native rendering is a separate future backend, not a label applied to Gamescope.

The launch command is split with shell-style quoting only. No shell is invoked, so expansion, pipes, command substitution, and shell startup files are not executed. The app manages only the Gamescope child it created.

## Mode A: Gamescope nested launch (first milestone)

The controller invokes Gamescope's documented command-line controls:

- `--backend sdl` for the nested SDL backend;
- `--display-index` using SDL's own enumerated display index;
- `--nested-width` / `--nested-height` for the application's game/render mode;
- `--output-width` / `--output-height` for the nested output surface;
- `--filter fsr` plus `--sharpness` for Gamescope's FSR 1.0 path;
- `--scaler fit`, `integer`, `fill`, `stretch`, or `auto` for the selected geometry policy;
- `--fullscreen` only when requested; `--expose-wayland` for Wayland clients where the installed Gamescope build supports it.

The local development host's Gamescope 3.16.31 `--help` advertises these controls and identifies `fsr` as AMD FidelityFX Super Resolution 1.0. NKScale does not bundle or reimplement that shader in this mode. The user must have a compatible Gamescope build installed.

Nested fullscreen is still a surface submitted to the host compositor. A normal Wayland client does not gain authority to replace the physical display scanout or reconfigure other clients' output. Gamescope may have a DRM/KMS backend for standalone/exclusive-session use, but that is a different privilege, lifecycle, and compatibility model; NKScale does not select it in this milestone. The nested route can incur host compositor scheduling/presentation overhead and is not asserted to have the same latency as direct scanout.

## Mode B: portal-authorized screen capture (planned)

The intended flow is an explicit xdg-desktop-portal ScreenCast session: request source types, let the portal/compositor present source selection and consent, start the session, obtain PipeWire stream node IDs, open the portal-provided PipeWire remote, and consume negotiated video buffers. The selected source and target output are separate concepts.

A mirror can recursively capture its own fullscreen output if the selected source includes the presentation window. The future implementation must identify/exclude its own output where supported or refuse unsafe monitor selection and explain the workaround. It must not attempt compositor-private capture, silently reuse a prior grant, or bypass the user's chooser.

PipeWire may deliver system-memory or DMA-BUF-backed buffers depending on compositor, portal, negotiated format/modifier, and buffer negotiation. A future Vulkan backend must validate importability, synchronization, lifetime, and fallback behavior per buffer. Zero-copy is a measured capability, not a blanket promise. HDR/color metadata and multi-GPU ownership are also unresolved for this route.

## Native GPU pipeline (planned, separate milestone)

The future pipeline is modular: capture/input surface → format/color conversion → spatial scaler → optional sharpening → output surface. FSR 1.0 means spatial EASU upscaling and RCAS sharpening; it does not create frames and has no temporal motion/depth input. Native shaders must be based on compatible upstream open-source code, preserve notices, and be checked against reference images before release. NIS is not exposed until its license/redistribution conditions have been independently audited.

The UI and renderer communicate through bounded, non-blocking control/telemetry channels. Stale telemetry must not stall frame presentation. GPU timing, memory, frame pacing, dropped frames, and end-to-end latency must be clearly scoped to the instrument that measured them.

## Current environment evidence

At initial development, the host was Omarchy Linux x86_64 under Hyprland/Wayland, with Gamescope 3.16.31, PipeWire 1.6.9, SDL2 2.32.74, Vulkan loader 1.4.363, an amdgpu Navi 31-class device, and multiple active outputs including a 4K/60 display, 2560×1080/180 display, and 1920×1080/500 display. The UI does not hard-code these details; runtime output is discovered. These facts are setup context, not a compatibility certification or benchmark.

## Upstream references

- Valve Gamescope source and usage: https://github.com/ValveSoftware/gamescope
- AMD FidelityFX FSR source: https://github.com/GPUOpen-Effects/FidelityFX-FSR
- xdg-desktop-portal ScreenCast: https://flatpak.github.io/xdg-desktop-portal/docs/doc-org.freedesktop.portal.ScreenCast.html
- xdg-shell protocol: https://wayland.app/protocols/xdg-shell
- PipeWire API documentation: https://docs.pipewire.org/
- wgpu API documentation: https://docs.rs/wgpu/latest/wgpu/
- Vulkan DMA-BUF external-memory extension: https://registry.khronos.org/vulkan/specs/latest/man/html/VK_EXT_external_memory_dma_buf.html

These references are starting points for the upcoming native/capture milestones. API and compositor support must be rechecked against the versions actually targeted before implementation.

## Android TV demo (prototype)

The Android application lives in `android/` in this repository; it is a foreground GLES 3.0 demo with deterministic H.264 test clips and user-selected local videos. Android MediaPlayer decodes into an app-owned `SurfaceTexture` backed by an external OES texture. BALANCED/PERFORMANCE can sample that OES texture directly as EASU input only after an opt-in debug pixel comparison passes for the active source/geometry; merely linking `GL_OES_EGL_image_external_essl3` does not enable the path. Until validated—and after any mismatch—the RGBA-copy reference path remains active. OFF can present the OES texture directly because it does not run FSR. QUALITY retains the OES→RGBA8 pass because the NR filter needs a 2D source texture. This preserves the FSR input route unless equivalence is measured, without changing decoder ownership or its callback thread.

Render-target allocation follows the effective preset instead of reserving every processing intermediate for every mode, and the source RGBA texture/FBO is retained across preset changes and recreated only when source dimensions change. OFF uses that source target only as a fallback; PERFORMANCE adds EASU output and reusable ping-pong buffers; BALANCED adds RCAS; QUALITY adds the NR source-sized target. Each ping-pong slot is sized to the largest stage that actually uses that slot, rather than allocating two copies of the largest intermediate. RCAS renders directly into the centered default-framebuffer viewport when that viewport matches the EASU target; only a size mismatch requires a separate RCAS scratch texture. Every FBO is checked for completeness, incomplete FBO objects are deleted, and failed RGBA allocations fall back to bilinear input or direct OES output where possible. The 128 MiB policy is a soft estimate of app-owned RGBA targets for the active path; decoder/OES, driver alignment, UI and compositor memory are excluded.

The renderer keeps requested and effective modes separate. If QUALITY's NR shader/target or another optional shader/resource is unavailable, diagnostics name the effective fallback rather than silently implying the requested path ran. Independently, when nominal source FPS is known and recent SurfaceTexture frame callbacks show active input, a conservative policy steps down one mode after two consecutive one-second renderer-callback-interval windows exceed that source-frame budget by 3%; it records a reason and can step down again if needed. Paused/stale sources do not cause further downshifts. This is a callback-cadence safeguard—not a GPU-time measurement, physical display FPS, or proof that the GPU caused a stall. User presets remain OFF (bilinear), QUALITY (NR+EASU+RCAS), BALANCED (EASU+RCAS), and PERFORMANCE (EASU).

Diagnostics separate nominal source video FPS when track metadata provides it, SurfaceTexture callback cadence, consumed media-timestamp cadence, renderer callback cadence, estimated callback coalescing, decoder-reported lag, two-second CPU wall-time samples for whole-frame GLES submission and input/NR/EASU/RCAS/presentation stages, asynchronous GPU execution samples (only with `GL_EXT_disjoint_timer_query`), and physical display presentation timing. CPU timings may include driver stalls but are not GPU execution time and exclude EGL swap. Unknown local-track FPS is N/A; it is never inferred from callback cadence. Renderer callback FPS is not display FPS; display presentation timing remains N/A because this GLSurfaceView path does not provide physical scanout timestamps. GPU query samples are polled without waiting and invalidated when the GPU reports a disjoint interval. Production telemetry does not call `glFinish()` or `glReadPixels()`; the latter is reserved for an explicit debug-only equivalence probe.

The shader implementation is a GLSL ES 3.0 port of AMD FidelityFX FSR 1.0's 32-bit EASU and RCAS paths. The RGBA reference path uses explicit `texelFetch` neighborhood loads in place of the upstream Vulkan GLSL gather callbacks; the optional OES EASU variant changes source loading but shares the same EASU math. A debug-only first-frame probe compares direct-OES and RGBA-copy EASU output buffers. Direct input remains disabled unless that comparison passes for the current source/geometry; a failure is reported with MAE, per-channel errors, max-error location and PSNR, then retains RGBA copy. The multi-pass policy limits each EASU stage to ≤2× per axis. The optional NR prefilter is a separate mild 3×3 spatial pass, not codec-aware or temporal. The source attribution and MIT license are in `third_party/AMD-FSR-LICENSE.txt`. Both shader variants are validated with `glslangValidator`; emulator comparison is a runtime regression probe, not proof of equivalence on the PowerVR BXE-4-32 or against AMD's reference corpus. No physical GPU timing or image-quality result is claimed.

There is no MediaProjection, VirtualDisplay, or screen-capture service, and the app cannot replace another app's presented image. Playback is a foreground preview of packaged test clips or user-selected local videos only; Media3/ExoPlayer, IPTV/tuner input, codec-aware artifact reduction, deinterlacing, and DRM handling are outside this GPU module. A future consent-based capture implementation must request a fresh system-mediated grant and process only the capture stream delivered to its own surface. Protected/DRM surfaces must remain protected and unsupported content must be reported rather than bypassed.

## Android Rust planning core (experimental milestone)

The Android app now includes the reusable Rust crate `nightknight-render-core` as an NDK `cdylib`. Its only rendering responsibility in this milestone is deterministic EASU stage scheduling (each EASU scale remains capped at 2× per axis). A small JNI method accepts source/target dimensions and returns all stage dimensions as one primitive array. An EASU render-plan creation uses one JNI call; memory estimation and ping-pong allocation derive their buffers from the already-selected stage list. The UI publishes a separate volatile requested mode; the active mode remains GL-thread-owned until a coalesced queue event rebuilds its plan/resources, and rapid preset changes collapse to the latest request. If `onSurfaceChanged` adopts the request first, it also resets transition fallback state before replanning. Steady-state `onDrawFrame` does not build plans or call the planner. JNI is not called per pass or pixel. Diagnostics separates planner/JNI CPU wall time from GLES and GPU measurements.

Kotlin remains authoritative for Android lifecycle, MediaPlayer and `SurfaceTexture`, shader compilation/program bindings, GLES commands, texture/FBO ownership, output validation, and presentation. The existing GLSurfaceView GL thread remains the sole owner of the EGL context and GLES state. `NativeEasuStagePlanner` loads the library lazily, rejects malformed schedules, and safely uses the retained overflow-safe Kotlin reference planner when loading/calling fails. Debug emulator smoke compares the native planner against the Kotlin reference over 8 canonical and boundary cases, then checks exact Rust-plan use and one-call counts on surface initialization, preset changes, and strategy changes. The Rust planner has no GL resource ownership or mutable global rendering state yet.

Gradle's `preBuild` task compiles the crate with Rust/Cargo `1.99.0` and Android NDK `27.2.12479018`, packages generated libraries for `arm64-v8a`, `armeabi-v7a`, `x86`, and `x86_64`, and tracks both toolchain pins as task inputs. CI installs the exact compiler, components, and corresponding Rust targets. `scripts/verify_rust_apk.py` checks ELF class and machine in the APK, while the Android TV emulator smoke executes the x86 library. The actual TV CPU ABI and PowerVR behavior remain unverified because the physical television is intentionally untouched.

Binding/resource decision: this milestone uses the `jni` Rust crate for the narrow JNI boundary and introduces no Rust GLES binding. Keep GLES in Kotlin until a native resource-management slice has a measurable reason to move. If that slice is justified, prototype the small direct NDK GLESv3 FFI before adopting a broad GL wrapper, retain the current EGL/GL owner thread, and document every `unsafe` call's current-context/thread invariant. Native RAII must delete objects on that same context-owning thread; no unsafe global mutable renderer state is allowed.

This policy-only migration is not evidence of faster shaders, lower GPU time, or faster playback. It changes neither EASU/RCAS equations nor the failed direct-OES equivalence decision, does not claim GLES 3.2 availability, and does not validate native 4K on PowerVR. Future milestones must compare CPU planner cost and renderer performance under identical conditions before moving FBO, shader, or draw ownership into Rust.

## References for Android TV and capture

- Android TV launcher, device feature and remote-navigation requirements: https://developer.android.com/training/tv/get-started/create
- Android OpenGL ES `GLSurfaceView` and renderer lifecycle: https://developer.android.com/develop/ui/views/graphics/opengl/environment
- MediaProjection capture model and user-consent flow: https://developer.android.com/media/grow/media-projection
- MediaProjection foreground-service requirements: https://developer.android.com/develop/background-work/services/fgs/service-types
- AMD FidelityFX FSR 1.0 source and algorithm: https://github.com/GPUOpen-Effects/FidelityFX-FSR
