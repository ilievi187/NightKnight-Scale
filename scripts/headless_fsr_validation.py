#!/usr/bin/env python3
"""Run one Gamescope scaling case on an idle Hyprland headless output only."""

from __future__ import annotations

import argparse
import copy
import datetime as dt
import hashlib
import json
import math
import os
import re
import select
import shlex
import signal
import struct
import subprocess
import sys
import time
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[1]
DISPLAY_LINE = re.compile(
    r"^Display\s+(\d+):.*?(\d+)×(\d+).*?at\s+\((-?\d+),\s*(-?\d+)\)\s*$"
)
MAX_DIMENSION = 16_384


HEADLESS_HARDWARE_FIELDS = ("make", "model", "serial", "description")
MONITOR_IDENTITY_FIELDS = (
    "name",
    "id",
    "x",
    "y",
    "width",
    "height",
    "refreshRate",
    "scale",
    *HEADLESS_HARDWARE_FIELDS,
)


def is_headless_monitor(monitor: dict) -> bool:
    if not monitor.get("name", "").startswith("HEADLESS-") or monitor.get("disabled"):
        return False
    if not all(
        isinstance(monitor.get(field), str) and not monitor[field].strip()
        for field in HEADLESS_HARDWARE_FIELDS
    ):
        return False
    for field in ("id", "x", "y", "width", "height"):
        if type(monitor.get(field)) is not int:
            return False
    if monitor["width"] <= 0 or monitor["height"] <= 0:
        return False
    for field in ("refreshRate", "scale"):
        value = monitor.get(field)
        if not isinstance(value, (int, float)) or not math.isfinite(value) or value <= 0:
            return False
    return True


def same_headless_monitor(first: dict, second: dict) -> bool:
    return (
        is_headless_monitor(first)
        and is_headless_monitor(second)
        and tuple(first.get(field) for field in MONITOR_IDENTITY_FIELDS)
        == tuple(second.get(field) for field in MONITOR_IDENTITY_FIELDS)
        and first.get("activeWorkspace", {}).get("id")
        == second.get("activeWorkspace", {}).get("id")
    )


def select_headless_monitor(monitors: list[dict], monitor_name: str) -> dict:
    if not monitor_name.startswith("HEADLESS-"):
        raise ValueError("Only explicitly named HEADLESS-* outputs may be used.")
    matches = [monitor for monitor in monitors if monitor.get("name") == monitor_name]
    if len(matches) != 1:
        raise ValueError(f"Expected one {monitor_name} output; found {len(matches)}.")
    monitor = matches[0]
    if not is_headless_monitor(monitor):
        raise ValueError(f"{monitor_name} lacks headless-output identity; refusing to target it.")
    if monitor.get("disabled") or monitor.get("width", 0) <= 0 or monitor.get("height", 0) <= 0:
        raise ValueError(f"{monitor_name} is disabled or has invalid dimensions.")
    if not isinstance(monitor.get("activeWorkspace", {}).get("id"), int):
        raise ValueError(f"{monitor_name} has no active workspace to target safely.")
    return monitor


def revalidate_headless_target(
    expected_monitor: dict,
    monitors: list[dict],
    clients: list[dict],
    *,
    allowed_pid: int | None = None,
) -> dict:
    current = select_headless_monitor(monitors, str(expected_monitor.get("name", "")))
    if not same_headless_monitor(expected_monitor, current):
        raise ValueError("The selected headless output changed identity or mode; aborting before launch.")
    ensure_monitor_idle(current, clients, allowed_pid=allowed_pid)
    return current


def ensure_monitor_idle(
    monitor: dict, clients: list[dict], *, allowed_pid: int | None = None
) -> None:
    occupants = [
        client
        for client in clients
        if client.get("monitor") == monitor.get("id")
        and (allowed_pid is None or client.get("pid") != allowed_pid)
    ]
    if occupants:
        raise ValueError(
            f"{monitor['name']} already has {len(occupants)} window(s); refusing to disturb them."
        )


def parse_sdl_display_index(diagnostics: str, monitor: dict) -> int:
    matches = []
    for line in diagnostics.splitlines():
        match = DISPLAY_LINE.match(line)
        if not match:
            continue
        index, width, height, x, y = map(int, match.groups())
        if (width, height, x, y) == (
            monitor.get("width"),
            monitor.get("height"),
            monitor.get("x"),
            monitor.get("y"),
        ):
            matches.append(index)
    if len(matches) != 1:
        raise ValueError(
            f"Could not uniquely map {monitor['name']} to the explicit Wayland SDL display list."
        )
    return matches[0]


def revalidate_sdl_display_index(diagnostics: str, monitor: dict, expected_index: int) -> int:
    current_index = parse_sdl_display_index(diagnostics, monitor)
    if current_index != expected_index:
        raise ValueError(
            f"SDL display index changed from {expected_index} to {current_index}; aborting before launch."
        )
    return current_index


