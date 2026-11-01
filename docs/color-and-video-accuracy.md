# Colorimetry, Video Geometry & Video Quality Analysis

## Mission 9 Architectural Ledger & Handoff to LUNA

**Date:** November 2026

**Component:** NightKnight Scale Android TV Pipeline
**Target device:** TCL 55C645 (requested physical test target; its SoC, GLES renderer, refresh modes, and GPU timing have not been verified by this development session)

---

## 1. Executive Summary

This document records the geometry policy, metadata fields the Android media APIs may expose, and unresolved color/HDR validation. The PAL/NTSC and rotation cases below are deterministic policy fixtures and emulator/software checks, not evidence from a physical TCL 55C645.

Video geometry, Display Aspect Ratio (DAR) calculation, viewport fitting, and remote override controls are implemented in `VideoGeometry`, `RenderPolicy`, and `MainActivity`. Metadata availability is provider/device-dependent; when SAR/DAR is missing, the current policy uses a 4:3 heuristic for recognized 720×576 and 720×480 dimensions, so anamorphic 16:9 material without metadata requires an explicit user override.

The EASU/RCAS filter equations were not changed in this GPU pass; RCAS gained only a viewport-origin offset for direct aspect-fit output. The GLES port retains EASU's 12-tap adaptive kernel/anti-ringing clamp and RCAS's five-sample cross/lobe limit, but uses GLES texture fetches, finite-value guards, and GLSL division instead of AMD's platform-specific reciprocal approximations. Treat it as algorithmically aligned, not bit-exact or perceptually certified; CI shader compilation does not prove image-quality equivalence.[1]

---

## 2. Video Geometry & Aspect Ratio Invariants

### 2.1 The PAL & NTSC Non-Square Pixel Problem

Standard Definition (SD) broadcast and DVD video is stored with non-square pixels:

| Standard | Coded Dimensions | Intended DAR | Exact SAR (PAR) | Effective Display Width $\times$ Height |
| :--- | :--- | :--- | :--- | :--- |
| **PAL 4:3 Standard** | 720 $\times$ 576 | 4:3 (1.3333) | 16:15 ($\approx$ 1.0667) | 768 $\times$ 576 |
| **PAL 16:9 Anamorphic** | 720 $\times$ 576 | 16:9 (1.7778) | 64:45 ($\approx$ 1.4222) | 1024 $\times$ 576 |
| **NTSC 4:3 Standard** | 720 $\times$ 480 | 4:3 (1.3333) | 8:9 ($\approx$ 0.8889) | 640 $\times$ 480 |
| **NTSC 16:9 Anamorphic** | 720 $\times$ 480 | 16:9 (1.7778) | 32:27 ($\approx$ 1.1852) | 853.33 $\times$ 480 |

Prior to Mission 9, the player used raw `codedWidth` $\times$ `codedHeight` directly in `RenderPolicy.contentViewport`. This resulted in:
- PAL 4:3 content displayed at 5:4 (1.25), horizontally squashed by $\approx$ 6.25%.
- PAL 16:9 anamorphic content displayed at 5:4, severely distorted and squashed by $\approx$ 29.7%.

### 2.2 Solution Implemented in Mission 9

1. **`VideoGeometry` Engine:** Reads SAR, rotation, and color fields when the active `MediaExtractor`/`MediaMetadataRetriever` provider exposes them. It does not parse H.264 bitstreams itself. If SAR/DAR is unavailable, 720×576 and 720×480 default to a 4:3 heuristic; this cannot distinguish anamorphic 16:9 without metadata, so the user override remains the recovery path.
2. **Aspect Ratio Mode Override:** Users can toggle via remote between:
   - `Auto (SAR/DAR)`
   - `Force 16:9`
   - `Force 4:3`
   - `1:1 Square (Raw)`
