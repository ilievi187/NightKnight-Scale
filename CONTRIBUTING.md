# Contributing

Thanks for helping make Linux display scaling honest, fast, and easy to validate.

## Ground rules

- Linux/Wayland is the first target; Windows is out of scope. Do not regress the Wayland path for speculative cross-platform abstractions.
- Keep the game/render path independent from the settings UI. UI callbacks must not wait for a frame pipeline or child process to exit.
- Clearly label external backends. In the first milestone, Gamescope owns composition and scaling; NKScale owns configuration, launch supervision, and diagnostics.
- Treat capture permission as a user decision. Never add a screenshot, PipeWire, portal, or DRM bypass.
- Report only measurements produced by a reproducible instrumented run.
- Do not copy shader code or assets without confirming license compatibility and preserving attribution.

## Development

1. Open an issue or describe the user-visible behavior in the PR.
2. Add a focused test first and observe it fail for the intended reason.
3. Implement the smallest complete behavior and keep the change in a logical commit.
4. Run the same checks as CI:

   ```sh
   cargo fmt --all -- --check
   cargo clippy --workspace --all-targets -- -D warnings
   cargo test --workspace
   ```

5. For runtime changes, include the exact command, compositor, GPU/driver, source and output mode, and observed result. Do not imply hardware coverage beyond what was exercised.

## Reporting bugs

Include the NKScale and Gamescope versions, compositor/session type, GPU driver, selected source/output mode, exact command, logs, and whether the built-in test pattern reproduces the issue. Remove private content from logs before sharing.

## Licensing

Original code is MIT. Dependencies, shader sources, and redistributable artifacts keep their own license obligations. PRs that introduce third-party code must identify the upstream source, exact license, and attribution requirements.
