import os
from pathlib import Path
import subprocess
import tempfile
import unittest


REPO_ROOT = Path(__file__).resolve().parents[1]
SMOKE_SCRIPT = REPO_ROOT / "android" / "scripts" / "tv-emulator-smoke.sh"


class TvEmulatorSmokeSafetyTests(unittest.TestCase):
    def run_smoke_with_serial(self, serial):
        with tempfile.TemporaryDirectory(prefix="nkscale-adb-safety-") as tmp:
            root = Path(tmp)
            fake_bin = root / "bin"
            fake_bin.mkdir()
            calls = root / "adb-calls.txt"
            fake_adb = fake_bin / "adb"
            fake_adb.write_text(
                "#!/bin/sh\nprintf '%s\\n' \"$*\" >> \"$FAKE_ADB_CALLS\"\nexit 0\n",
                encoding="utf-8",
            )
            fake_adb.chmod(0o755)
            env = os.environ.copy()
            env["PATH"] = f"{fake_bin}{os.pathsep}{env.get('PATH', '')}"
            env["FAKE_ADB_CALLS"] = str(calls)
            if serial is None:
                env.pop("ANDROID_SERIAL", None)
            else:
                env["ANDROID_SERIAL"] = serial
            result = subprocess.run(
                ["bash", str(SMOKE_SCRIPT), "unused.apk", str(root / "output")],
                env=env,
                capture_output=True,
                text=True,
                timeout=5,
                check=False,
            )
            return result.returncode, result.stdout + result.stderr, calls.exists()

    def test_dense_ui_diagnostics_marker_matches_auto_1080p_startup_request(self):
        script = SMOKE_SCRIPT.read_text(encoding="utf-8")
        first_launch = next(
            line
            for line in script.splitlines()
            if line.startswith('adb shell am start -W -n "$activity_name"')
        )
        self.assertNotIn("--es nkscale_output_resolution", first_launch)
        diagnostics_section = script.split("assert_diagnostics_reachable_at_1080p() {", 1)[1].split(
            "\n}\n\nassert_diagnostics_reachable_at_1080p", 1
        )[0]
        self.assertIn(
            "output_resolution_verified requested=auto effective=1080p surface=1920×1080 egl=1920×1080",
            diagnostics_section,
        )
        native4k_section = script.split("assert_native4k_linear_final_strategy() {", 1)[1].split(
            "\n}\n\nassert_diagnostics_reachable_at_1080p", 1
        )[0]
        self.assertIn(
            "output_resolution_verified requested=1080p effective=1080p surface=1920×1080 egl=1920×1080",
            native4k_section,
        )
        explicit_1080p_restart = native4k_section.index(
            'adb shell am start -W -n "$activity_name" --es nkscale_output_resolution 1080p'
        )
        verified_1080p_marker = native4k_section.index(
            "output_resolution_verified requested=1080p effective=1080p surface=1920×1080 egl=1920×1080"
        )
        self.assertLess(explicit_1080p_restart, verified_1080p_marker)

    def run_native_validation(self, log):
        script = SMOKE_SCRIPT.read_text(encoding="utf-8")
        start = script.find("validate_native4k_snapshot() {")
        if start < 0:
            # Exercise the existing assertion until the snapshot validator is introduced.
            start = script.index("assert_c_surface_plan_matches_output() {")
            end = script.index("\n}\n", start) + 3
            command = script[start:end] + '\nassert_c_surface_plan_matches_output "$(cat)" "1920×1080" "a_1080p_system_scaler" false ""\n'
        else:
            end = script.index("\n}\n", start) + 3
            command = script[start:end] + '\nvalidate_native4k_snapshot "$(cat)"\n'
        return subprocess.run(["bash", "-c", command], input=log, text=True, capture_output=True, check=False)

    def fallback_log(self):
        return "\n".join([
            "output_resolution_request requested=native_4k requested_buffer=3840×2160",
            "surface=3840×2160 source=720×576 target=2880×2160 requested_buffer=3840×2160 strategy_requested=c_easu_1080_linear_2160 strategy_effective=c_easu_1080_linear_2160 linear_final=true native_planner_runtime_plan=rust native_planner_plan_calls=1",
            "output_resolution_fallback requested=native_4k fallback_buffer=1080p effective=unverified reason=slow",
            "output_resolution_request requested=native_4k requested_buffer=1920×1080",
            "surface=1920×1080 source=720×576 target=1440×1080 requested_buffer=3840×2160 strategy_requested=c_easu_1080_linear_2160 strategy_effective=a_1080p_system_scaler linear_final=false native_planner_runtime_plan=rust native_planner_plan_calls=1",
            "decoded_size=720×576 target=1440×1080 surface=1920×1080 native_planner_runtime_plan=rust native_planner_plan_calls=1",
            "output_resolution_verified requested=native_4k effective=1080p surface=1920×1080 egl=1920×1080 target=1440×1080",
        ])
    def test_geometry_update_may_change_target_after_surface_plan(self):
        log = self.fallback_log().replace(
            "decoded_size=720×576 target=1440×1080", "decoded_size=1024×576 target=1920×1080"
        ).replace("egl=1920×1080 target=1440×1080", "egl=1920×1080 target=1920×1080")
        result = self.run_native_validation(log)
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_old_plans_cannot_validate_new_request(self):
        log = self.fallback_log() + "\noutput_resolution_request requested=native_4k requested_buffer=1920×1080\n" + self.fallback_log().splitlines()[-1]
        self.assertNotEqual(self.run_native_validation(log).returncode, 0)

    def test_fallback_reason_does_not_survive_a_later_1080p_request(self):
        lines = self.fallback_log().splitlines()
        log = self.fallback_log() + "\n" + "\n".join(lines[3:])
        result = self.run_native_validation(log)
        self.assertNotEqual(result.returncode, 0, result.stdout)

    def test_snapshot_rejects_split_planner_fields_and_prefix_call_count(self):
        log = self.fallback_log()
        for bad in (
            log.replace("native_planner_plan_calls=1", "native_planner_plan_calls=10"),
            log.replace("native_planner_runtime_plan=rust native_planner_plan_calls=1", "native_planner_runtime_plan=rust\nnative_planner_plan_calls=1"),
        ):
            self.assertNotEqual(self.run_native_validation(bad).returncode, 0)

    def test_timeout_fallback_marker_is_accepted(self):
        log = self.fallback_log().replace("reason=slow", "reason=4K EGL surface was not confirmed within 5 seconds")
        result = self.run_native_validation(log)
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_aspect_fit_fallback_after_transient_4k_callback(self):
        result = self.run_native_validation(self.fallback_log())
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("1440×1080", result.stdout)

    def test_rejects_stale_or_malformed_final_snapshots(self):
        log = self.fallback_log()
        cases = {
            "later request": log + "\noutput_resolution_request requested=native_4k requested_buffer=3840×2160",
            "late fallback": log + "\noutput_resolution_auto_fallback requested=native_4k reason=slow",
            "blank latest reason": log + "\noutput_resolution_fallback requested=native_4k reason=\n" + log.splitlines()[-1],
            "mismatched target": log.replace("egl=1920×1080 target=1440×1080", "egl=1920×1080 target=1920×1080"),
            "invalid target": log.replace("egl=1920×1080 target=1440×1080", "egl=1920×1080 target=1440×1080×1"),
            "oversized target": log.replace("egl=1920×1080 target=1440×1080", "egl=1920×1080 target=2147483648×1080"),
            "duplicate field": log.replace("egl=1920×1080 target=1440×1080", "egl=1920×1080 target=1440×1080\ttarget=1440×1080"),
            "incomplete final plan": log + "\ndecoded_size=720×576 target=1440×1080 surface=1920×1080",
            "extra calls": log.replace("native_planner_plan_calls=1", "native_planner_plan_calls=10", 1),
        }
        for name, snapshot in cases.items():
            with self.subTest(name=name):
                result = self.run_native_validation(snapshot)
                self.assertNotEqual(result.returncode, 0, result.stdout)

    def test_native_4k_requires_profile_after_latest_status(self):
        log = "\n".join([
            "output_resolution_request requested=native_4k requested_buffer=3840×2160",
            "surface=3840×2160 source=720×576 target=2880×2160 requested_buffer=3840×2160 strategy_requested=c_easu_1080_linear_2160 strategy_effective=c_easu_1080_linear_2160 linear_final=true native_planner_runtime_plan=rust native_planner_plan_calls=1",
            "decoded_size=720×576 target=2880×2160 surface=3840×2160 native_planner_runtime_plan=rust native_planner_plan_calls=1",
            "output_resolution_verified requested=native_4k effective=native_4k surface=3840×2160 egl=3840×2160 target=2880×2160",
        ])
        self.assertNotEqual(self.run_native_validation("native4k_auto_profile=validated\n" + log).returncode, 0)
        result = self.run_native_validation(log + "\nnative4k_auto_profile=validated")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("2880×2160", result.stdout)

    def test_non_emulator_serial_is_rejected_before_any_adb_command(self):
        code, output, adb_was_called = self.run_smoke_with_serial("192.0.2.10:5555")
        self.assertEqual(code, 2, output)
        self.assertIn("refusing to issue adb commands", output.casefold())
        self.assertFalse(adb_was_called, "safety check must run before every ADB interaction")

    def test_missing_serial_is_rejected_before_any_adb_command(self):
        code, output, adb_was_called = self.run_smoke_with_serial(None)
        self.assertEqual(code, 2, output)
        self.assertIn("refusing to issue adb commands", output.casefold())
        self.assertFalse(adb_was_called, "an implicit ADB target could be a physical TV")


if __name__ == "__main__":
    unittest.main()
