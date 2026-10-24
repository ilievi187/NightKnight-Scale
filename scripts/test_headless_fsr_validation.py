import csv
import json
import os
from pathlib import Path
import re
import signal
import subprocess
import sys
import tempfile
import time
import unittest

import headless_fsr_validation as validation

from headless_fsr_validation import (
    build_gamescope_argv,
    build_lua_dispatch,
    build_lua_fullscreen_dispatch,
    build_process_environment,
    ensure_monitor_idle,
    find_gamescope_process,
    focus_unchanged,
    parse_sdl_display_index,
    portable_gamescope_argv,
    portable_path,
    revalidate_headless_target,
    revalidate_sdl_display_index,
    same_headless_monitor,
    sanitize_diagnostics,
    sanitize_home_paths,
    select_headless_monitor,
    terminate_owned_process,
)


class HeadlessFsrValidationTests(unittest.TestCase):
    def setUp(self):
        self.monitors = [
            {"name": "DP-TEST-OUTPUT", "id": 40, "x": 400, "y": 100, "width": 1920, "height": 1080, "make": "Example Vendor", "model": "Physical Display", "serial": "TEST-123", "description": "Example Vendor Physical Display TEST-123"},
            {"name": "HEADLESS-TEST-OUTPUT", "id": 41, "x": 8000, "y": 300, "width": 1920, "height": 1080, "refreshRate": 60.0, "scale": 1.25, "make": "", "model": "", "serial": "", "description": "", "activeWorkspace": {"id": 73, "name": "73"}},
        ]

    def test_foreign_toplevel_identifier_requires_same_visible_gamescope_window(self):
        window = {
            "pid": 987654,
            "class": "gamescope",
            "monitor": 41,
            "workspace": {"id": 73},
            "mapped": True,
            "hidden": False,
            "stableId": "test-handle-01",
        }
        self.assertEqual(
            validation.gamescope_toplevel_identifier(window, 987654, 41, 73, "test-handle-01"),
            "test-handle-01",
        )
        for changed in (
            {**window, "pid": 987655},
            {**window, "class": "chromium"},
            {**window, "monitor": 40},
            {**window, "workspace": {"id": 72}},
            {**window, "mapped": False},
            {**window, "hidden": True},
            {**window, "stableId": "test-handle-02"},
            {key: value for key, value in window.items() if key != "stableId"},
        ):
            with self.subTest(window=changed), self.assertRaises(ValueError):
                validation.gamescope_toplevel_identifier(changed, 987654, 41, 73, "test-handle-01")

    def test_toplevel_capture_command_uses_grim_foreign_handle(self):
        self.assertEqual(
            validation.build_toplevel_capture_argv("test-handle-01", Path("capture.png")),
            ["grim", "-T", "test-handle-01", "capture.png"],
        )

    def test_foreign_toplevel_identifier_rejects_empty_handle(self):
        window = {
            "pid": 987654,
            "class": "gamescope",
            "monitor": 41,
            "workspace": {"id": 73},
            "mapped": True,
            "hidden": False,
            "stableId": "",
        }
        with self.assertRaises(ValueError):
            validation.gamescope_toplevel_identifier(window, 987654, 41, 73)

    def test_lsfg_layer_can_be_disabled_for_one_process_without_config_changes(self):
        self.assertEqual(build_process_environment(False), ["SDL_VIDEODRIVER=wayland"])
        self.assertEqual(
            build_process_environment(True),
            ["SDL_VIDEODRIVER=wayland", "DISABLE_LSFGVK=1"],
        )

    def test_git_source_provenance_is_root_scoped_and_includes_untracked_files(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            repo = root / "repo"
            outside = root / "outside"
            repo.mkdir()
            outside.mkdir()
            subprocess.run(["git", "init", "-q", str(repo)], check=True)
            (repo / "tracked.txt").write_text("tracked source\n", encoding="utf-8")
            subprocess.run(["git", "-C", str(repo), "add", "tracked.txt"], check=True)
            subprocess.run(
                [
                    "git",
                    "-C",
                    str(repo),
                    "-c",
                    "user.name=Test User",
                    "-c",
                    "user.email=test@example.invalid",
                    "commit",
                    "-qm",
                    "initial test commit",
                ],
                check=True,
            )
            original_cwd = Path.cwd()
            try:
                os.chdir(outside)
                clean = validation.git_source_provenance(repo)
                self.assertTrue(clean["worktree_clean"])
                self.assertEqual(len(clean["git_commit"]), 40)
                self.assertEqual(len(clean["git_tree"]), 40)
                (repo / "untracked_source.py").write_text("source\n", encoding="utf-8")
                dirty = validation.git_source_provenance(repo)
                self.assertFalse(dirty["worktree_clean"])
            finally:
                os.chdir(original_cwd)

    def test_run_paths_are_portable_and_scoped(self):
        repo = Path("/opt/nkscale")
        run = repo / "validation/runs/case-a"
        self.assertEqual(portable_path(run / "capture.png", run, repo), "$RUN_DIR/capture.png")
        self.assertEqual(
            portable_path(repo / "target/release/nk-scale", run, repo),
            "$REPO/target/release/nk-scale",
        )
        self.assertEqual(portable_path("/usr/bin/gamescope", run, repo), "$SYSTEM/gamescope")
        self.assertEqual(
            portable_path("/home/testuser/bin/gamescope", run, repo, "/home/testuser"),
            "$HOME/bin/gamescope",
        )

    def test_symlink_path_inside_home_is_redacted(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            home = root / "private-home"
            home.mkdir()
            repo = root / "repo"
            run = repo / "validation/runs/case"
            external = root / "system-gamescope"
            external.touch()
            home_link = home / "gamescope"
            home_link.symlink_to(external)
            self.assertEqual(
                portable_path(home_link, run, repo, home), "$HOME/gamescope"
            )

    def test_gamescope_argv_redacts_custom_home_paths(self):
        repo = Path("/opt/nkscale")
        run = repo / "validation/runs/case-a"
        argv = [
            "--stats-path",
            "/home/testuser/results/stats.csv",
            "--",
            "/home/testuser/build/nk-scale",
        ]
        self.assertEqual(
            portable_gamescope_argv(
                "/home/testuser/bin/gamescope",
                argv,
                run,
                repo,
                "/home/testuser",
            ),
            [
                "$HOME/bin/gamescope",
                "--stats-path",
                "$HOME/results/stats.csv",
                "--",
                "$HOME/build/nk-scale",
            ],
        )

    def test_diagnostics_redact_physical_identity_and_keep_test_output_mode(self):
        raw = (
            "Display 2: Example Vendor TestPanel MODEL-0001 SERIAL-TEST-0001 (DP-TEST-OUTPUT) · 1920×1080 · 500 Hz at (400, 100)\n"
            "Display 9:  · 1920×1080 · 60 Hz at (8000, 300)\n"
            "GPU: Vulkan adapter: Example Adapter (RADV TEST) · DiscreteGpu · Mesa 99.0"
        )
        sanitized = sanitize_diagnostics(raw)
        self.assertEqual(
            sanitized,
            "Display <redacted>: PHYSICAL OUTPUT · mode withheld\n"
            "Display <redacted>: HEADLESS TEST OUTPUT · 1920×1080 · 60 Hz\n"
            "GPU: <adapter details redacted>",
        )
        for private in ("DP-TEST-OUTPUT", "SERIAL-TEST-0001", "MODEL-0001", "400, 100", "8000, 300", "500 Hz", "Example Adapter"):
            self.assertNotIn(private, sanitized)

    def test_gamescope_log_redacts_runtime_and_hardware_identifiers(self):
        raw = "\n".join(
            (
                "selecting physical device 'Example GPU (RADV TEST)':",
                "Running script file 'inspect.lua' (id: 0)",
                "Running compositor on wayland display 'gamescope-0'",
                "Starting Xwayland on :17",
                "stream available on node ID: 147",
                "PipeWire socket /run/user/424242/pipewire-77",
                "window xid: 0x1234",
                "wayland surface res id: 16",
                "Created swapchain: 0x55f801c526c0",
                "Display DP-TEST-OUTPUT at (400, 100)",
                "config at /home/testuser/.config/tool",
            )
        )
        sanitized = validation.sanitize_gamescope_log(raw, home_dir="/home/testuser")
        for private in (
            "Example GPU",
            "id: 0",
            "gamescope-0",
            ":17",
            "node ID: 147",
            "pipewire-77",
            "/run/user/424242",
            "0x1234",
            "res id: 16",
            "0x55f801c526c0",
            "DP-TEST-OUTPUT",
            "400, 100",
            "/home/testuser",
        ):
            self.assertNotIn(private, sanitized)
        self.assertIn("$HOME/.config/tool", sanitized)

    def test_written_result_artifact_redacts_runtime_identifiers(self):
        metadata = {
            "monitor": {
                "name": "HEADLESS-TEST-OUTPUT",
                "id": 41,
                "x": 8000,
                "y": 0,
                "width": 1920,
                "height": 1080,
                "refreshRate": 60.0,
                "scale": 1.25,
            },
            "workspace_id": 13,
            "focus_checkpoints": [
                {
                    "stage": "prelaunch",
                    "focused_monitor": "PHYSICAL-TEST-OUTPUT",
                    "focused_client_unchanged": True,
                }
            ],
            "focused_monitor_before": "PHYSICAL-TEST-OUTPUT",
            "focused_monitor_after": "PHYSICAL-TEST-OUTPUT",
            "gamescope_window": {
                "pid": 987654,
                "class": "gamescope",
                "monitor_id": 41,
                "workspace_id": 13,
                "logical_size": [1536, 864],
                "fullscreen_state": 2,
            },
            "software_versions": {
                "gamescope": "Gamescope 3.16.31",
                "gpu_diagnostic": "Example GPU (RADV TEST)",
                "kernel": "test-kernel",
                "hyprland": "test-build-id",
                "git_commit": "test-commit",
                "git_dirty": True,
            },
        }
        with tempfile.TemporaryDirectory() as temp_dir:
            output = Path(temp_dir)
            validation.write_result_metadata(output, metadata)
            serialized = (output / "result.json").read_text(encoding="utf-8")
            result = json.loads(serialized)
        for private in (
            "HEADLESS-TEST-OUTPUT",
            "PHYSICAL-TEST-OUTPUT",
            "8000",
            "987654",
            "monitor_id",
            "workspace_id",
            "gpu_diagnostic",
            "test-build-id",
            "test-kernel",
        ):
            self.assertNotIn(private, serialized)
        self.assertEqual(result["test_output"]["class"], "headless")
        self.assertEqual(result["test_output"]["mode"], {"width": 1920, "height": 1080})
        self.assertEqual(result["gamescope_window"]["logical_size"], [1536, 864])
        self.assertTrue(result["focus_checkpoints"][0]["focus_monitor_unchanged"])

    def test_committed_validation_metadata_contains_no_runtime_identity(self):
        validation_root = validation.REPO_ROOT / "validation"
        forbidden_patterns = (
            r"/home/",
            r"/(?:usr|etc|opt|var|proc|sys|run|tmp|root|mnt|media)/",
            r"(?<![\w$:/])/(?!/)[^\s'\"<>|]+",
            r"\b(?:HDMI|DP|eDP|LVDS|VGA|DVI)-[A-Za-z0-9]+(?:-[A-Za-z0-9]+)*\b",
            r"\bHEADLESS-\d+\b",
            r"\b(?:gamescope|wayland)-\d+\b",
            r"node ID:\s*\d+",
            r"(?:window xid:|swapchain:)\s*0x[0-9a-f]+",
            r"\b0x[0-9a-f]{9,}\b",
            r"--display-index(?:\s*=\s*|\s+)\d+",
            r"(?m)^Display\s+\d+:",
            r"\bsdl_display_index\b",
        )
        for path in validation_root.rglob("*"):
            if path.suffix.lower() not in {".json", ".csv", ".log", ".txt"}:
                continue
            text = path.read_text(encoding="utf-8", errors="replace")
            for pattern in forbidden_patterns:
                with self.subTest(path=path.relative_to(validation_root), pattern=pattern):
                    self.assertIsNone(re.search(pattern, text, re.IGNORECASE))
            if path.name == "result.json":
                data = json.loads(text)
                self.assertNotIn("monitor", data)
                self.assertNotIn("workspace_id", data)
                self.assertNotIn("focused_monitor_before", data)
                self.assertNotIn("focused_monitor_after", data)
                self.assertNotIn("sdl_display_index", data)
                self.assertNotIn("pid", data.get("gamescope_window", {}))
                argv = data.get("gamescope_argv", [])
                if "--display-index" in argv:
                    index = argv.index("--display-index")
                    self.assertEqual(argv[index + 1], "<redacted>")
            if path.name == "results.csv":
                headers = next(csv.reader(text.splitlines()))
                for private_header in (
                    "monitor",
                    "workspace_id",
                    "focused_monitor_before",
                    "focused_monitor_after",
                    "gpu_diagnostic",
                    "sdl_display_index",
                ):
                    self.assertNotIn(private_header, headers)
            if path.name == "diagnostics.txt":
                display_lines = [
                    line for line in text.splitlines() if line.startswith("Display ")
                ]
                self.assertTrue(display_lines)
                for line in display_lines:
                    self.assertRegex(
                        line,
                        r"^Display <redacted>: (?:PHYSICAL OUTPUT · mode withheld|HEADLESS TEST OUTPUT · \d+×\d+ · [0-9.]+ Hz)$",
                    )
                self.assertTrue(any("HEADLESS TEST OUTPUT" in line for line in display_lines))
                if "GPU:" in text:
                    self.assertIn("GPU: <adapter details redacted>", text)

    def test_sanitizer_redacts_system_paths_and_sdl_display_indices(self):
        text = (
            "/usr/bin/gamescope --display-index 4 "
            "'/usr/share/gamescope/scripts/example.lua' '/etc/gamescope/scripts'"
        )
        sanitized = validation.sanitize_runtime_identifiers(text)
        self.assertNotRegex(sanitized, r"/(?:usr|etc)/")
        self.assertNotRegex(sanitized, r"--display-index\s+\d+")
        self.assertIn("$SYSTEM/gamescope", sanitized)
        self.assertIn("--display-index <redacted>", sanitized)

        metadata = validation.sanitize_run_metadata(
            {
                "sdl_display_index": 4,
                "gamescope_argv": ["$SYSTEM/gamescope", "--display-index", "4"],
            }
        )
        self.assertNotIn("sdl_display_index", metadata)
        self.assertEqual(
            metadata["gamescope_argv"],
            ["$SYSTEM/gamescope", "--display-index", "<redacted>"],
        )

        raw_diagnostic = (
            "Gamescope executable: /usr/bin/gamescope\n"
            "Display 4:  · 1920×1080 · 60 Hz at (8000, 300)"
        )
        diagnostic = sanitize_diagnostics(raw_diagnostic)
        self.assertEqual(
            diagnostic,
            "Gamescope executable: $SYSTEM/gamescope\n"
            "Display <redacted>: HEADLESS TEST OUTPUT · 1920×1080 · 60 Hz",
        )
        self.assertEqual(sanitize_diagnostics(diagnostic), diagnostic)
        legacy_diagnostic = (
            "Display 4: PHYSICAL OUTPUT · mode withheld\n"
            "Display 9: HEADLESS TEST OUTPUT · 1920×1080 · 60 Hz"
        )
        self.assertEqual(
            sanitize_diagnostics(legacy_diagnostic),
            "Display <redacted>: PHYSICAL OUTPUT · mode withheld\n"
            "Display <redacted>: HEADLESS TEST OUTPUT · 1920×1080 · 60 Hz",
        )

    def test_sanitizer_redacts_unlisted_absolute_paths_and_both_index_forms(self):
        text = (
            "device=/dev/dri/renderD128 module='/lib/modules/example.ko' "
            "game='/opt/Games/My Game/bin/game' "
            "--display-index=4 --display-index = 9 "
            "url=https://gamescope.dev/docs?asset=/dev/dri/renderD128"
        )
        sanitized = validation.sanitize_runtime_identifiers(text)
        for private in ("/lib/", "/opt/", "My Game", "--display-index=4", "--display-index = 9"):
            self.assertNotIn(private, sanitized)
        self.assertIn("device=<filesystem-path>", sanitized)
        self.assertIn("url=https://gamescope.dev/docs?asset=/dev/dri/renderD128", sanitized)
        self.assertIn("--display-index=<redacted>", sanitized)
        self.assertIn("--display-index = <redacted>", sanitized)
        self.assertIn("https://gamescope.dev/docs", sanitized)
        self.assertEqual(validation.sanitize_runtime_identifiers(sanitized), sanitized)

    def test_documented_pixel_metrics_match_machine_readable_comparisons(self):
        validation_root = validation.REPO_ROOT / "validation"
        comparisons = json.loads(
            (validation_root / "image-differences.json").read_text(encoding="utf-8")
        )["comparisons"]
        docs = (validation.REPO_ROOT / "docs/validation.md").read_text(encoding="utf-8")
        start = docs.index("| Comparison | Changed luma pixels | Changed | Mean absolute luma difference |")
        table = docs[start:].split("\n\n", 1)[0]
        rows = [line for line in table.splitlines() if line.startswith("|")][2:]
        self.assertEqual(len(rows), len(comparisons))
        for row, comparison in zip(rows, comparisons, strict=True):
            cells = [cell.strip() for cell in row.strip("|").split("|")]
            metrics = comparison["full_frame"]
            expected_labels = {
                "linear_1280_to_1920_vs_fsr_sharpness_0": "Linear 1280×720→1920×1080 vs FSR sharpness 0",
                "linear_1280_to_1920_vs_fsr_sharpness_6": "Linear 1280×720→1920×1080 vs FSR sharpness 6",
                "linear_1280_to_1920_vs_fsr_sharpness_20": "Linear 1280×720→1920×1080 vs FSR sharpness 20",
                "fsr_sharpness_0_vs_6": "FSR sharpness 0 vs 6",
                "fsr_sharpness_6_vs_20": "FSR sharpness 6 vs 20",
                "fsr6_default_layer_vs_disabled_layer": "Default LSFG-VK layer vs process-disabled",
            }
            with self.subTest(comparison=comparison["name"]):
                self.assertEqual(cells[0], expected_labels[comparison["name"]])
                self.assertEqual(int(cells[1].replace(",", "")), metrics["changed_luma_pixels"])
                self.assertEqual(cells[2], f"{metrics['changed_luma_ratio'] * 100:.4f}%")
                self.assertEqual(cells[3], f"{metrics['mean_absolute_luma_difference']:.4f}")

    def test_dispatch_text_writer_redacts_runtime_output(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            path = Path(temp_dir) / "dispatch.txt"
            validation.write_sanitized_text(
                path,
                "monitor DP-TEST-OUTPUT id: 7 monitor_id: 41 workspace_id: 73 stableId: test-handle-01 PipeWire pipewire-0 at (400, 100)",
            )
            text = path.read_text(encoding="utf-8")
        for private in (
            "DP-TEST-OUTPUT",
            "id: 7",
            "monitor_id: 41",
            "workspace_id: 73",
            "stableId: test-handle-01",
            "pipewire-0",
            "400, 100",
        ):
            self.assertNotIn(private, text)

    def test_home_paths_are_redacted_in_published_logs(self):
        self.assertEqual(
            sanitize_home_paths("config at /home/testuser/.config/tool", "/home/testuser"),
            "config at $HOME/.config/tool",
        )

    def test_custom_gamescope_executable_is_pinned_for_cleanup(self):
        if not hasattr(os, "pidfd_open") or not hasattr(signal, "pidfd_send_signal"):
            self.skipTest("Linux pidfd signaling is unavailable")
        with tempfile.TemporaryDirectory() as temp_dir:
            marker = str(Path(temp_dir) / "custom-owned-stats.csv")
            custom_executable = Path(temp_dir) / "gamescope-custom"
            custom_executable.symlink_to(sys.executable)
            child = subprocess.Popen(
                [
                    str(custom_executable),
                    "-c",
                    "import time; time.sleep(30)",
                    marker,
                ],
                stdin=subprocess.DEVNULL,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
            )
            owned = None
            try:
                deadline = time.monotonic() + 3.0
                while time.monotonic() < deadline and owned is None:
                    owned = find_gamescope_process(
                        marker, expected_executable=str(custom_executable)
                    )
                    if owned is None:
                        time.sleep(0.02)
                self.assertIsNotNone(owned)
                self.assertEqual(owned[0], child.pid)
                terminate_owned_process(owned, grace_seconds=1.0)
                owned = None
                child.wait(timeout=2.0)
                self.assertNotEqual(child.returncode, 0)
            finally:
                if owned is not None:
                    terminate_owned_process(owned, grace_seconds=0.5)
                if child.poll() is None:
                    child.kill()
                    child.wait(timeout=2.0)

    def test_cleanup_uses_a_pidfd_for_the_owned_gamescope_process(self):
        if not hasattr(os, "pidfd_open") or not hasattr(signal, "pidfd_send_signal"):
            self.skipTest("Linux pidfd signaling is unavailable")
        with tempfile.TemporaryDirectory() as temp_dir:
            marker = str(Path(temp_dir) / "owned-stats.csv")
            fake_executable = str(Path(temp_dir) / "gamescope")
            code = (
                "import os,sys; os.execv(sys.executable, ["
                f"{fake_executable!r}, '-c', 'import time; time.sleep(30)', {marker!r}])"
            )
            child = subprocess.Popen(
                [sys.executable, "-c", code],
                stdin=subprocess.DEVNULL,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
            )
            owned = None
            try:
                deadline = time.monotonic() + 3.0
                while time.monotonic() < deadline and owned is None:
                    owned = find_gamescope_process(marker)
                    if owned is None:
                        time.sleep(0.02)
                self.assertIsNotNone(owned)
                self.assertEqual(owned[0], child.pid)
                terminate_owned_process(owned, grace_seconds=1.0)
                owned = None
                child.wait(timeout=2.0)
                self.assertNotEqual(child.returncode, 0)
            finally:
                if owned is not None:
                    terminate_owned_process(owned, grace_seconds=0.5)
                if child.poll() is None:
                    child.kill()
                    child.wait(timeout=2.0)

    def test_focus_unchanged_requires_same_monitor_and_client(self):
        monitors = [{"name": "DP-TEST-OUTPUT", "focused": True}]
        self.assertTrue(focus_unchanged("DP-TEST-OUTPUT", "test-window-01", monitors, {"address": "test-window-01"}))
        self.assertFalse(focus_unchanged("DP-TEST-OUTPUT", "test-window-01", monitors, {"address": "test-window-02"}))
        self.assertFalse(
            focus_unchanged("DP-TEST-OUTPUT", "test-window-01", [{"name": "HDMI-TEST-OUTPUT", "focused": True}], {"address": "test-window-01"})
        )

    def test_physical_monitor_is_rejected_before_launch(self):
        with self.assertRaises(ValueError):
            select_headless_monitor(self.monitors, "DP-TEST-OUTPUT")

    def test_incomplete_headless_metadata_is_rejected(self):
        incomplete = {
            "name": "HEADLESS-TEST-INCOMPLETE",
            "id": 49,
            "x": 0,
            "y": 0,
            "width": 1920,
            "height": 1080,
            "make": "",
            "model": "",
            "serial": "",
            "description": "",
            "activeWorkspace": {"id": 79, "name": "79"},
        }
        with self.assertRaises(ValueError):
            select_headless_monitor([incomplete], "HEADLESS-TEST-INCOMPLETE")

    def test_headless_name_alone_is_not_authoritative(self):
        physical = dict(self.monitors[1], make="Example Vendor", model="Physical Display", serial="TEST-123")
        with self.assertRaises(ValueError):
            select_headless_monitor([physical], "HEADLESS-TEST-OUTPUT")

    def test_headless_monitor_identity_must_remain_stable(self):
        self.assertTrue(same_headless_monitor(self.monitors[1], dict(self.monitors[1])))
        moved = dict(self.monitors[1], x=12200)
        self.assertFalse(same_headless_monitor(self.monitors[1], moved))
        changed_mode = dict(self.monitors[1], height=900)
        self.assertFalse(same_headless_monitor(self.monitors[1], changed_mode))

    def test_prelaunch_revalidation_requires_same_idle_headless_output(self):
        expected = self.monitors[1]
        self.assertEqual(
            revalidate_headless_target(expected, self.monitors, [])["id"], 41
        )
        changed = dict(expected, width=1600)
        with self.assertRaises(ValueError):
            revalidate_headless_target(expected, [self.monitors[0], changed], [])
        own_window = {"monitor": 41, "pid": 987654, "class": "gamescope"}
        self.assertEqual(
            revalidate_headless_target(
                expected, self.monitors, [own_window], allowed_pid=987654
            )["id"],
            41,
        )
        with self.assertRaises(ValueError):
            revalidate_headless_target(
                expected,
                self.monitors,
                [own_window, {"monitor": 41, "pid": 987655, "class": "other"}],
                allowed_pid=987654,
            )

    def test_headless_monitor_must_be_unique_and_unoccupied(self):
        selected = select_headless_monitor(self.monitors, "HEADLESS-TEST-OUTPUT")
        self.assertEqual(selected["id"], 41)
        with self.assertRaises(ValueError):
            select_headless_monitor(self.monitors + [dict(self.monitors[1])], "HEADLESS-TEST-OUTPUT")

    def test_occupied_headless_monitor_is_rejected(self):
        with self.assertRaises(ValueError):
            ensure_monitor_idle(
                self.monitors[1],
                [{"monitor": 41, "class": "chromium", "mapped": True}],
            )

    def test_sdl_index_matches_wayland_monitor_coordinates(self):
        diagnostics = """GPU: Vulkan adapter: test
Display 0: HDMI · 3840×2160 · 60 Hz at (0, 0)
Display 9:  · 1920×1080 · 60 Hz at (8000, 300)
"""
        self.assertEqual(parse_sdl_display_index(diagnostics, self.monitors[1]), 9)

    def test_prelaunch_sdl_index_change_fails_closed(self):
        original = "Display 9:  · 1920×1080 · 60 Hz at (8000, 300)"
        shifted = "Display 8:  · 1920×1080 · 60 Hz at (8000, 300)"
        self.assertEqual(revalidate_sdl_display_index(original, self.monitors[1], 9), 9)
        with self.assertRaises(ValueError):
            revalidate_sdl_display_index(shifted, self.monitors[1], 9)

    def test_gamescope_argv_keeps_requested_filter_and_resolutions(self):
        argv = build_gamescope_argv(
            display_index=9,
            source=(1280, 720),
            target=(1920, 1080),
            filter_name="fsr",
            sharpness=6,
            stats_path="/tmp/nkscale-stats.csv",
            app_path="/repo/target/release/nk-scale",
        )
        self.assertIn("--filter", argv)
        self.assertEqual(argv[argv.index("--filter") + 1], "fsr")
        self.assertEqual(argv[argv.index("--display-index") + 1], "9")
        self.assertEqual(argv[argv.index("--nested-width") + 1], "1280")
        self.assertEqual(argv[argv.index("--output-height") + 1], "1080")
        self.assertEqual(argv[-2:], ["--test-pattern", "--pattern-size=1280x720"])
        self.assertNotIn("--fullscreen", argv)

    def test_fullscreen_dispatch_targets_only_the_launched_process(self):
        lua = build_lua_fullscreen_dispatch(987654)
        self.assertIn('window = "pid:987654"', lua)
        self.assertIn('mode = "fullscreen"', lua)
        self.assertIn('action = "set"', lua)

    def test_dispatch_is_scoped_to_headless_monitor_without_focus(self):
        lua = build_lua_dispatch(
            "exec /usr/bin/gamescope ...", monitor_name="HEADLESS-TEST-OUTPUT", workspace_id=73
        )
        self.assertIn('monitor = "HEADLESS-TEST-OUTPUT silent"', lua)
        self.assertIn('workspace = "73 silent"', lua)
        self.assertIn("no_initial_focus = true", lua)
        self.assertIn("hl.dsp.exec_cmd", lua)
        self.assertNotIn("fullscreen = true", lua)


if __name__ == "__main__":
    unittest.main()
