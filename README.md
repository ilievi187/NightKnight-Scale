# NightKnight Scale (NKScale)

Linux-first GPU scaling control application. The first usable path delegates game composition and FSR 1.0 to the installed Gamescope compositor; NKScale does not claim that this backend is a native NKScale renderer.

## Current state

This is an early, working-foundation prototype. The Rust GUI, Vulkan adapter probe, SDL display enumeration, Gamescope launcher, profile I/O, and built-in test pattern are implemented locally. The M1 visual FSR-output proof and remote CI result are still open; native Vulkan shader processing and portal-authorized capture remain later milestones.

Implemented scope and validation results are tracked in [the roadmap](docs/roadmap.md) and [validation ledger](docs/validation.md). Do not interpret the aspirational roadmap as shipped functionality.

## Mode A: launch an app through Gamescope

The control UI launches a command in a nested Gamescope session and passes the selected SDL display index, game render size, output size, filter, scale mode, and FSR sharpness. In this mode, Gamescope performs the scaling. It does not change Hyprland's physical output or add rendering performance to a game.

The built-in test pattern is the default launch command, so the basic path can be exercised without an installed game:

```sh
cargo run --release -p nkscale-ui
```

Select a display, leave the default FSR 1.0 filter selected, choose source/output dimensions, then press **Start scaling**. The UI starts Gamescope as a child process; **Stop session** terminates only that session. Use a windowed session first if you do not want the Gamescope surface to cover a display.

You can also run the backend directly:

```sh
gamescope --backend sdl --display-index 0 \
  --nested-width 1280 --nested-height 720 \
  --output-width 1920 --output-height 1080 \
  --scaler fit --filter fsr --sharpness 6 \
  --fullscreen --expose-wayland -- \
  ./target/release/nk-scale --test-pattern
```

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