def sanitize_diagnostics(diagnostics: str) -> str:
    sanitized = []
    for line in diagnostics.splitlines():
        if line.startswith("GPU: "):
            sanitized.append("GPU: <adapter details redacted>")
            continue
        if not line.startswith("Display "):
            sanitized.append(line)
            continue
        match = DISPLAY_LINE.match(line)
        details = re.search(r"·\s*([0-9.]+)\s*Hz at \((-?\d+),\s*(-?\d+)\)\s*$", line)
        if not match or not details:
            sanitized.append("Display: <redacted>")
            continue
        index, width, height, _, _ = match.groups()
        label = line.split(":", 1)[1].split("·", 1)[0].strip()
        is_headless = not label or "HEADLESS" in label.upper()
        if not is_headless:
            sanitized.append(f"Display {index}: PHYSICAL OUTPUT · mode withheld")
        else:
            refresh = details.group(1)
            sanitized.append(
                f"Display {index}: HEADLESS TEST OUTPUT · {width}×{height} · {refresh} Hz"
            )
    result = "\n".join(sanitized)
    return result + ("\n" if diagnostics.endswith("\n") else "")


def parse_resolution(value: str) -> tuple[int, int]:
    try:
        width_text, height_text = value.lower().split("x", 1)
        width, height = int(width_text), int(height_text)
    except (ValueError, AttributeError) as error:
        raise argparse.ArgumentTypeError("resolution must be WIDTHxHEIGHT") from error
    if not (1 <= width <= MAX_DIMENSION and 1 <= height <= MAX_DIMENSION):
        raise argparse.ArgumentTypeError(f"dimensions must be between 1 and {MAX_DIMENSION}")
    return width, height


def portable_path(
    value: str | Path,
    run_dir: Path,
    repo_root: Path = REPO_ROOT,
    home_dir: str | Path | None = None,
) -> str:
    path = Path(os.path.abspath(Path(value).expanduser()))
    run_root = Path(os.path.abspath(run_dir))
    repo_root = Path(os.path.abspath(repo_root))
    try:
        relative = path.relative_to(run_root)
        return "$RUN_DIR" if str(relative) == "." else f"$RUN_DIR/{relative.as_posix()}"
    except ValueError:
        pass
    try:
        relative = path.relative_to(repo_root)
        return "$REPO" if str(relative) == "." else f"$REPO/{relative.as_posix()}"
    except ValueError:
        pass
    home = Path(os.path.abspath(Path(home_dir or Path.home()).expanduser()))
    try:
        relative = path.relative_to(home)
        return "$HOME" if str(relative) == "." else f"$HOME/{relative.as_posix()}"
    except ValueError:
        return str(value)


def portable_gamescope_argv(
    gamescope_executable: str,
    argv: list[str],
    run_dir: Path,
    repo_root: Path = REPO_ROOT,
    home_dir: str | Path | None = None,
) -> list[str]:
    return [
        portable_path(gamescope_executable, run_dir, repo_root, home_dir),
        *(
            portable_path(token, run_dir, repo_root, home_dir)
            if Path(token).is_absolute()
            else token
            for token in argv
        ),
    ]


def sanitize_home_paths(text: str, home_dir: str | Path | None = None) -> str:
    home = str(home_dir or Path.home()).rstrip("/")
    return text.replace(home, "$HOME") if home and home != "/" else text


def sanitize_runtime_identifiers(text: str, home_dir: str | Path | None = None) -> str:
    text = sanitize_home_paths(text, home_dir)
    text = re.sub(r"\x1b\[[0-?]*[ -/]*[@-~]", "", text)
    replacements = (
        (r"(?i)(running compositor on wayland display\s+')([^']+)(')", r"\1<redacted>\3"),
        (r"(?i)(starting Xwayland on ):\d+", r"\1<redacted>"),
        (r"(?i)(node ID:\s*)\d+", r"\1<redacted>"),
        (r"(?i)(\bID:\s*)\d+", r"\1<redacted>"),
        (r"(?i)(\b(?:monitor|workspace)(?:_id)?\s*[:=]\s*\"?)\d+", r"\1<redacted>"),
        (r"(?i)(\bstable[_ ]?id\s*[:=]\s*)[^\s,]+", r"\1<redacted>"),
        (r"(?i)(window xid:\s*)0x[0-9a-f]+", r"\1<redacted>"),
        (r"(?i)(wayland surface res id:\s*)\d+", r"\1<redacted>"),
        (r"(?i)\b0x[0-9a-f]{6,}\b", "<address>"),
        (r"\b(?:HDMI|DP|eDP|LVDS|VGA|DVI)-[A-Za-z0-9]+(?:-[A-Za-z0-9]+)*\b", "PHYSICAL-OUTPUT"),
        (r"\bHEADLESS-\d+\b", "HEADLESS-OUTPUT"),
        (r"\bgamescope-\d+\b", "gamescope-<redacted>"),
        (r"\bwayland-\d+\b", "wayland-<redacted>"),
        (r"\bpipewire-\d+\b", "pipewire-<redacted>"),
        (r"(?i)\b(?:pid|process\s*id)\s*[:= ]\s*\d+\b", "PID=<redacted>"),
        (r"\bat\s+\(-?\d+,\s*-?\d+\)", "at (<redacted>)"),
    )
    for pattern, replacement in replacements:
        text = re.sub(pattern, replacement, text)
    text = re.sub(
        r"(?i)(selecting physical device\s+')([^']+)(')",
        r"\1<adapter details redacted>\3",
        text,
    )
    text = re.sub(r"/run/user/\d+/(?:wayland|pipewire)-[^\s'\"]+", "<runtime-socket>", text)
    return text


