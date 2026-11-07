# Emulator reference samples (not a GPU benchmark)

Source artifacts: GitHub Actions run 41382520775 (Kotlin baseline commit `7b0e472550814d1844bbaac8ecc2885139078198`) and run 42126454677 (Terra commit `5dc29b452b71e3e7b1f5ed7ccdba9d8e019ff055`). Both used the API 36 Android TV x86 SwiftShader emulator and 960×540, 30 fps test source, with 1920×1080 callback/EGL surfaces. Samples are from `performance-samples-active.logcat.txt`; only requested=effective samples are included. Columns show medians of window averages, not a matched or controlled benchmark.

| Mode | Baseline n | Baseline renderer callbacks/s | Baseline SurfaceTexture callbacks/s | Baseline CPU submission ms | Terra n | Terra renderer callbacks/s | Terra SurfaceTexture callbacks/s | Terra CPU submission ms |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| QUALITY | 1 | 6.11 | 5.55 | 5.431 | 1 | 8.68 | 9.22 | 9.962 |
| BALANCED | 3 | 7.55 | 6.83 | 15.267 | 3 | 9.96 | 9.35 | 9.719 |
| PERFORMANCE | 1 | 7.15 | 6.91 | 8.015 | 1 | 11.97 | 10.88 | 6.883 |
| OFF | 1 | 15.97 | 16.94 | 11.934 | 1 | 25.74 | 24.65 | 8.253 |

These runs are separate, contain very few windows, and have uncontrolled host variability. They do not establish a performance improvement or causality. Renderer callbacks are not physical display FPS; SurfaceTexture callbacks are not exact decoder output counts. CPU submission is wall time around CPU/GLES operations, not GPU execution. GPU timing was N/A in all included samples. No native 4K throughput, physical presentation, PowerVR behavior, sustained 30 fps, image-quality win, thermal behavior, or real bandwidth was measured. The current PR head differs from the Terra sample commit.
