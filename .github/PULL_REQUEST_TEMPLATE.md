## Summary

<!-- What user-visible behavior changes, and which mode/backend is responsible? -->

## Validation

- [ ] `cargo fmt --all -- --check`
- [ ] `cargo clippy --workspace --all-targets -- -D warnings`
- [ ] `cargo test --workspace`
- [ ] Runtime test performed (include compositor, GPU/driver, and resolutions), or explain why not

## Safety and accuracy

- [ ] No compositor/driver/system configuration is modified.
- [ ] No capture permission is bypassed.
- [ ] No unmeasured performance or compatibility claim is added.
- [ ] Third-party code/shader license and attribution are documented.
- [ ] Gamescope-backed and native functionality remain clearly distinguished.
