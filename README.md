# NightKnight Scale (NKScale)

Open-source picture-scaling experiments, with Android TV now the priority and the existing Linux Gamescope prototype preserved as a separate supported path. The Linux path delegates game composition and FSR 1.0 to the installed Gamescope compositor; NKScale does not claim that this backend is a native NKScale renderer.

## Current state

This is an early prototype. The Android TV GLES app in `android/` packages four deterministic H.264 clips. Android MediaPlayer decodes a target-matched clip to SurfaceTexture/OES, copies each frame into an RGBA texture, then runs EASU + RCAS. A generated chart remains as fallback, and a TV remote switches between enhanced and bilinear output. Hosted Android TV emulator CI verified playback, changing decoded frames, pause, remote controls and an A/B pixel difference. This is an in-app demo, not a system-wide enhancement layer. The emulator uses Google SwiftShader software rendering, so physical TV and target GPU behavior remain unverified. The existing Linux Rust GUI, Vulkan adapter probe, SDL display enumeration, Gamescope launcher, profile I/O, and deterministic test pattern are preserved. The Linux Mode A 1280×720→1920×1080 FSR comparison has been visually captured on an isolated headless output and pixel-compared with the linear path; exact shader dispatch and performance telemetry remain unverified. PR #2 carries the Linux validation work; check its live CI and human-review state before merging. Issue #1 remains open because actual high-resolution target-buffer dimensions are not verified.

Implemented scope and validation results are tracked in [the roadmap](docs/roadmap.md) and [validation ledger](docs/validation.md). Do not interpret the aspirational roadmap as shipped functionality.

## Android TV prototype

The Android demo selects one of four 6-second H.264/YUV420p/30fps clips for an approximately 2×-per-axis upscale. MediaPlayer feeds a SurfaceTexture/OES input, which the GLES pipeline copies into an RGBA texture before EASU and a separate RCAS pass. The FSR1-derived shaders include AMD attribution and the MIT license. A D-pad switches **Original · Bilinear** and **Enhanced · FSR 1.0**, controls test-video pause/play, and adjusts RCAS sharpness. Diagnostics report display/source/target/surface dimensions, GL renderer/version, decoded frame count, render callback FPS, and a sparse CPU-submit-to-`glFinish` sample. This remains a foreground app demo, not a system-wide enhancement layer.

```sh
cd android
gradle testDebugUnitTest assembleDebug
```

The Android build is pinned to Android Gradle Plugin 8.13.2, Kotlin 2.3.0, Gradle 8.13, JDK 17, and compile SDK 36. GitHub Actions run `40784574729` passed Linux tests, Android JVM/build tests, and H.264 video playback in an API 36 Android TV 1080p emulator with Google SwiftShader GLES 3.0. The same paused frame produced different enhanced and bilinear chart pixels. The debug APK was downloaded and hash/container-verified. This is software-rendered emulator evidence, not physical-TV or target-GPU validation. See [`android/README.md`](android/README.md) for exact evidence and limits.

## Mode A: launch an app through Gamescope

The control UI launches a command in a nested Gamescope session and passes the selected SDL display index, game render size, output size, filter, scale mode, and FSR sharpness. In this mode, Gamescope performs the scaling. It does not change Hyprland's physical output or add rendering performance to a game.

The built-in test pattern is the default launch command, so the basic path can be exercised without an installed game:

```sh
cargo run --release -p nkscale-ui
```

Select a display, leave the default FSR 1.0 filter selected, choose source/output dimensions, then press **Start scaling**. The UI starts Gamescope as a child process; **Stop session** terminates only that session. Use a windowed session first if you do not want the Gamescope surface to cover a display.

You can also run the backend directly:

```sh
# Re-enumerate first; never reuse an SDL index after display changes.
: "${SDL_DISPLAY_INDEX:?Set from fresh nk-scale --diagnostics output}"
env SDL_VIDEODRIVER=wayland gamescope --backend sdl --display-index "$SDL_DISPLAY_INDEX" \
  --nested-width 1280 --nested-height 720 \
  --output-width 1920 --output-height 1080 \
  --scaler fit --filter fsr --sharpness 6 \
  --fullscreen --expose-wayland -- \
  ./target/release/nk-scale --test-pattern
```

## Headless visual validation

For isolated tests, use the safety-checked harness rather than a hard-coded SDL index:

```sh
cargo build --release -p nkscale-ui
python scripts/headless_fsr_validation.py \
  --monitor HEADLESS-N --filter fsr \
  --source 1280x720 --target 1920x1080 --sharpness 6 \
  --output-dir "${TMPDIR:-/tmp}/nkscale-validation-runs"
```

The harness verifies that the named output has headless-only identity metadata,
revalidates its geometry and SDL index immediately before launch, refuses occupied
outputs, and maps Gamescope windowed with `no_initial_focus`. It requests
fullscreen only after the process/window is verified on the target. Focus/client
identity is sampled at checkpoints but not continuously. It captures only the
test output and cleans up the matching Gamescope process through a pidfd. It
expects an idle Hyprland `HEADLESS-*` output; it deliberately does not create or
remove outputs. See [`validation/README.md`](validation/README.md) for the matrix
and limitations.

Check `gamescope --help` on your installation before using flags from examples; the application detects the installed version but does not install or replace it.

## Build prerequisites

- Linux with a running Wayland session (Hyprland is the primary development target).
- Rust toolchain 1.95 or newer.
- SDL2 development files, Vulkan loader/driver, and the XKB/Wayland/XCB development headers needed by eframe/winit.
- Gamescope for Mode A. Gamescope is an external runtime dependency, not bundled or renamed by NKScale.

On Arch-derived systems, install dependencies only after reviewing your system's package manager plan; NKScale does not install packages or edit compositor/driver configuration. Typical package names include `base-devel`, `pkgconf`, `sdl2`, `wayland`, `libxkbcommon`, `libxcb`, and `vulkan-icd-loader`; Gamescope is a separate package.

## What the prototype reports

The application probes Vulkan adapters through wgpu and enumerates outputs through SDL2, matching the display index passed to Gamescope's SDL nested backend. The current build does not collect capture/output FPS, GPU utilization, frame drops, or end-to-end latency; the UI marks those values unavailable instead of inventing them.

Run a support probe without opening the GUI:

```sh
cargo run -p nkscale-ui -- --diagnostics
```

The per-user desktop launcher and icon can be installed after building with `bash packaging/install-user.sh`; this writes only under `~/.local` and does not install system packages or touch compositor settings. See [the validation ledger](docs/validation.md) for the exact tested configuration and remaining gates.

## Architecture and limitations

- **Mode A — Gamescope launch:** implemented first. Nested fullscreen is a compositor-managed surface. It is not a mirror that replaces the physical output.
- **Mode B — Screen capture:** planned. Future capture must use the desktop portal's ScreenCast permission flow and PipeWire; it must not bypass user consent. A monitor capture that includes the presentation window can feed the output back into itself.
- **Native Vulkan processing:** planned separately from the control UI and Gamescope path. DMA-BUF import and explicit synchronization require negotiated formats/modifiers and lifecycle handling; no zero-copy support is claimed today.
- **X11:** not a supported target for this milestone.

See [architecture](docs/architecture.md) for the trust boundary and [roadmap](docs/roadmap.md) for stage gates.

## License

Original NKScale code is MIT licensed. Third-party components retain their own licenses; see [CONTRIBUTING.md](CONTRIBUTING.md) and the source links in the architecture document.
