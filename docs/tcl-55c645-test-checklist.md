# TCL 55C645 on-device validation checklist

**Purpose:** first physical-TV evaluation of the NightKnight Scale debug APK. This checklist is not a claim that the TV has been tested. Record the TV firmware/build, Android/Google TV version, APK version/hash, selected display mode, and ambient/room conditions with each run.

## Before testing

- [ ] Install the exact debug APK from the Actions artifact on the TCL 55C645; record SHA-256 and APK version.
- [ ] Connect the TV to stable power/network; close other video apps and let the panel cool to a steady idle state.
- [ ] Keep a second device/USB available to reinstall the previous APK if launch or rendering fails.
- [ ] Prepare lawfully owned, unencrypted local clips (H.264/AVC SDR, AAC optional): 480p, 576p, 720p, 1080p; 4:3 and 16:9; 25, 30, 50 and 60 fps; one portrait/90°-metadata sample; one 576p SDR color-bar / skin-tone sample. Record `ffprobe` codec, coded size, display aspect ratio, sample aspect ratio, frame rate, rotation, color primaries, transfer, matrix and range for each file.
- [ ] Do not use EON, Netflix, YouTube protected streams, or external HDMI sources as test inputs. This app is an own-player preview; it cannot intercept those sources.

## Launch and remote controls

- [ ] Launch NightKnight Scale TV from the Google TV app launcher.
- [ ] Verify the panel remains visible, the video is moving behind it, and the remote visibly focuses controls.
- [ ] Select each profile and confirm the shown profile and effect change: **OFF** (bilinear), **QUALITY** (NR + EASU + RCAS), **BALANCED** (EASU + RCAS), **PERFORMANCE** (EASU only).
- [ ] Move sharpness to a distinctive value (for example 9), exit/relaunch the app, and verify both preset and sharpness restore.
- [ ] Open the diagnostics dialog with the remote, move focus to Close, dismiss it, and verify focus returns to the prior control.
- [ ] Pause/resume the clip with the remote. Verify the picture freezes and resumes without leaving the activity.

## Local file selection and playback

For each prepared clip:

- [ ] Use **Open local video…** and select the exact file using the system picker. If the device shows the platform Photo Picker fallback, confirm the video appears there; a full document browser is not guaranteed on every TV build.
- [ ] Verify the player reports the expected decoded dimensions and that moving frames are visible after selection.
- [ ] Compare the displayed geometry to the source's display aspect ratio. Verify 4:3 is letter-/pillar-boxed rather than stretched, widescreen is not cropped, and portrait/rotation content is upright. Record any pixel-aspect-ratio mismatch separately from coded dimensions.
- [ ] Observe color bars, grayscale steps, black level, and skin tones in OFF and each upscaling profile. Record clipping, range shifts, tint, banding, or orientation changes. Do not infer HDR fidelity from SDR clips.
- [ ] Verify the diagnostic OES upload cadence, render callback rate, frame interval, decoder lag notifications, estimated coalesced SurfaceTexture callbacks, renderer name, and source/output/surface sizes. These are diagnostics, not all hardware decoder counters.
- [ ] For 25/30/50/60 fps content, record the file's probed nominal rate, the diagnostic's OES-upload cadence, render callback cadence, and visible judder/duplicate frames. Do not call app callback FPS display-present FPS.
- [ ] Repeat the local clip selection after one process restart. The SAF grant may persist only when the provider supplies a persistable grant; the Android TV Photo Picker fallback can be session-scoped and require re-selection after restart.

## Resolution, performance, and thermal run

- [ ] Test at the TV's normal 4K display mode. Record the app surface, decoded input and processed-target dimensions for every source.
- [ ] For 480p/576p inputs, confirm the multi-pass EASU target reaches the aspect-fit output within GLES texture limits; inspect the RGBA render-target estimate against the 128 MiB policy budget, verify no OOM/GL framebuffer error/crash/scaling seam, and remember that the estimate excludes decoder and driver allocations.
- [ ] Run OFF, PERFORMANCE, BALANCED and QUALITY for at least 10 minutes each on the same 25/30 fps source. Record callback FPS and frame intervals at start and end, lag events, visible stutter, app restarts, device heat, and any TV thermal warnings.
- [ ] Run 50/60 fps samples separately and note whether the decoder/display can sustain them; do not assume panel refresh matching from the file frame rate.
- [ ] Re-open diagnostics after the run and capture a photo/screenshot with the mode and counters visible. Keep the source clip and TV configuration identical for comparisons.

## Pass/fail notes

A run is **not a pass** if the app crashes, decoder never yields a frame, dimensions are materially wrong, 4:3/portrait content is distorted, remote focus is lost, persisted preferences are not restored, or a sustained thermal warning occurs. Save logcat, APK hash, screenshots, clip probe data, firmware/build, start/end metrics, and exact reproduction steps for every failure.

## Known unverified items before hardware testing

- No TCL 55C645 has been available to this development session; emulator results are not hardware results.
- Current player is framework `MediaPlayer`, not Media3/ExoPlayer; the exact hardware codec chosen by the TV is not exposed by the current diagnostics.
- Interlaced 576i deinterlacing and HDR/colorimetry validation are not implemented. This checklist uses progressive SDR clips only unless a future interlace-capable path is explicitly added.
- Frame-coalescing and callback-rate counters are renderer-side estimates; verify motion visually and do not treat them as an exact decoder or display-present drop counter.
