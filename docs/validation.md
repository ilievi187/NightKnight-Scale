# Milestone 1 validation ledger

This ledger records observed runtime evidence, not planned checks. The tested
path is Mode A: Gamescope acting as the external compositor/backend. It does not
validate a native NKScale Vulkan renderer or portal/PipeWire capture.

## Environment and isolation

Final captures were made on 2026-10-23 UTC with:

- Gamescope 3.16.31 (GCC 16.2.1).
- Hyprland 0.56.2; kernel `7.2.8-9-omarchy`.
- AMD Radeon RX 7900 XT (`RADV NAVI31`), Mesa `26.2.5-arch1.1`.
- SDL video driver explicitly set to Wayland before display enumeration.

The physical outputs were HDMI-A-1 3840×2160@60 Hz, DP-1 1920×1080@500 Hz,
and HDMI-A-2 2560×1080@179.983 Hz. `HEADLESS-1` was occupied by a pre-existing
window and was left untouched. The test created and used a separate temporary
Hyprland headless output, `HEADLESS-3`, 1920×1080@60 Hz, scale 1.25, on workspace
3. Re-enumeration mapped it to SDL display index 4. The SDL/Hyprland mapping and
complete connector-only output inventory are in `validation/environment.json`;
monitor model/serial strings are intentionally omitted.

The harness rejects outputs that merely have a `HEADLESS-*` name but expose
physical make/model/serial/description data. It revalidates output identity,
geometry, occupancy, physical modes, focus, and the SDL index immediately before
launch. Gamescope starts without a fullscreen request; only after its mapped
window is confirmed on the unchanged headless output does the harness apply
PID-scoped fullscreen. It captures the verified Gamescope foreign toplevel with
`grim -T` using Hyprland's stable window ID, not the whole output; host compositor
layers and notifications are excluded. It terminates only the process pinned by
its pidfd. Focus checkpoints include prelaunch, pre-fullscreen,
fullscreen transition, pre-capture, and post-cleanup; they confirm `DP-1` and the
same focused client at those samples. The client address is not persisted and
continuous input/focus tracing is not claimed. Physical modes were unchanged in
the ten captured runs. There were 11 run records for
10 unique matrix cases: the initial 4K target attempt hit the default 8-second
mapping timeout, then a 20-second retry captured the chart. The failed attempt
is retained without a screenshot. The temporary output is not automatically
removed because the harness must never remove an output it did not create.

The real GUI was also launched as a Wayland window on `HEADLESS-3`; Hyprland
reported class/title `NightKnight Scale`, mapped on monitor 4/workspace 3. The
focused monitor was `DP-1` at the before/after snapshots, and it was closed by
targeting only its own PID. The unfocused output snapshot showed the desktop
background strongly through the window; its cause was not established, so it is
omitted as GUI visual evidence.

## Automated checks

Local checks after the code and harness changes:

```sh
cargo fmt --all -- --check
cargo test --workspace
cargo clippy --workspace --all-targets -- -D warnings
python -m unittest discover -s scripts -p 'test_*.py'
```

The Rust format, tests, and Clippy checks passed. The Python headless-harness
suite passed 23 tests covering physical/occupied-output rejection, authoritative
headless identity and revalidation, verified Gamescope-toplevel capture identity,
volatile SDL index mapping, windowed preflight/PID-scoped fullscreen planning,
pidfd-bound cleanup, focused-client checkpoints, portable result paths, home-path
redaction, and display-identifier redaction. A release build of
`nkscale-ui` succeeded; `nk-scale --diagnostics` detected Gamescope, the Vulkan
adapter, and all current outputs. The capture binary SHA-256 is recorded in each
run's `result.json` and in `validation/environment.json`.

Remote CI status is tracked on PR #2; use its live checks as the source of truth.
Each follow-up commit requires a fresh check before merge.

## Visual FSR test

The reproducible harness is `scripts/headless_fsr_validation.py`. It checks
output identity/idleness, SDL display correspondence, process/window routing,
fullscreen state, physical output modes and focus; captures the Gamescope
surface; records logs and machine-readable results; and performs owned-process
cleanup. See `validation/README.md` for commands and limitations.

The deterministic chart includes 1–8 px checker/edge patterns, fine text,
diagonals, detailed texture, small UI elements, color bars, and a grayscale ramp.
`validation/comparison.png` labels the native-size linear reference, 720p linear
reference, and FSR sharpness 0/6/20 captures. Original captures, per-run argv,
versions and logs are retained under `validation/runs/`. `validation/results.csv`
records 11 attempts for 10 unique cases, including the failed 8-second 4K start;
`validation/image-differences.json` summarizes the six pixel-comparison pairs.

