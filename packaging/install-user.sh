#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
install_root="${HOME}/.local"
bin_dir="${install_root}/bin"
app_dir="${install_root}/share/applications"
icon_dir="${install_root}/share/icons/hicolor/scalable/apps"

cargo build --release --manifest-path "${repo_root}/Cargo.toml" -p nkscale-ui
install -d "${bin_dir}" "${app_dir}" "${icon_dir}"
install -m 0755 "${repo_root}/target/release/nk-scale" "${bin_dir}/nk-scale"
install -m 0644 "${repo_root}/assets/nightknight-scale.desktop" "${app_dir}/nightknight-scale.desktop"
install -m 0644 "${repo_root}/assets/nightknight-scale.svg" "${icon_dir}/nightknight-scale.svg"
printf 'Installed per-user launcher in %s\n' "${install_root}"
printf 'No system packages, compositor files, or drivers were changed.\n'