def sanitize_gamescope_log(log_text: str, home_dir: str | Path | None = None) -> str:
    return sanitize_runtime_identifiers(log_text, home_dir)


def write_sanitized_text(path: Path, text: str) -> None:
    path.write_text(sanitize_runtime_identifiers(text), encoding="utf-8")


def sanitize_run_metadata(metadata: dict) -> dict:
    result = copy.deepcopy(metadata)
    monitor = result.pop("monitor", None)
    if isinstance(monitor, dict):
        result["test_output"] = {
            "class": "headless",
            "mode": {
                "width": monitor.get("width"),
                "height": monitor.get("height"),
            },
            "refresh_hz": monitor.get("refreshRate"),
            "logical_scale": monitor.get("scale"),
        }
    result.pop("workspace_id", None)
    focused_before = result.get("focused_monitor_before")
    focused_after = result.get("focused_monitor_after")
    result.pop("focused_monitor_before", None)
    result.pop("focused_monitor_after", None)
    monitor_unchanged = result.get("focus_monitor_unchanged")
    if monitor_unchanged is None and focused_before is not None and focused_after is not None:
        monitor_unchanged = focused_before == focused_after
    if monitor_unchanged is not None:
        result["focus_monitor_unchanged"] = monitor_unchanged
    checkpoints = []
    for checkpoint in result.get("focus_checkpoints", []):
        checkpoint_monitor_unchanged = checkpoint.get("focus_monitor_unchanged")
        if checkpoint_monitor_unchanged is None:
            checkpoint_monitor = checkpoint.get("focused_monitor")
            if checkpoint_monitor is not None and focused_before is not None:
                checkpoint_monitor_unchanged = checkpoint_monitor == focused_before
        checkpoints.append(
            {
                "stage": checkpoint.get("stage"),
                "focus_monitor_unchanged": checkpoint_monitor_unchanged,
                "focused_client_unchanged": checkpoint.get(
                    "focused_client_unchanged", False
                ),
            }
        )
    if "focus_checkpoints" in result:
        result["focus_checkpoints"] = checkpoints
    window = result.get("gamescope_window")
    if isinstance(window, dict):
        result["gamescope_window"] = {
            key: window[key]
            for key in ("class", "logical_size", "fullscreen_state")
            if key in window
        }
    versions = result.get("software_versions")
    if isinstance(versions, dict):
        result["software_versions"] = {
            key: versions[key]
            for key in ("nk_scale_sha256", "gamescope")
            if key in versions
        }
    provenance = result.get("source_provenance")
    if isinstance(provenance, dict):
        if "worktree_clean" not in provenance:
            if "tracked_source_dirty" in provenance:
                known_dirty = bool(provenance["tracked_source_dirty"])
            elif "git_dirty" in provenance:
                known_dirty = bool(provenance["git_dirty"])
            elif "worktree_clean_at_start" in provenance:
                known_dirty = not bool(provenance["worktree_clean_at_start"])
            else:
                known_dirty = True
            worktree_clean = False if known_dirty else None
        else:
            worktree_clean = bool(provenance["worktree_clean"])
        clean_provenance = {"worktree_clean": worktree_clean}
        for key in ("git_commit", "git_tree"):
            if provenance.get(key):
                clean_provenance[key] = provenance[key]
        result["source_provenance"] = clean_provenance
    else:
        legacy_versions = metadata.get("software_versions", {})
        if isinstance(legacy_versions, dict) and legacy_versions.get("git_commit"):
            known_dirty = bool(legacy_versions.get("git_dirty", True))
            result["source_provenance"] = {
                "git_commit": legacy_versions["git_commit"],
                "worktree_clean": False if known_dirty else None,
            }
    if "error" in result:
        result["error"] = sanitize_runtime_identifiers(str(result["error"]))
    return result


def write_result_metadata(run_dir: Path, metadata: dict) -> dict:
    sanitized = sanitize_run_metadata(metadata)
    (run_dir / "result.json").write_text(
        json.dumps(sanitized, indent=2) + "\n", encoding="utf-8"
    )
    return sanitized


def build_gamescope_argv(
    *,
    display_index: int,
    source: tuple[int, int],
    target: tuple[int, int],
    filter_name: str,
    sharpness: int,
    stats_path: str,
    app_path: str,
) -> list[str]:
    if filter_name not in {"fsr", "linear", "nearest", "pixel"}:
        raise ValueError(f"Unsupported Gamescope filter: {filter_name}")
    for dimension in (*source, *target):
        if not (1 <= dimension <= MAX_DIMENSION):
            raise ValueError(f"Resolution dimensions must be between 1 and {MAX_DIMENSION}.")
    if filter_name == "fsr" and not (0 <= sharpness <= 20):
        raise ValueError("FSR sharpness must be between 0 and 20.")
    argv = [
        "--backend", "sdl",
        "--display-index", str(display_index),
        "--nested-width", str(source[0]),
        "--nested-height", str(source[1]),
        "--output-width", str(target[0]),
        "--output-height", str(target[1]),
        "--scaler", "fit",
        "--filter", filter_name,
    ]
    if filter_name == "fsr":
        argv.extend(["--sharpness", str(sharpness)])
    argv.extend([
        "--expose-wayland",
        "--stats-path", stats_path,
        "--",
        app_path,
        "--test-pattern",
        f"--pattern-size={source[0]}x{source[1]}",
    ])
    return argv


