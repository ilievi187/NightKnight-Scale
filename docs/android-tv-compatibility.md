# Android TV compatibility boundary

This note describes what the current NightKnight Scale Android application can process, and what ordinary Android apps cannot promise on a TCL 55C645.

## What is implemented

The Android branch is a foreground player/preview. It decodes bundled test clips or a user-selected local document into an app-owned video surface and sends frames through the app's GLES pipeline. It requests `ACTION_OPEN_DOCUMENT` where a real document provider handles the action. Some Android TV builds expose a placeholder stub instead; in that case this app falls back to Android's system Photo Picker with `ACTION_GET_CONTENT`. The fallback grant is not promised to persist across app/process restarts, so the app only saves the URI if a persistable read grant was actually accepted. Neither path uses broad filesystem permissions.[6]

The player currently uses framework `android.media.MediaPlayer`, not Jetpack Media3. The decoder-reported input size is passed to the GL thread. EASU can use multiple stages, each no more than 2× per axis, to reach the aspect-fit display target within the GLES texture cap. Media3/ExoPlayer can target an application-provided `Surface`, so migrating player control to Media3 is feasible without changing the conceptual `SurfaceTexture` → GLES renderer path; it has not been implemented or acceptance-tested yet.[3][4] We do not claim this app uses Media3 or a direct custom `MediaCodec` implementation today.

## Source compatibility

| Source | Current status | Boundary |
|---|---|---|
| Bundled unencrypted clips | Implemented and exercised in CI emulator | Synthetic H.264 samples only; emulator evidence is not TCL hardware evidence. |
| User-selected local unencrypted video | `ACTION_OPEN_DOCUMENT` when available; fallback system Photo Picker `ACTION_GET_CONTENT` when TV resolves the document action to a stub. Automated emulator smoke selects five local samples and verifies decode dimensions/motion. | Container/codec/profile support depends on Android's available decoder. Fallback URI may not persist beyond the session. TCL provider behavior and physical codecs still require validation. |
| User-provided unencrypted IPTV/network stream | Not implemented | A future input/player must handle stream protocols, authorization, codec changes, audio and subtitles; it processes only media the user is permitted to access. |
| Vivacom EON app | Not directly processable by this app | EON owns its playback/decode surface; this app has no EON decoder integration or public API to take over that surface. Consent-based MediaProjection could at most provide a separate capture/preview path where the source permits capture. Android requires user approval for screen projection; secure/protected output is not a supported bypass path.[1][2] EON-specific capture/DRM behavior has not been tested, so no claim is made about exact EON policy. |
| Netflix, YouTube, other apps | No direct processing integration | Same app/surface separation. A consented capture experiment would be separate from the source player; protected content can be excluded by platform/content security. No DRM circumvention is implemented. |
| External HDMI receiver | Not accessible to this app | The TV's HDMI connectors accept external inputs; they are not a pixel-feed API for an ordinary Android application. The TCL product sheet lists HDMI inputs and HDCP support, not an app-level capture interface.[5] This project has no HDMI capture hardware. Processing an external receiver would require a separate capture device and signal path, with HDCP/legal constraints; no claim of direct HDMI enhancement is made. |
| Built-in TV tuner/broadcast | Not implemented | No tuner/TvInput integration has been built or validated. |

## MediaProjection is not a system-wide filter

Android MediaProjection is an explicit, user-consented screen/app capture API that provides captured frames to the requesting app on a surface. It does not grant a general right to replace the pixels presented by another app or to intercept HDMI input. Recent Android versions require consent for each projection session and proper lifecycle cleanup.[1] Android restricts silent framebuffer/video-output capture permissions to signature-level access; normal apps must use MediaProjection and its consent UI.[2] Even if a particular source can be captured, this application would render a processed copy in its own preview, not overwrite the source app or TV input. Capturing protected content or bypassing DRM is out of scope.

## Device/performance evidence

The repository's previous CI smoke used an API 36 Android TV AVD with Google SwiftShader. That confirms GLES shader execution and app behavior under software emulation only. It is not a physical TCL 55C645 decoder/GPU/frame-pacing/thermal test. No current physical-TV performance or visual-quality claim is made. The TV's external video inputs and its internal Google TV app environment are distinct from a third-party app's own rendering surface.

## Release blockers still open

- Add automated unsupported-format/revoked-URI coverage and validate the fallback picker behavior on TCL firmware; current emulator smoke covers five local progressive SDR files only.
- Replace or justify the framework MediaPlayer path with Media3/ExoPlayer or separately tested MediaCodec; current UI labels the actual MediaPlayer path and platform info lag events but cannot expose the hardware codec name.
- Validate pixel/sample aspect ratios and rotation metadata, plus SDR color range/matrix, on real files and the physical TV. Interlaced 576i deinterlacing and HDR/colorimetry processing remain unimplemented.
- Add exact decoder/display drop counters only if a platform API exposes them; current callback-coalescing and render cadence values are explicitly estimates.
- Test on physical TCL 55C645 and document actual decoder, GLES renderer, resolution, latency, sustained performance/thermal behavior, and remote navigation.
- Complete Android TV accessibility, app metadata, privacy policy, release signing, store listing, crash handling, and Google Play policy/compliance review.

The Linux implementation remains in the existing repository and is not replaced by this Android prototype.

## Sources

[1] https://developer.android.com/media/grow/media-projection — Android Media projection
[2] https://developer.android.com/about/versions/10/privacy/changes — Android 10 screen capture restrictions
[3] https://developer.android.com/media/media3/exoplayer — Media3 ExoPlayer
[4] https://developer.android.com/media/media3/ui/surface — Media3 surface types
[5] https://www.tcl.com/content/dam/brandsite/region/es/product-documents/product-sheets/c645/11-20-57_1449_754_ExportPDFTCL_55C645_TECHSPECS_ES.pdf — TCL 55C645 product specifications
[6] https://developer.android.com/training/data-storage/shared/documents-files — Android Storage Access Framework documents
