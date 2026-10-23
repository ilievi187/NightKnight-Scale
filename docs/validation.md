# Milestone 1 validation ledger

This ledger distinguishes a successful build/launch from a verified image-quality result. It records only checks actually run on the development host.

## Build and automated checks

The following checks passed after the UI layout and status-label fixes:

```sh
cargo fmt --all -- --check
cargo test --workspace
cargo clippy --workspace --all-targets -- -D warnings
desktop-file-validate assets/nightknight-scale.desktop
bash -n packaging/install-user.sh
```

The workspace has seven automated tests: six core launcher/profile/display-label tests and one UI status-label test. The desktop file validator returned success with no warnings after the category was narrowed to `Game;`.

## Runtime discovery

`cargo run -p nkscale-ui -- --diagnostics` completed successfully. It detected:

- Gamescope 3.16.31.
- Vulkan adapter `AMD Radeon RX 7900 XT (RADV NAVI31)`, discrete GPU, Mesa `26.2.5-arch1.1`.
- SDL display 0: HDMI-A-1, 3840×2160 at 60 Hz.
- SDL display 1: DP-1, 1920×1080 at 500 Hz.
- SDL display 2: HDMI-A-2, 2560×1080 at 180 Hz.
- SDL display 3: HEADLESS-1, 1920×1080 at 60 Hz.

The host also emitted an `lsfg-vk` profile warning (`Unknown key in profile section: gpu`) during Vulkan initialization. Vulkan adapter enumeration still succeeded. This warning is environmental and has not been attributed to NKScale.

The GUI was launched as a real Wayland window and the screenshot at `/home/nightknight/.hermes/cache/scratch/nk-scale-ui-layout-final.png` was inspected. It showed the source, output, upscaling, runtime, profile/action controls, and the explicit Gamescope-backend disclaimer. This scratch screenshot may be pruned by the host and is not a release artifact.

## Gamescope FSR launch smoke test

The test used SDL display index 3 (the isolated Hyprland `HEADLESS-1` output), with 1280×720 nested input and 1920×1080 output:

```sh
gamescope --backend sdl --display-index 3 \
  --nested-width 1280 --nested-height 720 \
  --output-width 1920 --output-height 1080 \
  --scaler fit --filter fsr --sharpness 6 \
  --fullscreen --expose-wayland -- \
  ./target/debug/nk-scale --test-pattern
```

Observed: Hyprland listed a live Gamescope surface on monitor 3; Gamescope started its reaper-managed test child; the Gamescope WSI created a 3-image swapchain; and stopping the Gamescope parent caused the reaper to stop its child. No physical monitor or existing game was targeted.

Not yet verified: the rendered test-pattern pixels were not visually inspected. The headless output was covered by the dedicated browser window, so the attempted compositor screenshot captured that window rather than Gamescope. The launch passed the `fsr` filter option, but this smoke test is not image-based evidence of FSR output quality or correctness. No performance, latency, GPU-utilization, dropped-frame, or VRAM measurements were made. Do not claim those results.

## Remaining M1 gates

- Obtain a clean visual capture of the built-in pattern from the Gamescope surface and compare it with the unscaled reference.
- Exercise representative source/output mode pairs (720p, 1080p, ultrawide, 4K) where hardware permits.
- Run repository CI on the pushed commit and record its remote result.

Until those gates pass, M1 remains open and the prototype is not a verified release.
