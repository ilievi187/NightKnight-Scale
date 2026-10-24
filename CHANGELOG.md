# Changelog

All notable project changes are recorded here. The project has not made a stable release.

## Unreleased — 0.1.0 development

- Created the public project and Rust workspace.
- Established a Gamescope-backed FSR 1.0 launch path as the first usability target.
- Added architectural boundaries for native Vulkan processing and portal-authorized capture; these remain future capabilities.
- Added fail-closed Wayland SDL display identity revalidation before Gamescope launch.
- Expanded the deterministic chart and added an isolated headless Gamescope validation harness with live target/SDL revalidation, windowed preflight, PID-scoped fullscreen only after placement verification, pidfd-bound cleanup, verified foreign-toplevel capture, logs, and machine-readable results.
- Added visual FSR-versus-linear/sharpness comparisons and resolution-request evidence; documented shader-level, high-resolution-capture, and performance-measurement limits.
- Added Python safety/metadata tests to CI and a process-scoped LSFG-VK diagnostic toggle that does not edit user configuration.
