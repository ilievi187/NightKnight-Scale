#!/usr/bin/env python3
"""Assert the diagnostics control is D-pad focused/visible or its dialog opened."""

from __future__ import annotations

import argparse
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

DIAGNOSTICS_DESCRIPTION = "Open detailed video and renderer diagnostics"
DIALOG_MARKERS = (
    "NightKnight · Diagnostics",
    "EASU pass count",
    "RGBA render-target estimate",
    "GPU execution time",
    "Display presentation timing",
    "SurfaceTexture callback cadence",
)


def load_nodes(path: Path) -> list[ET.Element]:
    try:
        return list(ET.parse(path).getroot().iter("node"))
    except (ET.ParseError, OSError) as error:
        raise SystemExit(f"Could not read UI hierarchy {path}: {error}") from error


def assert_focused_control(path: Path, width: int, height: int) -> None:
    nodes = load_nodes(path)
    control = next(
        (
            node
            for node in nodes
            if node.get("content-desc") == DIAGNOSTICS_DESCRIPTION
            or node.get("text") == "Diagnostics…"
        ),
        None,
    )
    if control is None:
        raise SystemExit("Diagnostics button is missing from the D-pad UI hierarchy")

    bounds = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", control.get("bounds", ""))
    if bounds is None:
        raise SystemExit(f"Diagnostics button has invalid bounds: {control.get('bounds')!r}")
    left, top, right, bottom = map(int, bounds.groups())
    if not (0 <= left < right <= width and 0 <= top < bottom <= height):
        raise SystemExit(
            f"Diagnostics button is clipped at {control.get('bounds')} on {width}x{height}"
        )
    if control.get("focused") != "true":
        raise SystemExit("D-pad navigation did not focus the visible Diagnostics button")
    print(f"PASS: Diagnostics is focused and visible at {width}x{height}, bounds={control.get('bounds')}")


def assert_dialog_open(path: Path) -> None:
    nodes = load_nodes(path)
    labels = "\n".join(
        value
        for node in nodes
        for value in (node.get("text", ""), node.get("content-desc", ""))
        if value
    )
    missing = [marker for marker in DIALOG_MARKERS if marker not in labels]
    if missing:
        raise SystemExit("Diagnostics dialog is missing: " + ", ".join(missing))
    print("PASS: Diagnostics dialog opened with renderer and video metrics")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=("focused", "dialog"))
    parser.add_argument("xml", type=Path)
    parser.add_argument("--width", type=int, default=1920)
    parser.add_argument("--height", type=int, default=1080)
    args = parser.parse_args()
    if args.mode == "focused":
        assert_focused_control(args.xml, args.width, args.height)
    else:
        assert_dialog_open(args.xml)


if __name__ == "__main__":
    main()
