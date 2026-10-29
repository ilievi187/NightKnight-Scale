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

The Android application lives in `android/` in this repository; it is not a second unrelated project. It is a foreground GLES 3.0 demo with four packaged deterministic H.264 test clips. Android MediaPlayer decodes the selected clip into an app-owned `SurfaceTexture` backed by an external OES texture. A fullscreen GLES copy pass applies the `SurfaceTexture` transform matrix and writes each decoded frame into an RGBA8 2D texture. Four selectable paths are provided: ordinary bilinear presentation, EASU only, EASU+RCAS (FSR 1.0), and a mild 3×3 edge-aware spatial denoise prefilter followed by EASU+RCAS. The NR shader is experimental, spatial-only, and not codec-aware or temporal. A generated chart remains as an input fallback if video surface setup or decoding fails. The display-mode value comes from Android's display API; the GL surface and processed-target sizes are reported separately because logical app dimensions, physical display mode, and maximum GLES texture dimensions can differ.

The shader implementation is a GLSL ES 3.0 port of AMD FidelityFX FSR 1.0's 32-bit EASU and RCAS paths. It uses explicit texel loads in place of the upstream Vulkan GLSL gather callbacks, follows the direction-adaptive 12-tap EASU filter and 2×2 anti-ringing clamp, then applies RCAS in a separate pass. The optional spatial NR prefilter is a separate mild 3×3 edge-aware pass, not a substitute for codec-aware artifact reduction. The source attribution and MIT license are included in `third_party/AMD-FSR-LICENSE.txt`. The EASU/RCAS and auxiliary GLSL programs compile/link with `glslangValidator`. Code-bearing CI run `40834327084` decoded H.264 test frames through SurfaceTexture/OES to GLES on a Google SwiftShader API 36 Android TV AVD, exercised remote pause/resume and confirmed post-resume motion, switched through all four output modes, adjusted RCAS sharpness, and asserted non-identical crops for video motion, filter variants, and sharpness change. Measured output differences are documented in `android/README.md` and the acceptance matrix, but are not perceptual quality scores. The emulator's software renderer is not a hardware GPU benchmark and the output has not been compared against AMD's reference corpus.

The demo's **Original** route is ordinary bilinear texture sampling. The enhanced routes expose EASU-only, EASU+RCAS, and NR-prefiltered EASU+RCAS for controlled in-app comparison. The UI does not hook SurfaceFlinger or Hardware Composer and cannot replace another app's presented image. The foreground activity exits or pauses normally; it does not continue enhancement behind other apps.

There is no MediaProjection, VirtualDisplay, screen-capture service, user-selectable video/TV/IPTV input, codec-aware artifact reduction, deinterlacer, or DRM handling in this prototype. The only NR is the optional mild spatial prefilter described above. Video playback is limited to the app's packaged synthetic test clips. A future consent-based capture implementation must request a fresh system-mediated grant and process only the capture stream delivered to its own surface. Showing that processed stream in this app would be a separate presentation path, not transparent system-wide replacement. Protected/DRM surfaces must remain protected and unsupported content must be reported rather than bypassed.

## References for Android TV and capture

- Android TV launcher, device feature and remote-navigation requirements: https://developer.android.com/training/tv/get-started/create
- Android OpenGL ES `GLSurfaceView` and renderer lifecycle: https://developer.android.com/develop/ui/views/graphics/opengl/environment
- MediaProjection capture model and user-consent flow: https://developer.android.com/media/grow/media-projection
- MediaProjection foreground-service requirements: https://developer.android.com/develop/background-work/services/fgs/service-types
- AMD FidelityFX FSR 1.0 source and algorithm: https://github.com/GPUOpen-Effects/FidelityFX-FSR
