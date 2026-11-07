# Terra V3 integration plan

Base: `feature/luna-gpu-engine` at `4abfa4433084d7b3288719154d44395b31696b10` (fetched before work). Work is isolated on `feature/terra-rust-gpu-v3` and targets the integration branch, not main.

1. Repair and test the output-surface state regression and smoke validation without weakening dimension, EGL, FBO, planner or fallback checks.
2. Run Android JVM, Rust, shader, ABI and emulator checks; establish passing GitHub CI on the integration PR before considering further renderer migration.
3. Investigate each proposed Rust/GLES optimization with correctness and before/after timing evidence; retain the Kotlin and RGBA-copy fallbacks.
4. Compare A–D on verified output buffers, separating estimates, CPU submission, renderer/decoder callbacks, GPU timer queries and presentation timing. Emulator results cannot establish physical PowerVR behavior.
5. Perform independent Rust/JNI, GPU/image and Android/CI reviews; deliver a verified APK and hash. Do not merge or access the physical TV without owner permission.