3. **Exact DAR Aspect Fitting:** `RenderPolicy.contentViewport` computes viewport dimensions directly from the Display Aspect Ratio without intermediate truncation:
   - On a 1080p panel: PAL 4:3 $\to$ `PixelRect(240, 0, 1440, 1080)` (240px black pillarbox bars on left/right).
   - On a 1080p panel: PAL 16:9 $\to$ `PixelRect(0, 0, 1920, 1080)` (full screen 16:9).
   - On a 4K panel (3840 $\times$ 2160): PAL 4:3 $\to$ `PixelRect(480, 0, 2880, 2160)`.
   - On a 4K panel: PAL 16:9 $\to$ `PixelRect(0, 0, 3840, 2160)`.

### 2.3 Primary-source verification (November 1, 2026)

AMD's reference source confirms EASU's edge-direction/adaptive 12-tap resolve and anti-ringing clamp, and RCAS's center-plus-cross resolve with a bounded negative lobe. The local GLES port preserves those filter structures; it intentionally does not include AMD's optional RCAS denoise permutation, and its guards/reciprocal operations are not a bit-for-bit port.[1][2]

Android documents track SAR fields and gives display aspect as the visible crop ratio multiplied by SAR; MediaCodec crop right/bottom coordinates are inclusive. The metadata reader now derives DAR from inclusive crop bounds and SAR when crop metadata is present, while keeping the coded dimensions for texture allocation. If a provider omits crop and SAR, the existing PAL/NTSC heuristic or square-pixel fallback remains in effect.[3][4][5]

The tests cover synthetic crop/SAR arithmetic and the emulator confirms GLES shader compilation. They do not establish that every Android provider exposes these fields, that a physical decoder applies crop transforms identically, or that output matches AMD's reference images; those remain device/reference-image validation items.

---

## 3. Portrait & Rotation Metadata

### 3.1 Transposition and Decoder Inconsistencies

Smartphones and social video often record in portrait:
- Coded as 1920 $\times$ 1080 with 90° or 270° rotation metadata in the `moov/trak/tkhd` matrix.
- Or coded directly as 1080 $\times$ 1920 with 0° rotation.

In Android's media stack:
- Hardware decoders may apply the rotation flag to the `BufferQueue` buffer transform (`NATIVE_WINDOW_TRANSFORM_ROT_90`). When `SurfaceTexture.updateTexImage()` is called, `SurfaceTexture.getTransformMatrix()` incorporates this 90° rotation.
- Software decoders or certain vendor MediaCodec HAL implementations leave the buffer transform as identity, reporting rotation only through `MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION`.

### 3.2 Solution Implemented in Mission 9

`FsrDemoRenderer` checks whether `textureTransform` already has rotation:
```kotlin
val matrixHasRotation = when (videoGeometry.normalizedRotation) {
    90, 270 -> abs(textureTransform[1]) > 0.5f || abs(textureTransform[4]) > 0.5f
    180 -> textureTransform[0] < -0.5f
    else -> false
}
if (!matrixHasRotation && videoGeometry.normalizedRotation != 0) {
    // Apply rotation around texture center (0.5, 0.5)
    ...
}
```
And `sourceFramebuffer` is allocated with `orientedSize` (e.g. 1080 $\times$ 1920), ensuring portrait frames are upright and properly pillarboxed (`PixelRect(656, 0, 608, 1080)` on 1080p).

---

## 4. Colorimetry, Dynamic Range & Transfer Functions

### 4.1 Limited-Range (Studio) vs Full-Range SDR

Most consumer video (broadcast, Blu-ray, DVD, web H.264) uses **limited range** (studio swing):
- Luma ($Y$): 16 to 235 (219 levels)
- Chroma ($Cb, Cr$): 16 to 240 (224 levels)
- Headroom (236–254) and footroom (1–15) are reserved for overshoot/undershoot filter ringing.

PC and graphics content use **full range** (0 to 255).

**Current validation boundary:**
The normal video path samples an Android `samplerExternalOES` texture and copies the result into RGBA before filtering. Android's decoder/graphics pipeline supplies the color conversion, but the exact range/matrix behavior depends on source metadata and the device implementation; it has not been validated on the TCL 55C645. The app reports metadata fields when available but does not currently apply a BT.601/BT.709 correction or HDR transfer conversion. `GL_CLAMP_TO_EDGE` only clamps texture coordinates at image boundaries; it does not correct range, matrix, or filter ringing.

