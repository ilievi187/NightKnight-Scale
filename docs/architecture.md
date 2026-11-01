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

The Android application lives in `android/` in this repository; it is a foreground GLES 3.0 demo with deterministic H.264 test clips and user-selected local videos. Android MediaPlayer decodes into an app-owned `SurfaceTexture` backed by an external OES texture. In the normal processing path, one GLES copy pass writes each decoded frame to an RGBA8 2D texture before the filter passes; if the RGBA source/FBO cannot be allocated, the renderer attempts direct external-OES presentation and reports the fallback. Four selectable paths remain: bilinear presentation, EASU only, EASU+RCAS (FSR 1.0), and mild 3×3 edge-aware spatial NR followed by EASU+RCAS.

Render-target allocation follows the effective preset instead of reserving every intermediate for every mode. OFF allocates only the source RGBA target; PERFORMANCE adds the EASU output and reusable ping-pong buffers; BALANCED adds RCAS; QUALITY lazily adds the NR source-sized target. Each ping-pong slot is sized to the largest stage that actually uses that slot, rather than allocating two copies of the largest intermediate. RCAS renders directly into the centered default-framebuffer viewport when that viewport matches the EASU target; only a size mismatch requires a separate RCAS scratch texture. Every FBO is checked for completeness, incomplete FBO objects are deleted, and failed RGBA allocations fall back to bilinear input or direct OES output where possible. The 128 MiB policy is a soft estimate of app-owned RGBA targets for the active path; decoder/OES, driver alignment, UI and compositor memory are excluded.

The renderer keeps requested and effective modes separate. If QUALITY's NR shader/target or another optional shader/resource is unavailable, diagnostics name the effective fallback rather than silently implying the requested path ran. The estimate is mode-specific, and the UI reports the allocation/shader fallback reason. User presets remain OFF (bilinear), QUALITY (NR+EASU+RCAS), BALANCED (EASU+RCAS), and PERFORMANCE (EASU).

Diagnostics separate nominal source video FPS when track metadata provides it, SurfaceTexture callback cadence, consumed media-timestamp cadence, renderer callback cadence, estimated callback coalescing, decoder-reported lag, CPU render/GL-submit duration, asynchronous GPU execution samples (only with `GL_EXT_disjoint_timer_query`), and physical display presentation timing. Unknown local-track FPS is N/A; it is never inferred from callback cadence. Renderer callback FPS is not display FPS; display presentation timing remains N/A because this GLSurfaceView path does not provide physical scanout timestamps. GPU query samples are polled without waiting and invalidated when the GPU reports a disjoint interval; `glFinish()` is not used for telemetry.

The shader implementation is a GLSL ES 3.0 port of AMD FidelityFX FSR 1.0's 32-bit EASU and RCAS paths. EASU uses explicit `texelFetch` neighborhood loads in place of the upstream Vulkan GLSL gather callbacks; the multi-pass policy limits each stage to ≤2× per axis. The optional NR prefilter is a separate mild 3×3 spatial pass, not codec-aware or temporal. The source attribution and MIT license are in `third_party/AMD-FSR-LICENSE.txt`. Shader assets pass local `glslangValidator` syntax checks and the Android TV emulator compiles the runtime programs, but this does not establish perceptual quality or equivalence to AMD's reference corpus. No physical TCL GPU timing or image-quality result is claimed.

There is no MediaProjection, VirtualDisplay, or screen-capture service, and the app cannot replace another app's presented image. Playback is a foreground preview of packaged test clips or user-selected local videos only; Media3/ExoPlayer, IPTV/tuner input, codec-aware artifact reduction, deinterlacing, and DRM handling are outside this GPU module. A future consent-based capture implementation must request a fresh system-mediated grant and process only the capture stream delivered to its own surface. Protected/DRM surfaces must remain protected and unsupported content must be reported rather than bypassed.

## References for Android TV and capture

- Android TV launcher, device feature and remote-navigation requirements: https://developer.android.com/training/tv/get-started/create
- Android OpenGL ES `GLSurfaceView` and renderer lifecycle: https://developer.android.com/develop/ui/views/graphics/opengl/environment
- MediaProjection capture model and user-consent flow: https://developer.android.com/media/grow/media-projection
- MediaProjection foreground-service requirements: https://developer.android.com/develop/background-work/services/fgs/service-types
- AMD FidelityFX FSR 1.0 source and algorithm: https://github.com/GPUOpen-Effects/FidelityFX-FSR