def lua_quote(value: str) -> str:
    escaped = value.replace("\\", "\\\\").replace('"', '\\"')
    escaped = escaped.replace("\n", "\\n").replace("\r", "\\r")
    return f'"{escaped}"'


def build_process_environment(disable_lsfgvk: bool) -> list[str]:
    environment = ["SDL_VIDEODRIVER=wayland"]
    if disable_lsfgvk:
        environment.append("DISABLE_LSFGVK=1")
    return environment


def build_lua_dispatch(command: str, *, monitor_name: str, workspace_id: int) -> str:
    if not monitor_name.startswith("HEADLESS-"):
        raise ValueError("Refusing to dispatch a test window to a non-headless monitor.")
    if workspace_id <= 0:
        raise ValueError("Workspace id must be positive.")
    workspace = lua_quote(f"{workspace_id} silent")
    monitor = lua_quote(f"{monitor_name} silent")
    return (
        "hl.dispatch(hl.dsp.exec_cmd("
        f"{lua_quote(command)}, {{ workspace = {workspace}, monitor = {monitor}, "
        "no_initial_focus = true }))"
    )


def build_lua_fullscreen_dispatch(pid: int) -> str:
    if pid <= 0:
        raise ValueError("Gamescope PID must be positive.")
    window = lua_quote(f"pid:{pid}")
    return (
        "hl.dispatch(hl.dsp.window.fullscreen({ "
        f"window = {window}, mode = \"fullscreen\", action = \"set\" }}))"
    )


def run_checked(
    argv: list[str], *, timeout: float = 30.0, cwd: str | Path | None = None
) -> subprocess.CompletedProcess:
    result = subprocess.run(
        argv, text=True, capture_output=True, timeout=timeout, cwd=cwd
    )
    if result.returncode != 0:
        detail = (result.stderr or result.stdout).strip()
        raise RuntimeError(f"Command failed ({result.returncode}): {shlex.join(argv)}\n{detail}")
    return result


def git_source_provenance(repo_root: Path = REPO_ROOT) -> dict:
    repo = Path(repo_root).resolve()
    def git_value(*args: str) -> str:
        return run_checked(
            ["git", *args], timeout=10.0, cwd=repo
        ).stdout.strip()

    commit = git_value("rev-parse", "HEAD")
    tree = git_value("rev-parse", "HEAD^{tree}")
    status = run_checked(
        ["git", "status", "--porcelain", "--untracked-files=all"],
        timeout=10.0,
        cwd=repo,
    ).stdout
    return {
        "git_commit": commit,
        "git_tree": tree,
        "worktree_clean": not bool(status.strip()),
    }


def hypr_json(command: str) -> list[dict]:
    result = run_checked(["hyprctl", command, "-j"])
    return json.loads(result.stdout)


def gamescope_toplevel_identifier(
    window: dict,
    expected_pid: int,
    expected_monitor_id: int,
    expected_workspace_id: int,
    expected_stable_id: str | None = None,
) -> str:
    if window.get("pid") != expected_pid or window.get("class") != "gamescope":
        raise ValueError("The mapped client is not the expected Gamescope process.")
    if window.get("monitor") != expected_monitor_id:
        raise ValueError("The Gamescope client is not on the selected headless output.")
    workspace = window.get("workspace")
    if not isinstance(workspace, dict) or workspace.get("id") != expected_workspace_id:
        raise ValueError("The Gamescope client is not on the test workspace.")
    if not window.get("mapped") or window.get("hidden"):
        raise ValueError("The Gamescope client is not visibly mapped.")
    stable_id = window.get("stableId")
    if not isinstance(stable_id, str) or not stable_id:
        raise ValueError("Hyprland did not provide a foreign-toplevel capture identifier.")
    if expected_stable_id is not None and stable_id != expected_stable_id:
        raise ValueError("The Gamescope foreign-toplevel identity changed during the run.")
    return stable_id


def build_toplevel_capture_argv(stable_id: str, capture_path: str | Path) -> list[str]:
    if not stable_id:
        raise ValueError("A verified foreign-toplevel identifier is required for capture.")
    return ["grim", "-T", stable_id, str(capture_path)]


def focused_monitor_name(monitors: list[dict]) -> str | None:
    return next((monitor["name"] for monitor in monitors if monitor.get("focused")), None)


def focused_window_address() -> str | None:
    result = run_checked(["hyprctl", "activewindow", "-j"])
    active_window = json.loads(result.stdout or "{}")
    return active_window.get("address") if isinstance(active_window, dict) else None


def focus_unchanged(
    expected_monitor: str | None,
    expected_window_address: str | None,
    monitors: list[dict],
    active_window: dict,
) -> bool:
    return (
        focused_monitor_name(monitors) == expected_monitor
        and active_window.get("address") == expected_window_address
    )


