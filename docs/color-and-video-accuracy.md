# Colorimetry, Video Geometry & Video Quality Analysis

## Mission 9 Architectural Ledger & Handoff to LUNA

**Date:** November 2026  
**Component:** NightKnight Scale Android TV Pipeline  
**Target Panel:** TCL 55C645 (QLED 4K, 60 Hz / 120 Hz DLG, GLES 3.2 Mali-G57 / MediaTek SoC)

---

## 1. Executive Summary

This document records the static and dynamic analysis of colorimetry, range clipping, transfer functions, Sample Aspect Ratio (SAR), and rotation handling in the NightKnight Scale Android TV GLES rendering pipeline.

All video geometry, Display Aspect Ratio (DAR) calculation, pillarbox/letterbox viewport math, and UI/remote controls have been implemented cleanly in the application layer (`VideoGeometry`, `RenderPolicy`, `MainActivity`).

As required by Mission 9 multi-agent protocol, **no shader mathematics authored by LUNA have been modified**. All findings requiring future shader-stage processing are formalized below for LUNA's shader engineering roadmap.

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

1. **`VideoGeometry` Engine:** Automatically extracts SAR from container/bitstream metadata using `MediaExtractor` and `MediaMetadataRetriever`. If SAR is omitted in an MPEG-4 / H.264 bitstream, PAL 576p and NTSC 480p default to standard 4:3 DAR.
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

**Observation in `GL_TEXTURE_EXTERNAL_OES`:**
When `copy_external.frag` samples `samplerExternalOES`, the underlying GLES driver / hardware video compositor performs the YUV-to-RGB conversion automatically.
- Android's MediaCodec decoder passes color range metadata attached to the buffer.
- `GL_CLAMP_TO_EDGE` on all 2D intermediate textures prevents edge ringing and black level clipping.
- **Diagnostics:** The UI now displays the probed color standard (`BT.709`, `BT.601 PAL`, `BT.601 NTSC`, `BT.2020`) and color range (`Limited range`, `Full range`).

### 4.2 BT.601 vs BT.709 Color Primaries Shift

- ITU-R BT.601 ($K_r = 0.299, K_b = 0.114$) is the color space for PAL and NTSC SD content.
- ITU-R BT.709 ($K_r = 0.2126, K_b = 0.0722$) is standard for HD and 4K SDR panels.

When an SD clip encoded in BT.601 is decoded and displayed directly on a BT.709 TV panel without a color-space conversion matrix, a subtle color shift occurs:
- Pure greens appear slightly too yellowish.
- Reds and skin tones appear slightly over-saturated.

### 4.3 Recommendation for LUNA (GPU Shader Roadmap)

If LUNA wishes to implement an optional color matrix correction in the GLES shader pipeline, she can insert a 3x3 primary conversion matrix into a fragment shader pass between `copy_external` and EASU:

$$\begin{bmatrix} R_{709} \\ G_{709} \\ B_{709} \end{bmatrix} = \begin{bmatrix} 1.0 & 0.0 & 0.0 \\ 0.0 & 1.0 & 0.0 \\ 0.0 & 0.0 & 1.0 \end{bmatrix}$$

Specifically, the conversion matrix from BT.601 (625-line / PAL) to BT.709 in linear RGB is:

$$\begin{bmatrix} R_{709} \\ G_{709} \\ B_{709} \end{bmatrix} = \begin{bmatrix} 1.0000 & 0.0000 & 0.0000 \\ -0.0529 & 1.0000 & -0.0159 \\ 0.0000 & 0.0000 & 1.0000 \end{bmatrix} \dots$$

*(Note: Applying this matrix should occur in linear light, or through an approximation in gamma space, to prevent hue distortion).*

### 4.4 HDR Fidelity Claim Boundary

No HDR (BT.2020, PQ ST.2084, HLG) conversion is currently performed or claimed. SDR clips are displayed strictly in SDR mode. HDR claims must never be made without physical validation on an HDR-capable television with 10-bit EGL configurations (`EGL_CONFIG_CAVEAT` and `EGL_GL_COLORSPACE_BT2020_PQ_EXT`).

---

## 5. Verification Matrix

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
