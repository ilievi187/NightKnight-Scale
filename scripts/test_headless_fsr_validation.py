import os
from pathlib import Path
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
            {"name": "DP-1", "id": 0, "x": 3840, "y": 0, "width": 1920, "height": 1080, "make": "Example Vendor", "model": "Physical Display", "serial": "TEST-123", "description": "Example Vendor Physical Display TEST-123"},
            {"name": "HEADLESS-3", "id": 4, "x": 10240, "y": 0, "width": 1920, "height": 1080, "refreshRate": 60.0, "scale": 1.25, "make": "", "model": "", "serial": "", "description": "", "activeWorkspace": {"id": 3, "name": "3"}},
        ]

    def test_foreign_toplevel_identifier_requires_same_visible_gamescope_window(self):
        window = {
            "pid": 1234,
            "class": "gamescope",
            "monitor": 4,
            "workspace": {"id": 3},
            "mapped": True,
            "hidden": False,
            "stableId": "test-handle-01",
        }
        self.assertEqual(
            validation.gamescope_toplevel_identifier(window, 1234, 4, 3, "test-handle-01"),
            "test-handle-01",
        )
        for changed in (
            {**window, "pid": 4321},
            {**window, "class": "chromium"},
            {**window, "monitor": 0},
            {**window, "workspace": {"id": 1}},
            {**window, "mapped": False},
            {**window, "hidden": True},
            {**window, "stableId": "180000cc"},
            {key: value for key, value in window.items() if key != "stableId"},
        ):
            with self.subTest(window=changed), self.assertRaises(ValueError):
                validation.gamescope_toplevel_identifier(changed, 1234, 4, 3, "test-handle-01")

    def test_toplevel_capture_command_uses_grim_foreign_handle(self):
        self.assertEqual(
            validation.build_toplevel_capture_argv("test-handle-01", Path("capture.png")),
            ["grim", "-T", "test-handle-01", "capture.png"],
        )

    def test_foreign_toplevel_identifier_rejects_empty_handle(self):
        window = {
            "pid": 1234,
            "class": "gamescope",
            "monitor": 4,
            "workspace": {"id": 3},
            "mapped": True,
            "hidden": False,
            "stableId": "",
        }
        with self.assertRaises(ValueError):
            validation.gamescope_toplevel_identifier(window, 1234, 4, 3)

    def test_lsfg_layer_can_be_disabled_for_one_process_without_config_changes(self):
        self.assertEqual(build_process_environment(False), ["SDL_VIDEODRIVER=wayland"])
        self.assertEqual(
            build_process_environment(True),
            ["SDL_VIDEODRIVER=wayland", "DISABLE_LSFGVK=1"],
        )

    def test_run_paths_are_portable_and_scoped(self):
        repo = Path("/opt/nkscale")
        run = repo / "validation/runs/case-a"
        self.assertEqual(portable_path(run / "capture.png", run, repo), "$RUN_DIR/capture.png")
        self.assertEqual(
            portable_path(repo / "target/release/nk-scale", run, repo),
            "$REPO/target/release/nk-scale",
        )
        self.assertEqual(portable_path("/usr/bin/gamescope", run, repo), "/usr/bin/gamescope")
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

    def test_diagnostics_strip_monitor_serials_but_keep_connector_and_mode(self):
        raw = (
            "Display 0: Example Vendor TestPanel MODEL-0001 SERIAL-TEST-0001 (DP-1) · 1920×1080 · 500 Hz at (3840, 0)\n"
            "Display 4:  · 1920×1080 · 60 Hz at (10240, 0)"
        )
        sanitized = sanitize_diagnostics(raw)
        self.assertEqual(
            sanitized,
            "Display 0: DP-1 · 1920×1080 · 500 Hz at (3840, 0)\n"
            "Display 4: HEADLESS OUTPUT · 1920×1080 · 60 Hz at (10240, 0)",
        )
        self.assertNotIn("SERIAL-TEST-0001", sanitized)

    def test_home_paths_are_redacted_in_published_logs(self):
        self.assertEqual(
            sanitize_home_paths("config at /home/testuser/.config/tool", "/home/testuser"),
            "config at $HOME/.config/tool",
        )

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
        monitors = [{"name": "DP-1", "focused": True}]
        self.assertTrue(focus_unchanged("DP-1", "0x1", monitors, {"address": "0x1"}))
        self.assertFalse(focus_unchanged("DP-1", "0x1", monitors, {"address": "0x2"}))
        self.assertFalse(
            focus_unchanged("DP-1", "0x1", [{"name": "HDMI-A-1", "focused": True}], {"address": "0x1"})
        )

    def test_physical_monitor_is_rejected_before_launch(self):
        with self.assertRaises(ValueError):
            select_headless_monitor(self.monitors, "DP-1")

    def test_incomplete_headless_metadata_is_rejected(self):
        incomplete = {
            "name": "HEADLESS-9",
            "id": 9,
            "x": 0,
            "y": 0,
            "width": 1920,
            "height": 1080,
            "make": "",
            "model": "",
            "serial": "",
            "description": "",
            "activeWorkspace": {"id": 9, "name": "9"},
        }
        with self.assertRaises(ValueError):
            select_headless_monitor([incomplete], "HEADLESS-9")

    def test_headless_name_alone_is_not_authoritative(self):
        physical = dict(self.monitors[1], make="Example Vendor", model="Physical Display", serial="TEST-123")
        with self.assertRaises(ValueError):
            select_headless_monitor([physical], "HEADLESS-3")

    def test_headless_monitor_identity_must_remain_stable(self):
        self.assertTrue(same_headless_monitor(self.monitors[1], dict(self.monitors[1])))
        moved = dict(self.monitors[1], x=12200)
        self.assertFalse(same_headless_monitor(self.monitors[1], moved))
        changed_mode = dict(self.monitors[1], height=900)
        self.assertFalse(same_headless_monitor(self.monitors[1], changed_mode))

    def test_prelaunch_revalidation_requires_same_idle_headless_output(self):
        expected = self.monitors[1]
        self.assertEqual(
            revalidate_headless_target(expected, self.monitors, [])["id"], 4
        )
        changed = dict(expected, width=1600)
        with self.assertRaises(ValueError):
            revalidate_headless_target(expected, [self.monitors[0], changed], [])
        own_window = {"monitor": 4, "pid": 424242, "class": "gamescope"}
        self.assertEqual(
            revalidate_headless_target(
                expected, self.monitors, [own_window], allowed_pid=424242
            )["id"],
            4,
        )
        with self.assertRaises(ValueError):
            revalidate_headless_target(
                expected,
                self.monitors,
                [own_window, {"monitor": 4, "pid": 123, "class": "other"}],
                allowed_pid=424242,
            )

    def test_headless_monitor_must_be_unique_and_unoccupied(self):
        selected = select_headless_monitor(self.monitors, "HEADLESS-3")
        self.assertEqual(selected["id"], 4)
        with self.assertRaises(ValueError):
            select_headless_monitor(self.monitors + [dict(self.monitors[1])], "HEADLESS-3")

    def test_occupied_headless_monitor_is_rejected(self):
        with self.assertRaises(ValueError):
            ensure_monitor_idle(
                self.monitors[1],
                [{"monitor": 4, "class": "chromium", "mapped": True}],
            )

    def test_sdl_index_matches_wayland_monitor_coordinates(self):
        diagnostics = """GPU: Vulkan adapter: test
Display 0: HDMI · 3840×2160 · 60 Hz at (0, 0)
Display 4:  · 1920×1080 · 60 Hz at (10240, 0)
"""
        self.assertEqual(parse_sdl_display_index(diagnostics, self.monitors[1]), 4)

    def test_prelaunch_sdl_index_change_fails_closed(self):
        original = "Display 4:  · 1920×1080 · 60 Hz at (10240, 0)"
        shifted = "Display 3:  · 1920×1080 · 60 Hz at (10240, 0)"
        self.assertEqual(revalidate_sdl_display_index(original, self.monitors[1], 4), 4)
        with self.assertRaises(ValueError):
            revalidate_sdl_display_index(shifted, self.monitors[1], 4)

    def test_gamescope_argv_keeps_requested_filter_and_resolutions(self):
        argv = build_gamescope_argv(
            display_index=4,
            source=(1280, 720),
            target=(1920, 1080),
            filter_name="fsr",
            sharpness=6,
            stats_path="/tmp/nkscale-stats.csv",
            app_path="/repo/target/release/nk-scale",
        )
        self.assertIn("--filter", argv)
        self.assertEqual(argv[argv.index("--filter") + 1], "fsr")
        self.assertEqual(argv[argv.index("--display-index") + 1], "4")
        self.assertEqual(argv[argv.index("--nested-width") + 1], "1280")
        self.assertEqual(argv[argv.index("--output-height") + 1], "1080")
        self.assertEqual(argv[-2:], ["--test-pattern", "--pattern-size=1280x720"])
        self.assertNotIn("--fullscreen", argv)

    def test_fullscreen_dispatch_targets_only_the_launched_process(self):
        lua = build_lua_fullscreen_dispatch(424242)
        self.assertIn('window = "pid:424242"', lua)
        self.assertIn('mode = "fullscreen"', lua)
        self.assertIn('action = "set"', lua)

    def test_dispatch_is_scoped_to_headless_monitor_without_focus(self):
        lua = build_lua_dispatch(
            "exec /usr/bin/gamescope ...", monitor_name="HEADLESS-3", workspace_id=3
        )
        self.assertIn('monitor = "HEADLESS-3 silent"', lua)
        self.assertIn('workspace = "3 silent"', lua)
        self.assertIn("no_initial_focus = true", lua)
        self.assertIn("hl.dsp.exec_cmd", lua)
        self.assertNotIn("fullscreen = true", lua)


if __name__ == "__main__":
    unittest.main()