def record_focus_checkpoint(
    metadata: dict,
    stage: str,
    expected_monitor: str | None,
    expected_window_address: str | None,
    monitors: list[dict],
) -> bool:
    active_window = json.loads(run_checked(["hyprctl", "activewindow", "-j"]).stdout or "{}")
    if not isinstance(active_window, dict):
        active_window = {}
    unchanged = focus_unchanged(
        expected_monitor, expected_window_address, monitors, active_window
    )
    metadata.setdefault("focus_checkpoints", []).append(
        {
            "stage": stage,
            "focus_monitor_unchanged": focused_monitor_name(monitors) == expected_monitor,
            "focused_client_unchanged": unchanged,
        }
    )
    return unchanged


def physical_mode_snapshot(monitors: list[dict]) -> list[tuple[str, int, int, float]]:
    return sorted(
        (
            monitor["name"],
            int(monitor["width"]),
            int(monitor["height"]),
            round(float(monitor["refreshRate"]), 3),
        )
        for monitor in monitors
        if not is_headless_monitor(monitor)
    )


def proc_cmdline(pid: int) -> list[str] | None:
    try:
        raw = Path(f"/proc/{pid}/cmdline").read_bytes()
    except (FileNotFoundError, PermissionError, ProcessLookupError):
        return None
    return [part.decode(errors="replace") for part in raw.split(b"\0") if part]


def require_pidfd_support() -> None:
    if not hasattr(os, "pidfd_open") or not hasattr(signal, "pidfd_send_signal"):
        raise RuntimeError("Safe Gamescope cleanup requires Linux pidfd support.")


def process_matches_gamescope(
    pid: int, stats_path: str, expected_executable: str = "gamescope"
) -> bool:
    command = proc_cmdline(pid)
    expected_basename = Path(expected_executable).name
    return bool(
        command
        and Path(command[0]).name == expected_basename
        and stats_path in command
    )


def pidfd_has_exited(pidfd: int) -> bool:
    poller = select.poll()
    poller.register(pidfd, select.POLLIN | select.POLLHUP | select.POLLERR)
    return bool(poller.poll(0))


def pin_gamescope_process(
    pid: int, stats_path: str, expected_executable: str = "gamescope"
) -> tuple[int, int] | None:
    require_pidfd_support()
    if not process_matches_gamescope(pid, stats_path, expected_executable):
        return None
    try:
        pidfd = os.pidfd_open(pid, 0)
    except (FileNotFoundError, ProcessLookupError):
        return None
    try:
        if pidfd_has_exited(pidfd) or not process_matches_gamescope(
            pid, stats_path, expected_executable
        ):
            os.close(pidfd)
            return None
        return pid, pidfd
    except Exception:
        os.close(pidfd)
        raise


def find_gamescope_process(
    stats_path: str, expected_executable: str = "gamescope"
) -> tuple[int, int] | None:
    require_pidfd_support()
    for entry in Path("/proc").iterdir():
        if not entry.name.isdigit():
            continue
        owned = pin_gamescope_process(
            int(entry.name), stats_path, expected_executable
        )
        if owned is not None:
            return owned
    return None


def terminate_owned_process(
    owned_process: tuple[int, int] | None, *, grace_seconds: float = 3.0
) -> None:
    if owned_process is None:
        return
    _, pidfd = owned_process
    try:
        if pidfd_has_exited(pidfd):
            return
        try:
            signal.pidfd_send_signal(pidfd, signal.SIGTERM)
        except ProcessLookupError:
            return
        poller = select.poll()
        poller.register(pidfd, select.POLLIN | select.POLLHUP | select.POLLERR)
        if poller.poll(max(1, int(grace_seconds * 1000))):
            return
        try:
            signal.pidfd_send_signal(pidfd, signal.SIGKILL)
        except ProcessLookupError:
            return
        poller.poll(1000)
    finally:
        os.close(pidfd)


def png_dimensions(path: Path) -> tuple[int, int]:
    data = path.read_bytes()
    if len(data) < 24 or data[:8] != b"\x89PNG\r\n\x1a\n":
        raise ValueError("grim did not produce a valid PNG capture.")
    return struct.unpack(">II", data[16:24])


