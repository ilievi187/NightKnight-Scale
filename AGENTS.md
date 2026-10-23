# Project instructions for coding agents

- Keep the UI/control plane separate from the live frame-processing process. UI work must not block the rendering path.
- The current Mode A is a Gamescope launcher. Name Gamescope as the renderer in UI, docs, telemetry, and release notes; never present it as an NKScale-native renderer.
- Never claim that a Wayland client replaces the physical output. Fullscreen Gamescope is a compositor-managed nested surface.
- Never bypass xdg-desktop-portal consent or capture protections. Monitor/window capture is not implemented until its portal and PipeWire lifecycle is real.
- Do not fabricate FPS, GPU utilization, latency, dropped-frame, VRAM, or image-quality measurements. Mark unavailable measurements explicitly.
- Do not copy third-party shaders until their source, license, attribution, and reference behavior have been checked. FSR is currently selected through Gamescope's installed implementation.
- Do not edit system, Hyprland, GPU-driver, or boot configuration, and do not install system packages as part of build scripts.
- Preserve a safe argv boundary: parse the user's launch command into executable and arguments; do not pass it to a shell.
- Use SDL display enumeration for the index passed to Gamescope `--display-index`; keep monitor selection explicit and preserve source/output resolution distinction.
- Write a focused failing test before changing Rust behavior, run it red, implement the smallest change, then run the complete workspace test suite.
- Before pushing, run `cargo fmt --all -- --check`, `cargo clippy --workspace --all-targets -- -D warnings`, and `cargo test --workspace`.
- Document tested compositor, driver, display, and resolution precisely. A successful compile is not proof of a successful FSR presentation.
