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