def run_case(args: argparse.Namespace) -> dict:
    require_pidfd_support()
    monitors_before = hypr_json("monitors")
    monitor = select_headless_monitor(monitors_before, args.monitor)
    expected_monitor = monitor.copy()
    clients_before = hypr_json("clients")
    ensure_monitor_idle(monitor, clients_before)
    focused_before = focused_monitor_name(monitors_before)
    focused_window_before = focused_window_address()
    physical_before = physical_mode_snapshot(monitors_before)
    workspace_id = int(monitor["activeWorkspace"]["id"])

    app_path = Path(args.nk_scale).expanduser().resolve()
    if not app_path.is_file():
        raise ValueError(f"NKScale binary does not exist: {app_path}")
    diagnostics = run_checked([str(app_path), "--diagnostics"], timeout=45.0)
    display_index = parse_sdl_display_index(diagnostics.stdout, monitor)
    gamescope_version = next(
        (
            line.removeprefix("Gamescope: Available: ")
            for line in diagnostics.stdout.splitlines()
            if line.startswith("Gamescope: ")
        ),
        "unavailable",
    )
    source_provenance = git_source_provenance()
    binary_sha256 = hashlib.sha256(app_path.read_bytes()).hexdigest()

    timestamp = dt.datetime.now(dt.timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    source_label = f"{args.source[0]}x{args.source[1]}"
    target_label = f"{args.target[0]}x{args.target[1]}"
    run_name = f"{timestamp}-{args.filter}-{source_label}-to-{target_label}-s{args.sharpness}"
    run_dir = Path(args.output_dir).expanduser().resolve() / run_name
    run_dir.mkdir(parents=True, exist_ok=False)
    (run_dir / "diagnostics.txt").write_text(
        sanitize_diagnostics(diagnostics.stdout + diagnostics.stderr), encoding="utf-8"
    )
    log_path = run_dir / "gamescope.log"
    stats_path = run_dir / "gamescope-stats.csv"
    capture_path = run_dir / "capture.png"

    argv = build_gamescope_argv(
        display_index=display_index,
        source=args.source,
        target=args.target,
        filter_name=args.filter,
        sharpness=args.sharpness,
        stats_path=str(stats_path),
        app_path=str(app_path),
    )
    command = (
        "exec "
        + shlex.join(["env", *build_process_environment(args.disable_lsfgvk), args.gamescope, *argv])
        + " > " + shlex.quote(str(log_path)) + " 2>&1"
    )
    lua = build_lua_dispatch(command, monitor_name=monitor["name"], workspace_id=workspace_id)
    metadata = {
        "timestamp_utc": timestamp,
        "result": "running",
        "monitor": {key: monitor.get(key) for key in ("name", "id", "x", "y", "width", "height", "refreshRate", "scale")},
        "workspace_id": workspace_id,
        "sdl_display_index": display_index,
        "source_resolution_requested": {"width": args.source[0], "height": args.source[1]},
        "gamescope_target_resolution_requested": {"width": args.target[0], "height": args.target[1]},
        "filter_requested": args.filter,
        "sharpness_requested": args.sharpness if args.filter == "fsr" else None,
        "start_timeout_seconds": args.start_timeout,
        "capture_delay_seconds": args.capture_delay,
        "fullscreen_requested_before_placement": False,
        "fullscreen_dispatched_after_placement": False,
        "gamescope_argv": portable_gamescope_argv(args.gamescope, argv, run_dir),
        "wayland_sdl_driver": "SDL_VIDEODRIVER=wayland",
        "lsfgvk_disabled_for_test": args.disable_lsfgvk,
        "lsfgvk_config_modified": False,
        "window_routing": "per-process headless monitor/workspace rule; no_initial_focus=true; PID-scoped fullscreen only after placement validation",
        "process_cleanup": "pidfd; executable and unique stats-path marker verified before signaling",
        "software_versions": {
            "nk_scale_sha256": binary_sha256,
            "gamescope": gamescope_version,
        },
        "source_provenance": source_provenance,
        "log": portable_path(log_path, run_dir),
        "stats_path": portable_path(stats_path, run_dir),
        "capture": portable_path(capture_path, run_dir),
        "capture_method": "grim -T on verified Gamescope foreign-toplevel; host compositor layers excluded",
        "headless_host_output_mode_actual": {"width": monitor["width"], "height": monitor["height"]},
        "focus_checkpoints": [],
        "focused_window_unchanged": None,
        "shader_execution_proven": False,
        "performance_metrics": {
            "capture_fps": None,
            "output_fps": None,
            "gpu_processing_time_ms": None,
            "end_to_end_latency_ms": None,
            "gpu_utilization_percent": None,
            "vram_bytes": None,
            "cpu_percent": None,
            "dropped_frames": None,
            "reason": "No validated per-session telemetry source was available.",
        },
    }
    write_result_metadata(run_dir, metadata)

    pid = None
    owned_process = None
    gamescope_stable_id = None
    error = None
    try:
        monitors_prelaunch = hypr_json("monitors")
        monitor = revalidate_headless_target(
            expected_monitor, monitors_prelaunch, hypr_json("clients")
        )
        if not record_focus_checkpoint(
            metadata,
            "prelaunch_before_diagnostics",
            focused_before,
            focused_window_before,
            monitors_prelaunch,
        ):
            raise RuntimeError("Focus or focused window changed during preflight; aborting before launch.")
        if physical_mode_snapshot(monitors_prelaunch) != physical_before:
            raise RuntimeError("Physical output modes changed during preflight; aborting before launch.")
        fresh_diagnostics = run_checked([str(app_path), "--diagnostics"], timeout=45.0)
        revalidate_sdl_display_index(fresh_diagnostics.stdout, monitor, display_index)
        monitors_after_diagnostics = hypr_json("monitors")
        monitor = revalidate_headless_target(
            expected_monitor, monitors_after_diagnostics, hypr_json("clients")
        )
        if not record_focus_checkpoint(
            metadata,
            "prelaunch_after_diagnostics",
            focused_before,
            focused_window_before,
            monitors_after_diagnostics,
        ):
            raise RuntimeError("Focus or focused window changed during preflight; aborting before launch.")
        if physical_mode_snapshot(monitors_after_diagnostics) != physical_before:
            raise RuntimeError("Physical output modes changed during preflight; aborting before launch.")
        (run_dir / "diagnostics.txt").write_text(
            sanitize_diagnostics(fresh_diagnostics.stdout + fresh_diagnostics.stderr),
            encoding="utf-8",
        )
        metadata["prelaunch_revalidation"] = {
            "headless_identity_unchanged": True,
            "sdl_index_unchanged": True,
            "target_idle": True,
            "focus_monitor_unchanged": True,
            "physical_modes_unchanged": True,
        }
        metadata["monitor"] = {
            key: monitor.get(key)
            for key in ("name", "id", "x", "y", "width", "height", "refreshRate", "scale")
        }
        write_result_metadata(run_dir, metadata)
        dispatch = run_checked(["hyprctl", "eval", lua], timeout=15.0)
        write_sanitized_text(
            run_dir / "dispatch.txt", dispatch.stdout + dispatch.stderr
        )
        deadline = time.monotonic() + args.start_timeout
        window = None
        while time.monotonic() < deadline:
            if owned_process is None:
                owned_process = find_gamescope_process(str(stats_path), args.gamescope)
                if owned_process is not None:
                    pid = owned_process[0]
            if owned_process is not None:
                if pidfd_has_exited(owned_process[1]):
                    raise RuntimeError("Gamescope exited before mapping on the test output.")
                clients = hypr_json("clients")
                window = next((client for client in clients if client.get("pid") == pid), None)
                if window is not None:
                    if window.get("monitor") != monitor.get("id"):
                        raise RuntimeError("Gamescope mapped outside the selected headless output; aborting.")
                    if window.get("workspace", {}).get("id") != workspace_id:
                        raise RuntimeError("Gamescope mapped outside the selected test workspace; aborting.")
                    if not window.get("mapped") or window.get("hidden"):
                        raise RuntimeError("Gamescope window is not visible on the selected headless output.")
                    if pid is None:
                        raise RuntimeError("Gamescope process identity was not pinned before mapping.")
                    gamescope_stable_id = gamescope_toplevel_identifier(
                        window, pid, monitor["id"], workspace_id
                    )
                    break
            time.sleep(0.1)
        if pid is None or window is None or owned_process is None:
            raise TimeoutError("Gamescope did not map a window on the selected headless output in time.")
        if pidfd_has_exited(owned_process[1]):
            raise RuntimeError("The pinned Gamescope process exited before fullscreen verification.")
        monitors_before_fullscreen = hypr_json("monitors")
        monitor = revalidate_headless_target(
            expected_monitor,
            monitors_before_fullscreen,
            hypr_json("clients"),
            allowed_pid=pid,
        )
        if not record_focus_checkpoint(
            metadata,
            "before_fullscreen",
            focused_before,
            focused_window_before,
            monitors_before_fullscreen,
        ):
            raise RuntimeError("Focus or focused window changed before fullscreen; aborting.")
        if physical_mode_snapshot(monitors_before_fullscreen) != physical_before:
            raise RuntimeError("Physical output modes changed before fullscreen; aborting.")
        window = next((client for client in hypr_json("clients") if client.get("pid") == pid), None)
        if window is None or gamescope_stable_id is None:
            raise RuntimeError("Gamescope window disappeared before fullscreen.")
        gamescope_stable_id = gamescope_toplevel_identifier(
            window, pid, monitor["id"], workspace_id, gamescope_stable_id
        )
        fullscreen = run_checked(
            ["hyprctl", "eval", build_lua_fullscreen_dispatch(pid)], timeout=15.0
        )
        metadata["fullscreen_dispatched_after_placement"] = True
        write_sanitized_text(
            run_dir / "fullscreen-dispatch.txt", fullscreen.stdout + fullscreen.stderr
        )
        fullscreen_deadline = time.monotonic() + 2.0
        while time.monotonic() < fullscreen_deadline:
            if pidfd_has_exited(owned_process[1]):
                raise RuntimeError("The pinned Gamescope process exited during fullscreen transition.")
            monitors_fullscreen = hypr_json("monitors")
            monitor = revalidate_headless_target(
                expected_monitor, monitors_fullscreen, hypr_json("clients"), allowed_pid=pid
            )
            if not record_focus_checkpoint(
                metadata,
                "fullscreen_transition",
                focused_before,
                focused_window_before,
                monitors_fullscreen,
            ):
                raise RuntimeError("Focus or focused window changed during fullscreen transition.")
            if physical_mode_snapshot(monitors_fullscreen) != physical_before:
                raise RuntimeError("Physical output modes changed during fullscreen transition.")
            clients = hypr_json("clients")
            window = next((client for client in clients if client.get("pid") == pid), None)
            if window is None or gamescope_stable_id is None:
                raise RuntimeError("Fullscreen transition lost the Gamescope window or target output.")
            gamescope_stable_id = gamescope_toplevel_identifier(
                window, pid, monitor["id"], workspace_id, gamescope_stable_id
            )
            if window.get("fullscreen") == 2:
                break
            time.sleep(0.1)
        if window.get("fullscreen") != 2:
            raise RuntimeError("Hyprland did not confirm fullscreen on the selected headless output.")
        if args.capture_delay > 0:
            time.sleep(args.capture_delay)
        if pidfd_has_exited(owned_process[1]):
            raise RuntimeError("The pinned Gamescope process exited before capture.")
        monitors_before_capture = hypr_json("monitors")
        clients_before_capture = hypr_json("clients")
        monitor = revalidate_headless_target(
            expected_monitor, monitors_before_capture, clients_before_capture, allowed_pid=pid
        )
        if not record_focus_checkpoint(
            metadata,
            "before_capture",
            focused_before,
            focused_window_before,
            monitors_before_capture,
        ):
            raise RuntimeError("Focus or focused window changed before capture; aborting.")
        if physical_mode_snapshot(monitors_before_capture) != physical_before:
            raise RuntimeError("Physical output modes changed before capture; aborting.")
        window = next((client for client in clients_before_capture if client.get("pid") == pid), None)
        if window is None or gamescope_stable_id is None:
            raise RuntimeError("Gamescope window disappeared before capture.")
        gamescope_stable_id = gamescope_toplevel_identifier(
            window, pid, monitor["id"], workspace_id, gamescope_stable_id
        )
        if window.get("fullscreen") != 2:
            raise RuntimeError("Gamescope window is no longer fullscreen on the test output.")
        run_checked(
            build_toplevel_capture_argv(gamescope_stable_id, capture_path), timeout=20.0
        )
        captured = png_dimensions(capture_path)
        metadata["capture_resolution_actual"] = {"width": captured[0], "height": captured[1]}
        metadata["gamescope_toplevel_capture_verified"] = True
        metadata["gamescope_window"] = {
            "pid": pid,
            "class": window.get("class"),
            "monitor_id": window.get("monitor"),
            "workspace_id": window.get("workspace", {}).get("id"),
            "logical_size": window.get("size"),
            "fullscreen_state": window.get("fullscreen"),
        }
        metadata["stats_file_created"] = stats_path.exists()
        metadata["result"] = "captured"
    except Exception as exc:  # record failure, then always clean up this run's process
        error = f"{type(exc).__name__}: {exc}"
        error = error.replace(str(run_dir), "$RUN_DIR").replace(str(REPO_ROOT), "$REPO")
        error = sanitize_runtime_identifiers(error)
        metadata["result"] = "failed"
        metadata["error"] = error
    finally:
        if owned_process is None:
            owned_process = find_gamescope_process(str(stats_path), args.gamescope)
        terminate_owned_process(owned_process)
        if log_path.exists():
            log_path.write_text(
                sanitize_gamescope_log(log_path.read_text(errors="replace")), encoding="utf-8"
            )
        monitors_after = hypr_json("monitors")
        metadata["focus_monitor_unchanged"] = focused_monitor_name(monitors_after) == focused_before
        metadata["focused_window_unchanged"] = record_focus_checkpoint(
            metadata,
            "after_cleanup",
            focused_before,
            focused_window_before,
            monitors_after,
        )
        metadata["physical_modes_unchanged"] = physical_mode_snapshot(monitors_after) == physical_before
        if not metadata["focus_monitor_unchanged"] or not metadata["focused_window_unchanged"]:
            metadata["focus_changed_during_test"] = True
        if not metadata["physical_modes_unchanged"]:
            metadata["physical_modes_changed_during_test"] = True
        metadata["stopped_at_utc"] = dt.datetime.now(dt.timezone.utc).isoformat()
        write_result_metadata(run_dir, metadata)

    if error:
        raise RuntimeError(
            f"Validation case failed; see {portable_path(run_dir / 'result.json', run_dir)}: {error}"
        )
    if metadata.get("focus_changed_during_test") or not metadata.get("physical_modes_unchanged"):
        raise RuntimeError(
            "Safety invariant changed; inspect "
            f"{portable_path(run_dir / 'result.json', run_dir)}"
        )
    return sanitize_run_metadata(metadata)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--monitor", required=True, help="An idle Hyprland HEADLESS-* output name.")
    parser.add_argument("--source", type=parse_resolution, default=(1280, 720), help="Source WIDTHxHEIGHT.")
    parser.add_argument("--target", type=parse_resolution, default=(1920, 1080), help="Gamescope output WIDTHxHEIGHT.")
    parser.add_argument("--filter", choices=("fsr", "linear", "nearest", "pixel"), default="fsr")
    parser.add_argument("--sharpness", type=int, default=6, help="Gamescope FSR sharpness (0-20).")
    parser.add_argument(
        "--disable-lsfgvk",
        action="store_true",
        help="Disable the Lossless Scaling layer for this run only; never edits its config.",
    )
    parser.add_argument("--nk-scale", default=str(REPO_ROOT / "target/release/nk-scale"))
    parser.add_argument("--gamescope", default="/usr/bin/gamescope")
    parser.add_argument("--output-dir", default=str(REPO_ROOT / "validation/runs"))
    parser.add_argument("--start-timeout", type=float, default=8.0)
    parser.add_argument("--capture-delay", type=float, default=1.0)
    args = parser.parse_args(argv)
    try:
        result = run_case(args)
    except Exception as error:
        print(f"ERROR: {sanitize_runtime_identifiers(str(error))}", file=sys.stderr)
        return 1
    print(
        json.dumps(
            {
                key: result.get(key)
                for key in (
                    "result",
                    "test_output",
                    "source_resolution_requested",
                    "gamescope_target_resolution_requested",
                    "filter_requested",
                    "sharpness_requested",
                    "headless_host_output_mode_actual",
                    "capture_resolution_actual",
                    "capture_method",
                    "gamescope_toplevel_capture_verified",
                    "capture",
                    "log",
                    "stats_file_created",
                )
            },
            indent=2,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