### 4.2 BT.601 vs BT.709 conversion boundary

BT.601 and BT.709 use different Y′CbCr coefficients. The platform's OES conversion and output display pipeline are expected to interpret the buffer metadata, but this path has not been measured or visually verified on the target TV. The current app records the reported color standard/range/transfer when available; it makes no claim that its GLES intermediates preserve HDR or perform a BT.601-to-BT.709 conversion.

### 4.3 Future color-correction work

Do not insert a guessed RGB matrix into the FSR chain. A valid correction requires a verified source Y′CbCr matrix/range, destination primaries/transfer, operation domain (linear vs gamma-coded), and a device/reference image test. No correction matrix is implemented in this branch. Any future color pass should be separately selectable, preserve FSR input semantics, and include known color-bar fixtures and output-difference tests.

### 4.4 HDR fidelity claim boundary

No HDR (BT.2020, PQ ST.2084, HLG) conversion is currently performed or claimed. SDR clips are used for the current regression path; physical HDR validation and a verified 10-bit EGL/configuration path remain unimplemented.

---

## 5. Geometry-policy fixtures (unit tests; not hardware measurements)

The following rectangles are deterministic `VideoGeometry`/`RenderPolicy` fixture outputs. They are not visual measurements from the TCL panel, and rotation matrix fallback still requires a physical portrait-clip check.

| Test Scenario | Input Geometry | Target Display | Result |
| :--- | :--- | :--- | :--- |
| **PAL 4:3** | 720 $\times$ 576, SAR 16:15 | 1920 $\times$ 1080 | `PixelRect(240, 0, 1440, 1080)` (pillarbox, 0 distortion) |
| **PAL 16:9** | 720 $\times$ 576, SAR 64:45 | 1920 $\times$ 1080 | `PixelRect(0, 0, 1920, 1080)` (fills screen) |
| **PAL 4:3 on 4K** | 720 $\times$ 576, SAR 16:15 | 3840 $\times$ 2160 | `PixelRect(480, 0, 2880, 2160)` (pillarbox) |
| **PAL 16:9 on 4K** | 720 $\times$ 576, SAR 64:45 | 3840 $\times$ 2160 | `PixelRect(0, 0, 3840, 2160)` (fills screen) |
| **NTSC 4:3** | 720 $\times$ 480, SAR 8:9 | 1920 $\times$ 1080 | `PixelRect(240, 0, 1440, 1080)` (pillarbox) |
| **NTSC 16:9** | 720 $\times$ 480, SAR 32:27 | 1920 $\times$ 1080 | `PixelRect(0, 0, 1920, 1080)` (fills screen) |
| **Portrait 0°** | 1080 $\times$ 1920, SAR 1:1 | 1920 $\times$ 1080 | `PixelRect(656, 0, 608, 1080)` (pillarbox) |
| **Portrait 90°** | 1920 $\times$ 1080 [90°], SAR 1:1 | 1920 $\times$ 1080 | `PixelRect(656, 0, 608, 1080)` (upright pillarbox) |
| **Inverted 180°** | 1920 $\times$ 1080 [180°], SAR 1:1 | 1920 $\times$ 1080 | `PixelRect(0, 0, 1920, 1080)` (landscape) |
| **Letterboxed** | 1920 $\times$ 1080 16:9 | 1440 $\times$ 1080 (4:3) | `PixelRect(0, 135, 1440, 810)` (top/bottom bars) |

## Sources

[1] https://github.com/GPUOpen-Effects/FidelityFX-FSR/blob/master/ffx-fsr/ffx_fsr1.h — AMD GPUOpen FSR 1.0 reference source (ffx_fsr1.h)
[2] https://gpuopen.com/fidelityfx-superresolution — AMD GPUOpen FidelityFX Super Resolution 1
[3] https://developer.android.com/reference/android/media/MediaExtractor — Android MediaExtractor API reference
[4] https://developer.android.com/reference/android/media/MediaFormat — Android MediaFormat API reference
[5] https://developer.android.com/reference/android/media/MediaCodec — Android MediaCodec crop and output-format reference
