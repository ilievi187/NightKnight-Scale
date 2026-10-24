# Roadmap and acceptance gates

This roadmap separates working features from future research. A milestone is not complete because its documentation or issue exists.

## M1 — Working foundation

- [x] Public repository and five GitHub milestone records created.
- [x] Rust workspace, core library, first control UI implementation, and test-pattern path added locally.
- [x] Gamescope argv contract includes target SDL display, source/output dimensions, FSR 1.0, fit/integer modes, sharpness, and safe process boundary.
- [x] GUI builds and launches on the target Wayland session.
- [x] Vulkan adapter and SDL display list verified at runtime.
- [x] Start the built-in test application via Gamescope FSR on the isolated test display; Gamescope created a WSI swapchain and supervised the child.
- [x] Capture and inspect the deterministic test pattern from the live Gamescope window; compare native-size, linear 720p→1080p, and FSR sharpness 0/6/20 outputs.
- [x] Exercise the requested 720p, 900p, 1440p-target, ultrawide-target, and 4K-target cases on an isolated headless output; distinguish requested targets from measured 1920×1080 host captures.
- [x] Record pixel-difference evidence, environment/version/output metadata, process-scoped cleanup, and limitations; no unsupported performance claims.
- [x] Run local CI-equivalent checks and push the validation branch; recheck PR #2's live CI after each follow-up commit.
- [ ] Obtain human PR review and reconcile Issue #1's unverified high-resolution target-buffer dimensions before closing M1.

Completion requires the runtime checks above. The milestone is still in progress until they pass.

## M2 — Native rendering

- Research and select a Vulkan implementation and source-compatible FSR 1.0 EASU/RCAS shader implementation.
- Compare output against a versioned reference-image corpus for 720p, 1080p, ultrawide, and 4K cases.
- Add modular filter interfaces and integer/nearest/bilinear/bicubic/Lanczos options only after correctness tests.
- Add GPU timestamp queries and report instrumentation scope, frame pacing, resource lifecycle, and dropped frames.
- Evaluate DMA-BUF import and explicit synchronization; retain a tested copy fallback.

## M3 — Wayland capture

- Implement xdg-desktop-portal ScreenCast session flow and user-consented source selection.
- Consume PipeWire video streams, negotiate formats, and manage node/session teardown and portal revocation.
- Present processed frames in a selected output surface without claiming replacement of the host compositor output.
- Detect and prevent self-capture feedback; document compositor-dependent cases.
- Test permissions, capture FPS, dropped buffers, and latency on a declared compositor matrix.

## M4 — Polished application

- Named profiles/presets, user-configurable hotkeys, accessible controls, monitor targeting, and better recovery.
- Clear dependency diagnostics, desktop integration, packaging work, and error reporting.
- Keep telemetry nonblocking and expose only values measured by a real source.

## M5 — First prerelease

- Linux x86_64 build plus launcher/desktop entry and reproducible build instructions.
- Pre-release only after Mode A and the chosen core experience are demonstrated on declared hardware.
- SHA-256 checksums, accurate notes, supported compositor/GPU matrix, reproducible validation results, and known limitations.
- Do not publish a stable release until core behavior is proven.

## Future, explicitly not V1

Temporal upscaling requires its needed motion/depth inputs. Frame generation is separate from spatial FSR and may not be claimed without a real implementation and validated pacing. Anime4K, video players, HDR, game-launcher integrations, streaming, and additional compositors remain future work.
