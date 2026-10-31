# Android TV compatibility boundary

This note describes what the current NightKnight Scale Android application can process, and what ordinary Android apps cannot promise on a TCL 55C645.

## What is implemented

The Android branch is a foreground player/preview. It decodes its bundled test clips or a user-selected local document into an app-owned video surface and passes the resulting frames through the app's GLES pipeline. Local document access uses the system Storage Access Framework picker; this is a user-granted URI, not broad filesystem access.[6]

The player currently uses framework `android.media.MediaPlayer`, not Jetpack Media3. The decoder-reported input size is passed to the GL thread; FSR output is capped at 2× per source axis (4× area), with any remaining display fill done at surface presentation. Media3/ExoPlayer can target an application-provided `Surface`, so migrating player control to Media3 is feasible without changing the conceptual `SurfaceTexture` → GLES renderer path; it has not been implemented or acceptance-tested yet.[3][4] We do not claim this app uses Media3 or a direct custom `MediaCodec` implementation today.

## Source compatibility

| Source | Current status | Boundary |
|---|---|---|
| Bundled unencrypted clips | Implemented and exercised in CI emulator | Synthetic H.264 samples only; emulator evidence is not TCL hardware evidence. |
| User-selected local unencrypted video | SAF picker/player path implemented; requires dedicated runtime validation | Container/codec/profile support depends on Android's available decoder. Unsupported files should fail visibly; no universal codec guarantee. |
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

- Replace or justify the current framework MediaPlayer usage with Media3/ExoPlayer or a separately tested MediaCodec path.
- Add automated SAF local-file selection/playback smoke coverage on an emulator and validate common H.264 SD/HD samples; test unsupported formats and revoked URI grants.
- Implement named OFF/QUALITY/BALANCED/PERFORMANCE presets and persist all user settings; current UI has algorithm comparison choices and a sharpness slider, not those named presets.
- Implement proper 576i deinterlacing, and validate pixel aspect/aspect-fit/crop behavior with real interlaced content.
- Add explicit stream input only for unencrypted/authorized IPTV sources and test network loss/reconnect, audio, subtitles, and frame pacing.
- Test MediaProjection as a separately consented, opt-in preview only if useful; verify protected-surface behavior without attempting bypass.
- Test on physical TCL 55C645 and document actual decoder, GLES renderer, resolution, latency, dropped frames, sustained thermal behavior, and remote navigation.
- Complete Android TV accessibility, app metadata, privacy policy, release signing, store listing, crash handling, and Google Play policy/compliance review.

The Linux implementation remains in the existing repository and is not replaced by this Android prototype.

## Sources

[1] https://developer.android.com/media/grow/media-projection — Android Media projection
[2] https://developer.android.com/about/versions/10/privacy/changes — Android 10 screen capture restrictions
[3] https://developer.android.com/media/media3/exoplayer — Media3 ExoPlayer
[4] https://developer.android.com/media/media3/ui/surface — Media3 surface types
[5] https://www.tcl.com/content/dam/brandsite/region/es/product-documents/product-sheets/c645/11-20-57_1449_754_ExportPDFTCL_55C645_TECHSPECS_ES.pdf — TCL 55C645 product specifications
[6] https://developer.android.com/training/data-storage/shared/documents-files — Android Storage Access Framework documents
