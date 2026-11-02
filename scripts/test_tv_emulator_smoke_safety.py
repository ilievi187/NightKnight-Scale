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
