# NightKnight Scale (NKScale)

Open-source picture-scaling experiments, with Android TV now the priority and the existing Linux Gamescope prototype preserved as a separate supported path. The Linux path delegates game composition and FSR 1.0 to the installed Gamescope compositor; NKScale does not claim that this backend is a native NKScale renderer.

## Current state

This remains an early prototype. The Android TV app in `android/` now provides four named GLES profiles (OFF, QUALITY, BALANCED, PERFORMANCE), persistent preset/sharpness settings, local video selection with SAF/Photo Picker fallback, aspect-fit output, multi-pass EASU to the texture-limited target, RCAS, and a remote-accessible diagnostics dialog. Automated emulator coverage exercises all profiles, preference restore, local 25/30/50/60 fps SDR clips, 576p/portrait shapes and decoded-frame motion. Emulator results are not physical TCL 55C645 evidence and pixel differences do not establish perceptual improvement. The app remains a foreground own-player preview; it cannot intercept EON, other apps, or HDMI. The existing Linux Rust GUI, Vulkan adapter probe, SDL display enumeration, Gamescope launcher, profile I/O and deterministic test pattern are preserved; the Linux path remains a Gamescope delegation, not a claimed native NKScale renderer.

Implemented scope and validation results are tracked in [the roadmap](docs/roadmap.md) and [validation ledger](docs/validation.md). The [acceptance traceability matrix](docs/agent/luna-tv/acceptance-matrix.md) maps each prototype requirement to its concrete check or recorded gap. Do not interpret aspirational roadmap items as shipped functionality.

## Android TV prototype

The app supports bundled test clips and user-selected local video through the TV document picker, with a Photo Picker fallback when Android TV resolves `ACTION_OPEN_DOCUMENT` to a stub. MediaPlayer feeds `SurfaceTexture`/OES; the GL thread copies frames to RGBA and applies the selected bilinear/EASU/RCAS pipeline. OFF maps to bilinear, QUALITY to NR+EASU+RCAS, BALANCED to EASU+RCAS and PERFORMANCE to EASU-only. EASU uses multi-pass stages capped at 2× per axis toward a display-fit target limited by GLES texture edge and a 128 MiB estimated RGBA8 render-target budget. Preset and sharpness persist across process restart. Diagnostics expose input/output sizes, GL renderer/version, cadence, frame intervals, callback coalescing estimates, decoder lag events and CPU/GPU synchronization proxies. The RGBA estimate excludes decoder/driver/compositor allocations. Diagnostics do not expose exact hardware decoder/display dropped-frame counts. Rotation metadata, pixel-aspect ratio, color accuracy and sustained TCL performance still need physical-device validation. This remains an app-owned preview, not a system-wide enhancement layer.

```sh
cd android
gradle testDebugUnitTest assembleDebug
```

The Android build is pinned to Android Gradle Plugin 8.13.2, Kotlin 2.3.0, Gradle 8.13, JDK 17 and compile SDK 36. Mission 4 PR checks on commit `806ec9c` passed Linux formatting/lint/tests and Android build/emulator smoke; they do not cover the uncommitted Mission 5 tree. Mission 5 local build and emulator validation are in progress, and a new CI artifact will only be claimed after the current PR head passes. This remains software-rendered emulator evidence, not physical-TV or target-GPU validation. See [`android/README.md`](android/README.md) and the [acceptance matrix](docs/agent/luna-tv/acceptance-matrix.md) for exact evidence and limits.

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