Gamescope 3.16.31's local help identifies `--filter fsr` as AMD FidelityFX
Super Resolution 1.0. The 1280×720→1920×1080 captures were made from the live,
fullscreen Gamescope window. The command selected `--filter fsr`; actual captured
pixels differ from the same-source `linear` path, and the three sharpness values
produce distinct output. This establishes working, observable output from
Gamescope's FSR path. Gamescope did not emit a shader binary, dispatch ID, or
shader-level trace; the evidence is not described as direct proof of a specific
EASU/RCAS shader dispatch.

Full-frame luma comparison results (2,073,600 pixels per capture):

| Comparison | Changed luma pixels | Changed | Mean absolute luma difference |
|---|---:|---:|---:|
| Linear 1280×720→1920×1080 vs FSR sharpness 0 | 652,738 | 31.4785% | 4.8393 |
| Linear 1280×720→1920×1080 vs FSR sharpness 6 | 631,754 | 30.4665% | 4.4442 |
| Linear 1280×720→1920×1080 vs FSR sharpness 20 | 421,627 | 20.3331% | 4.1129 |
| FSR sharpness 0 vs 6 | 327,166 | 15.7777% | 0.4359 |
| FSR sharpness 6 vs 20 | 466,441 | 22.4943% | 0.4217 |
| Default LSFG-VK layer vs process-disabled | 225 | 0.0109% | 0.0001 |

Pixel differences show that the configured filter/sharpness changes the rendered
image; they are not subjective quality scores and do not prove a shader dispatch.
The default-vs-disabled LSFG-VK captures differ at 225 luma pixels (0.0109% of
the frame; mean absolute luma difference 0.00010851). This small difference is
reported without attributing a cause or claiming an LSFG-VK effect.

## Resolution matrix and limits

| Requested source → Gamescope target | Filter | Actual Gamescope toplevel PNG | Result / limit |
|---|---|---|---|
| 1920×1080 → 1920×1080 | linear | 1920×1080 | Native-size reference captured |
| 1280×720 → 1920×1080 | linear | 1920×1080 | Bilinear reference captured |
| 1280×720 → 1920×1080 | FSR, sharpness 0 / 6 / 20 | 1920×1080 | Captured and pixel-compared |
| 1600×900 → 1920×1080 | FSR, sharpness 6 | 1920×1080 | Captured |
| 1920×1080 → 2560×1440 | FSR, sharpness 6 | 1920×1080 | Requested target; no 1440p host buffer measurement |
| 1920×1080 → 2560×1080 | FSR, sharpness 6 | 1920×1080 | Ultrawide target request; 2560×1080 buffer/aspect presentation not independently measured |
| 1920×1080 → 3840×2160 | FSR, sharpness 6 | 1920×1080 | 8s mapping attempt timed out; 20s retry captured. Not a 4K capture; physical 4K output untouched |

Each screenshot is a `grim -T` capture of the verified Gamescope foreign toplevel
and measures 1920×1080; the headless host mode was also 1920×1080. This is not a
readback of Gamescope's internal target buffer. For requested targets above the
headless host's 1920×1080 mode, it must not be presented as proof of actual
1440p, ultrawide, or 4K scanout/buffer dimensions.

## LSFG-VK warning

The default Gamescope log contains `lsfg-vk: Unknown key in profile section:
gpu`. The warning is from the separate LSFG-VK Vulkan layer/configuration, not
from the Gamescope `fsr` filter. Default and process-scoped-disabled FSR6 runs
both created Gamescope WSI swapchains and captured the chart. Setting
`DISABLE_LSFGVK=1` only for the test process removed the warning from the
Gamescope log. The paired captures were nearly identical but not bit-identical
(225 luma pixels differed); no cause is attributed to the layer or warning. No
LSFG-VK configuration was edited. This comparison covers the tested static chart
only; it does not validate that profile for other applications.

## Performance status

No Gamescope stats file was emitted despite requesting a per-run stats path.
No trustworthy FPS, frame-time, GPU/VRAM utilization, CPU utilization,
dropped-frame, or end-to-end latency measurement is available. Gamescope also
reported that `CAP_SYS_NICE` was unavailable and normal scheduling priority was
used. No performance or latency claims are made.

## Milestone status

The requested 720p→1080p visual comparison and the supported resolution request
matrix have been exercised on an isolated headless output, with reproducible
captures and machine-readable evidence. M1 remains pending remote branch CI and
review/issue reconciliation; do not close Issue #1 until those gates are
verified. Native Vulkan processing and portal/PipeWire capture remain future
milestones.
