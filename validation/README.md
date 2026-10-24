# NightKnight Scale — M1 visual validation

This evidence validates the Mode A Gamescope backend. It is not evidence of a
native NKScale Vulkan renderer, portal/PipeWire capture, or frame generation.

## Test isolation

An existing headless output with a pre-existing window was left untouched. A
separate idle Hyprland headless output was used on a dedicated test workspace.
The harness re-enumerated outputs and mapped the selected headless output to the
current SDL display index immediately before every launch. Its physical mode was
1920×1080 at 60 Hz; logical geometry was 1536×864 at scale 1.25. Connector names,
desktop coordinates, workspace/output IDs, and physical-output modes are omitted.

The harness revalidates the selected output's headless identity, geometry, occupancy,
and SDL index immediately before launch. It maps Gamescope windowed with a
per-process monitor/workspace rule and `no_initial_focus`; only after confirming
the window is on the intended headless output does it request PID-scoped
fullscreen. It captures by the verified Gamescope client's Hyprland `stableId`
using `grim -T`, not by screenshotting the whole output. That keeps host compositor
layers and notifications out of the image; if the toplevel handle is missing or
changes, the run fails closed. It checks physical-mode snapshots and samples the
focused monitor/client before launch, before fullscreen, during the transition,
before capture, and after cleanup. It records only whether the original focused
client remained focused—not its address—and does not claim continuous
input/focus tracing. Process cleanup uses the pidfd opened for the matching
Gamescope command line.
The temporary headless output is not created or removed automatically; this is
intentional so the harness cannot remove an output it did not create.

## Reproduce

Build the application:

```sh
cargo build --release -p nkscale-ui
```

Create or identify an idle virtual output through Hyprland, inspect its current
name with `hyprctl monitors`, and pass that exact name. Do not use a physical
output or an occupied output. Example:

```sh
python scripts/headless_fsr_validation.py \
  --monitor HEADLESS-N \
  --filter fsr \
  --source 1280x720 \
  --target 1920x1080 \
  --sharpness 6 \
  --output-dir "${TMPDIR:-/tmp}/nkscale-validation-runs"
```

The comparison cases use the same command with `--filter linear`, or FSR
sharpness values `0`, `6`, and `20`. The optional `--disable-lsfgvk` flag sets
`DISABLE_LSFGVK=1` only for the Gamescope test process and never edits the
user's LSFG-VK configuration. Each invocation creates a timestamped directory
under the requested `--output-dir` (default: `validation/runs/`) with `result.json`,
the exact Gamescope argv, sanitized logs/metadata, and the captured PNG. Use a
scratch directory outside the checkout for a clean source-provenance record; if
you write to the checkout, later runs correctly report that the worktree is dirty.
The 4K-target replay uses
`--start-timeout 20`; the default 8-second timeout once expired before mapping,
then the longer retry succeeded. This is a bounded startup allowance, not a
performance measurement.

## Captured cases

Ten distinct matrix cases produced valid Gamescope-toplevel captures on a
dedicated headless output; each PNG is 1920×1080, matching the headless host mode. The
foreign-toplevel capture excludes host compositor overlays. There are 11 run
records because the initial 4K-target attempt reached the default 8-second mapping
timeout; a 20-second retry captured the chart. The failed attempt remains in the
record with no capture. Exact requested dimensions, Gamescope options, selected
filter, capture result, window state, sanitized version/output-class metadata,
and limitations are in each run's `result.json` and `gamescope.log`. Path tokens
in the recorded argv use `$REPO` and `$RUN_DIR` placeholders. Logs and diagnostics
redact physical connector/mode data, coordinates, GPU model, process/runtime IDs,
and home-directory paths; the selected headless mode and capture dimensions remain.

| Case | Requested source → Gamescope target | Filter |
|---|---:|---|
| Native-size reference | 1920×1080 → 1920×1080 | linear |
| 720p bilinear reference | 1280×720 → 1920×1080 | linear |
| FSR, strongest sharpening | 1280×720 → 1920×1080 | fsr, sharpness 0 |
| FSR comparison | 1280×720 → 1920×1080 | fsr, sharpness 6 |
| FSR, least sharpening | 1280×720 → 1920×1080 | fsr, sharpness 20 |
| 900p compatibility | 1600×900 → 1920×1080 | fsr, sharpness 6 |
| 1440p target request | 1920×1080 → 2560×1440 | fsr, sharpness 6 |
| Ultrawide target request | 1920×1080 → 2560×1080 | fsr, sharpness 6 |
| 4K target request | 1920×1080 → 3840×2160 | fsr, sharpness 6 |
| LSFG isolation check | 1280×720 → 1920×1080 | fsr, sharpness 6; LSFG-VK disabled for this process |

`comparison.png` is a labeled visual contact sheet of the native-size, linear
720p→1080p, and FSR sharpness 0/6/20 captures. The original 1920×1080 images
remain in their run directories. `results.csv` is the per-case machine-readable
summary. `image-differences.json` records deterministic screenshot differences;
these are pixel comparisons, not performance measurements.

## What the evidence establishes

The installed Gamescope 3.16.31 help describes `--filter fsr` as AMD FidelityFX
Super Resolution 1.0. The exact `--filter fsr` and `--sharpness` arguments are
recorded for each run. Gamescope created WSI swapchains, the NKScale chart was
visible in the captured Gamescope surface, and the FSR captures differed from
the same-source linear captures. Sharpness 0/6/20 also produced distinct
pixel output. This is operational output evidence for Gamescope's FSR path.

Gamescope did not expose a shader binary, dispatch identifier, or other
shader-level trace in these logs. Therefore the captures and pixel differences
are not claimed as independent proof of a particular EASU/RCAS shader dispatch.

## LSFG-VK warning

The user configuration contains a `gpu` key in an LSFG-VK profile, which
produces `lsfg-vk: Unknown key in profile section: gpu`. The warning appears in
the default Gamescope run log, but WSI swapchains and the chart capture still
succeed. The harness also ran the same FSR/sharpness case with the layer
explicitly disabled for that process using `DISABLE_LSFGVK=1`: the warning was
absent from the Gamescope log. The paired captures were nearly identical but
not pixel-identical (225 luma pixels differed); no cause is attributed to the
layer or warning. No global or user LSFG-VK configuration was changed. This
conclusion applies to the tested static chart path; it does not claim the profile
is valid for other applications.

## Performance and resolution limitations

Gamescope's requested targets above 1920×1080 were launched and captured on a
1920×1080 headless host. Thus the 2560×1440, 2560×1080, and 3840×2160 values are
requested Gamescope targets, not independently measured physical scanout or
capture-buffer dimensions. In particular, the 4K run is not a 4K screenshot and
did not use or alter the physical 4K display. The ultrawide run likewise does
not independently verify a 2560×1080 output buffer.

The harness requested a Gamescope stats path, but no stats file was emitted in
any run. No reliable FPS, frame-time, GPU/VRAM utilization, CPU utilization,
dropped-frame, or end-to-end latency measurement is available. Gamescope also
reported that `CAP_SYS_NICE` was unavailable and normal scheduling priority was
used. No performance or latency claim is made.

The only implemented backend under validation is Gamescope Mode A. Native
Vulkan processing and portal/PipeWire capture remain future work and were not
started as part of M1 validation.
