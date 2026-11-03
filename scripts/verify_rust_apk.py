#!/usr/bin/env python3
"""Verify that the debug APK contains correctly-typed Rust libraries for every supported ABI."""

from __future__ import annotations

import argparse
import struct
import sys
import zipfile
from pathlib import Path

EXPECTED_ABIS: dict[str, tuple[int, int]] = {
    "arm64-v8a": (2, 183),  # ELF64, EM_AARCH64
    "armeabi-v7a": (1, 40),  # ELF32, EM_ARM
    "x86": (1, 3),  # ELF32, EM_386
    "x86_64": (2, 62),  # ELF64, EM_X86_64
}
LIBRARY_NAME = "libnightknight_render_core.so"


class ApkValidationError(ValueError):
    """The APK is missing a native ABI library or contains a mismatched ELF."""


def verify_rust_apk(apk_path: str | Path) -> dict[str, tuple[int, int]]:
    verified: dict[str, tuple[int, int]] = {}
    try:
        with zipfile.ZipFile(apk_path) as archive:
            for abi, expected in EXPECTED_ABIS.items():
                member = f"lib/{abi}/{LIBRARY_NAME}"
                try:
                    with archive.open(member) as native_library:
                        header = native_library.read(20)
                except KeyError as failure:
                    raise ApkValidationError(f"APK is missing required ABI library: {member}") from failure

                if len(header) < 20 or header[:4] != b"\x7fELF":
                    raise ApkValidationError(f"{member} is not a valid ELF library")
                elf_class = header[4]
                elf_data = header[5]
                if elf_data != 1:
                    raise ApkValidationError(f"{member} is not little-endian ELF")
                machine = struct.unpack_from("<H", header, 18)[0]
                actual = (elf_class, machine)
                if actual != expected:
                    raise ApkValidationError(
                        f"{member} has ELF class/machine {actual}, expected {expected}"
                    )
                verified[abi] = actual
    except (OSError, zipfile.BadZipFile) as failure:
        raise ApkValidationError(f"cannot read Android APK: {failure}") from failure
    return verified


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path, help="debug APK produced by Gradle")
    arguments = parser.parse_args()
    try:
        verified = verify_rust_apk(arguments.apk)
    except ApkValidationError as failure:
        print(f"rust_abi_verification=fail reason={failure}", file=sys.stderr)
        return 1
    for abi, (elf_class, machine) in verified.items():
        print(f"rust_abi_verification=pass abi={abi} elf_class={elf_class} machine={machine}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
