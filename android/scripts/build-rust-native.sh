#!/usr/bin/env bash
set -euo pipefail

output_dir="${1:?usage: build-rust-native.sh GENERATED_JNI_LIBS_DIR ANDROID_NDK_VERSION RUST_VERSION}"
ndk_version="${2:?usage: build-rust-native.sh GENERATED_JNI_LIBS_DIR ANDROID_NDK_VERSION RUST_VERSION}"
rust_version="${3:?usage: build-rust-native.sh GENERATED_JNI_LIBS_DIR ANDROID_NDK_VERSION RUST_VERSION}"
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
host_tag=""

case "$(uname -s):$(uname -m)" in
    Linux:x86_64) host_tag="linux-x86_64" ;;
    Darwin:arm64) host_tag="darwin-x86_64" ;;
    Darwin:x86_64) host_tag="darwin-x86_64" ;;
    *) echo "Unsupported NDK host: $(uname -s) $(uname -m)" >&2; exit 2 ;;
esac

ndk_dir=""
for candidate in "${ANDROID_NDK_HOME:-}" "${ANDROID_NDK_ROOT:-}"; do
    [[ -n "$candidate" ]] || continue
    candidate_ndk_dir="$candidate"
    if [[ "$candidate_ndk_dir" != /* ]]; then
        candidate_ndk_dir="$PWD/$candidate_ndk_dir"
    fi
    if [[ ! -x "$candidate_ndk_dir/toolchains/llvm/prebuilt/$host_tag/bin/clang" ||
          ! -f "$candidate_ndk_dir/source.properties" ]]; then
        continue
    fi
    candidate_version=""
    while IFS='=' read -r key value; do
        key="${key//[[:space:]]/}"
        value="${value//[[:space:]]/}"
        if [[ "$key" == "Pkg.Revision" ]]; then
            candidate_version="$value"
            break
        fi
    done < "$candidate_ndk_dir/source.properties"
    if [[ "$candidate_version" == "$ndk_version" ]]; then
        ndk_dir="$candidate_ndk_dir"
        break
    fi
done
if [[ -z "$ndk_dir" ]]; then
    sdk_dir="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
    if [[ -z "$sdk_dir" && -f "$repo_root/android/local.properties" ]]; then
        while IFS='=' read -r key value; do
            if [[ "$key" == "sdk.dir" ]]; then
                sdk_dir="${value//\\/}"
                break
            fi
        done < "$repo_root/android/local.properties"
    fi
    if [[ -z "$sdk_dir" ]]; then
        for candidate in "$HOME/Android/Sdk" "$HOME/Library/Android/sdk" "/opt/android-sdk"; do
            if [[ -d "$candidate/ndk/$ndk_version" ]]; then
                sdk_dir="$candidate"
                break
            fi
        done
    fi
    if [[ -n "$sdk_dir" ]]; then
        ndk_dir="$sdk_dir/ndk/$ndk_version"
    fi
fi

if [[ ! -x "$ndk_dir/toolchains/llvm/prebuilt/$host_tag/bin/clang" ]]; then
    echo "Android NDK $ndk_version not found. Install it with sdkmanager or set ANDROID_NDK_HOME." >&2
    exit 2
fi
actual_ndk_version=""
while IFS='=' read -r key value; do
    key="${key//[[:space:]]/}"
    value="${value//[[:space:]]/}"
    if [[ "$key" == "Pkg.Revision" ]]; then
        actual_ndk_version="$value"
        break
    fi
done < "$ndk_dir/source.properties"
if [[ "$actual_ndk_version" != "$ndk_version" ]]; then
    echo "Expected Android NDK $ndk_version, found ${actual_ndk_version:-unknown} at $ndk_dir" >&2
    exit 2
fi
if ! command -v cargo >/dev/null 2>&1; then
    echo "Cargo is required to build the Android Rust library." >&2
    exit 2
fi
if ! command -v rustc >/dev/null 2>&1; then
    echo "Rustc is required to build the Android Rust library." >&2
    exit 2
fi
declared_rust_version="$(awk -F '\"' '/^[[:space:]]*channel[[:space:]]*=/{print $2; exit}' "$repo_root/rust-toolchain.toml")"
if [[ "$declared_rust_version" != "$rust_version" ]]; then
    echo "Gradle requested Rust $rust_version but rust-toolchain.toml pins ${declared_rust_version:-unknown}." >&2
    exit 2
fi
mkdir -p "$output_dir"
output_dir="$(cd "$output_dir" && pwd)"
cd "$repo_root"
actual_rust_version="$(rustc --version | awk '{print $2}')"
actual_cargo_version="$(cargo --version | awk '{print $2}')"
if [[ "$actual_rust_version" != "$rust_version" || "$actual_cargo_version" != "$rust_version" ]]; then
    echo "Expected Rust/Cargo $rust_version, found rustc $actual_rust_version and cargo $actual_cargo_version." >&2
    exit 2
fi

llvm_bin="$ndk_dir/toolchains/llvm/prebuilt/$host_tag/bin"

build_abi() {
    local target="$1"
    local abi="$2"
    local linker_name="$3"
    local linker_var="$4"
    local linker="$llvm_bin/$linker_name"
    if [[ ! -x "$linker" ]]; then
        echo "NDK linker not found for $abi: $linker" >&2
        exit 2
    fi

    env "CARGO_TARGET_${linker_var}_LINKER=$linker" \
        cargo build --locked --release --manifest-path "$repo_root/Cargo.toml" --package nightknight-render-core --target "$target"
    mkdir -p "$output_dir/$abi"
    install -m 0644 \
        "$repo_root/target/$target/release/libnightknight_render_core.so" \
        "$output_dir/$abi/libnightknight_render_core.so"
}

build_abi "aarch64-linux-android" "arm64-v8a" "aarch64-linux-android23-clang" "AARCH64_LINUX_ANDROID"
build_abi "armv7-linux-androideabi" "armeabi-v7a" "armv7a-linux-androideabi23-clang" "ARMV7_LINUX_ANDROIDEABI"
build_abi "i686-linux-android" "x86" "i686-linux-android23-clang" "I686_LINUX_ANDROID"
build_abi "x86_64-linux-android" "x86_64" "x86_64-linux-android23-clang" "X86_64_LINUX_ANDROID"
