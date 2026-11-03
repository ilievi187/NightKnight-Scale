import os
import re
import struct
import subprocess
import tempfile
import unittest
import zipfile
from pathlib import Path

from verify_rust_apk import ApkValidationError, EXPECTED_ABIS, verify_rust_apk


def synthetic_elf(elf_class: int, machine: int) -> bytes:
    header = bytearray(64 if elf_class == 2 else 52)
    header[0:4] = b"\x7fELF"
    header[4] = elf_class
    header[5] = 1
    struct.pack_into("<H", header, 18, machine)
    return bytes(header)


class VerifyRustApkTests(unittest.TestCase):
    def make_apk(self, entries: dict[str, bytes]) -> Path:
        temporary = tempfile.NamedTemporaryFile(suffix=".apk", delete=False)
        temporary.close()
        apk = Path(temporary.name)
        with zipfile.ZipFile(apk, "w") as archive:
            for name, content in entries.items():
                archive.writestr(name, content)
        self.addCleanup(apk.unlink, missing_ok=True)
        return apk

    def test_accepts_all_supported_abis_with_matching_elf_machines(self) -> None:
        entries = {
            f"lib/{abi}/libnightknight_render_core.so": synthetic_elf(elf_class, machine)
            for abi, (elf_class, machine) in EXPECTED_ABIS.items()
        }
        self.assertEqual(set(EXPECTED_ABIS), set(verify_rust_apk(self.make_apk(entries))))

    def test_rejects_missing_required_native_abi(self) -> None:
        entries = {
            "lib/x86/libnightknight_render_core.so": synthetic_elf(1, 3),
        }
        with self.assertRaisesRegex(ApkValidationError, "arm64-v8a"):
            verify_rust_apk(self.make_apk(entries))

    def test_rejects_wrong_elf_machine_for_abi(self) -> None:
        entries = {
            f"lib/{abi}/libnightknight_render_core.so": synthetic_elf(elf_class, machine)
            for abi, (elf_class, machine) in EXPECTED_ABIS.items()
        }
        entries["lib/arm64-v8a/libnightknight_render_core.so"] = synthetic_elf(2, 3)
        with self.assertRaisesRegex(ApkValidationError, "arm64-v8a"):
            verify_rust_apk(self.make_apk(entries))

    def test_gradle_tracks_native_builder_and_ndk_version_for_incremental_rebuilds(self) -> None:
        repository_root = Path(__file__).resolve().parents[1]
        build_gradle = (repository_root / "android/app/build.gradle.kts").read_text()
        build_script = (repository_root / "android/scripts/build-rust-native.sh").read_text()
        self.assertIn("inputs.file(rustBuildScript)", build_gradle)
        self.assertIn('inputs.property("androidNdkVersion", pinnedAndroidNdkVersion)', build_gradle)
        self.assertIn('--manifest-path "$repo_root/Cargo.toml"', build_script)

    def test_native_build_pins_compiler_and_r27_macos_host_tag(self) -> None:
        repository_root = Path(__file__).resolve().parents[1]
        toolchain_file = repository_root / "rust-toolchain.toml"
        self.assertTrue(toolchain_file.is_file(), "Rust toolchain must be pinned")
        toolchain = toolchain_file.read_text()
        build_gradle = (repository_root / "android/app/build.gradle.kts").read_text()
        build_script = (repository_root / "android/scripts/build-rust-native.sh").read_text()
        workflow = (repository_root / ".github/workflows/ci.yml").read_text()
        self.assertIn('channel = "1.99.0"', toolchain)
        self.assertIn("rustup toolchain install 1.99.0", workflow)
        self.assertIn('inputs.file(repositoryRoot.resolve("rust-toolchain.toml"))', build_gradle)
        self.assertIn('inputs.property("rustVersion", pinnedRustVersion)', build_gradle)
        self.assertIn('Darwin:arm64) host_tag="darwin-x86_64"', build_script)

    def test_smoke_matches_exact_native_plan_fields_on_one_record(self) -> None:
        repository_root = Path(__file__).resolve().parents[1]
        smoke_script = (repository_root / "android/scripts/tv-emulator-smoke.sh").read_text()
        self.assertIn("wait_for_exact_plan_record_occurrences", smoke_script)
        self.assertIn("surface=.*native_planner_runtime_plan=rust native_planner_plan_calls=1([[:space:]]|$)", smoke_script)
        self.assertIn("strategy_requested=c_easu_1080_linear_2160 .*native_planner_runtime_plan=", smoke_script)
        self.assertIn('local expected_effective_strategy="$3"', smoke_script)
        self.assertIn('pattern+="requested_buffer=${expected_requested_buffer} "', smoke_script)
        self.assertIn('pattern+="requested_buffer=(3840×2160|1920×1080) "', smoke_script)
        self.assertIn('"$log_output" "3840×2160" "c_easu_1080_linear_2160" true "3840×2160"', smoke_script)
        self.assertIn('"$log_output" "1920×1080" "a_1080p_system_scaler" false ""', smoke_script)
        self.assertIn("linear_final=false ", smoke_script)
        self.assertIn("native_planner_runtime_plan=rust native_planner_plan_calls=1([[:space:]]|$)", smoke_script)
        renderer_source = (
            repository_root
            / "android/app/src/main/java/org/nightknight/scale/tv/FsrDemoRenderer.kt"
        ).read_text()
        self.assertIn("target=${targetSize} requested_buffer=$requestedSurfaceBufferSize", renderer_source)
        self.assertIn('native_planner_plan_calls=${expected_calls}([[:space:]]|$)', smoke_script)

    def test_uses_android_ndk_root_when_home_is_invalid(self) -> None:
        repository_root = Path(__file__).resolve().parents[1]
        with tempfile.TemporaryDirectory() as temporary:
            temporary_root = Path(temporary)
            ndk_root = temporary_root / "ndk-r27"
            toolchain_bin = ndk_root / "toolchains/llvm/prebuilt/linux-x86_64/bin"
            toolchain_bin.mkdir(parents=True)
            (ndk_root / "source.properties").write_text("Pkg.Revision = 27.2.12479018\n")
            for tool in ("clang", "aarch64-linux-android23-clang"):
                executable = toolchain_bin / tool
                executable.write_text("#!/bin/sh\nexit 0\n")
                executable.chmod(0o755)

            fake_bin = temporary_root / "bin"
            fake_bin.mkdir()
            cargo_log = temporary_root / "cargo.log"
            fake_cargo = fake_bin / "cargo"
            fake_cargo.write_text(
                "#!/bin/sh\n"
                "if [ \"$1\" = \"--version\" ]; then printf 'cargo 1.99.0 (mock)\\n'; exit 0; fi\n"
                "printf 'cross-build-reached\\n' >> \"$FAKE_CARGO_LOG\"\n"
                "exit 42\n"
            )
            fake_cargo.chmod(0o755)
            environment = os.environ.copy()
            environment["ANDROID_NDK_HOME"] = str(temporary_root / "invalid-ndk")
            environment["ANDROID_NDK_ROOT"] = str(ndk_root)
            environment["FAKE_CARGO_LOG"] = str(cargo_log)
            environment["PATH"] = f"{fake_bin}{os.pathsep}{environment['PATH']}"
            environment.pop("ANDROID_SDK_ROOT", None)
            environment.pop("ANDROID_HOME", None)
            result = subprocess.run(
                [
                    "bash",
                    str(repository_root / "android/scripts/build-rust-native.sh"),
                    str(temporary_root / "jniLibs"),
                    "27.2.12479018",
                    "1.99.0",
                ],
                cwd=repository_root,
                env=environment,
                text=True,
                capture_output=True,
                check=False,
            )
            self.assertEqual(result.returncode, 42, result.stderr)
            self.assertEqual(cargo_log.read_text(), "cross-build-reached\n")

    def test_plan_log_waiters_distinguish_exact_events_from_surface_resizes(self) -> None:
        repository_root = Path(__file__).resolve().parents[1]
        smoke_script = (repository_root / "android/scripts/tv-emulator-smoke.sh").read_text()
        self.assertIn("wait_for_plan_record_minimum", smoke_script)
        self.assertIn('if [ "${occurrences:-0}" -eq "$expected" ]; then', smoke_script)
        self.assertIn('if [ "${occurrences:-0}" -gt "$expected" ]; then', smoke_script)
        self.assertIn("assert_c_surface_plan_calls_once", smoke_script)

    def test_smoke_log_helpers_reject_duplicate_events_and_multicall_surface_plans(self) -> None:
        repository_root = Path(__file__).resolve().parents[1]
        smoke_script = (repository_root / "android/scripts/tv-emulator-smoke.sh").read_text()
        helper_start = smoke_script.index("wait_for_exact_plan_record_occurrences() {")
        helper_end = smoke_script.index("\npress_preset() {", helper_start)
        helpers = smoke_script[helper_start:helper_end]
        good_record = (
            "I/NKScaleTV: surface=1920×1080 strategy_requested=c_easu_1080_linear_2160 "
            "native_planner_runtime_plan=rust native_planner_plan_calls=1"
        )

        duplicate_log = "\n".join((good_record, good_record))

        def run_helper(log_output: str, invocation: str) -> subprocess.CompletedProcess[str]:
            harness = f"""adb() {{
    printf '%s\\n' "$MOCK_LOG"
}}
{helpers}
{invocation}
"""
            return subprocess.run(
                ["bash", "-c", harness],
                capture_output=True,
                text=True,
                timeout=5,
                env={**os.environ, "MOCK_LOG": log_output},
                check=False,
            )

        single = run_helper(good_record, "wait_for_exact_plan_record_occurrences 'surface=' 1 exact")
        self.assertEqual(single.returncode, 0, single.stderr)
        duplicates = run_helper(
            duplicate_log,
            "wait_for_exact_plan_record_occurrences 'surface=' 1 exact",
        )
        self.assertEqual(duplicates.returncode, 1)
        self.assertIn("expected exactly 1", duplicates.stderr)
        resized_surfaces = run_helper(
            duplicate_log,
            "wait_for_plan_record_minimum 'surface=' 1 minimum",
        )
        self.assertEqual(resized_surfaces.returncode, 0, resized_surfaces.stderr)
        valid_c_plans = run_helper(
            duplicate_log,
            'assert_c_surface_plan_calls_once "$MOCK_LOG"',
        )
        self.assertEqual(valid_c_plans.returncode, 0, valid_c_plans.stderr)
        invalid_c_plan = run_helper(
            good_record.replace("native_planner_plan_calls=1", "native_planner_plan_calls=2"),
            'assert_c_surface_plan_calls_once "$MOCK_LOG"',
        )
        self.assertEqual(invalid_c_plan.returncode, 1)
        self.assertIn("exactly one Rust planner call", invalid_c_plan.stderr)

        native4k_record = (
            "I/NKScaleTV: surface=3840×2160 source=720×576 target=3840×2160 "
            "requested_buffer=3840×2160 strategy_requested=c_easu_1080_linear_2160 "
            "strategy_effective=c_easu_1080_linear_2160 linear_final=true "
            "native_planner_runtime_plan=rust native_planner_plan_calls=1"
        )
        valid_native4k = run_helper(
            native4k_record,
            'assert_c_surface_plan_matches_output "$MOCK_LOG" "3840×2160" "c_easu_1080_linear_2160" true "3840×2160"',
        )
        self.assertEqual(valid_native4k.returncode, 0, valid_native4k.stderr)
        stale_1080_plan = run_helper(
            native4k_record.replace("surface=3840×2160", "surface=1920×1080"),
            'assert_c_surface_plan_matches_output "$MOCK_LOG" "3840×2160" "c_easu_1080_linear_2160" true "3840×2160"',
        )
        self.assertEqual(stale_1080_plan.returncode, 1)
        self.assertIn("plan record did not match", stale_1080_plan.stderr)
        missing_linear_final = run_helper(
            native4k_record.replace("linear_final=true", "linear_final=false"),
            'assert_c_surface_plan_matches_output "$MOCK_LOG" "3840×2160" "c_easu_1080_linear_2160" true "3840×2160"',
        )
        self.assertEqual(missing_linear_final.returncode, 1)
        fallback_record = (
            "I/NKScaleTV: surface=1920×1080 source=720×576 target=1920×1080 "
            "requested_buffer=3840×2160 strategy_requested=c_easu_1080_linear_2160 "
            "strategy_effective=a_1080p_system_scaler linear_final=false "
            "native_planner_runtime_plan=rust native_planner_plan_calls=1"
        )
        valid_fallback = run_helper(
            fallback_record,
            'assert_c_surface_plan_matches_output "$MOCK_LOG" "1920×1080" "a_1080p_system_scaler" false ""',
        )
        self.assertEqual(valid_fallback.returncode, 0, valid_fallback.stderr)
        unchanged_buffer_fallback = run_helper(
            fallback_record.replace("requested_buffer=3840×2160", "requested_buffer=1920×1080"),
            'assert_c_surface_plan_matches_output "$MOCK_LOG" "1920×1080" "a_1080p_system_scaler" false ""',
        )
        self.assertEqual(unchanged_buffer_fallback.returncode, 0, unchanged_buffer_fallback.stderr)
        invalid_buffer_fallback = run_helper(
            fallback_record.replace("requested_buffer=3840×2160", "requested_buffer=1280×720"),
            'assert_c_surface_plan_matches_output "$MOCK_LOG" "1920×1080" "a_1080p_system_scaler" false ""',
        )
        self.assertEqual(invalid_buffer_fallback.returncode, 1)
        wrong_fallback_stage = run_helper(
            fallback_record.replace("linear_final=false", "linear_final=true"),
            'assert_c_surface_plan_matches_output "$MOCK_LOG" "1920×1080" "a_1080p_system_scaler" false ""',
        )
        self.assertEqual(wrong_fallback_stage.returncode, 1)
        still_c_fallback = run_helper(
            fallback_record.replace("strategy_effective=a_1080p_system_scaler", "strategy_effective=c_easu_1080_linear_2160"),
            'assert_c_surface_plan_matches_output "$MOCK_LOG" "1920×1080" "a_1080p_system_scaler" false ""',
        )
        self.assertEqual(still_c_fallback.returncode, 1)

    def test_native_plan_log_regex_requires_one_record_and_exact_call_count(self) -> None:
        repository_root = Path(__file__).resolve().parents[1]
        smoke_script = (repository_root / "android/scripts/tv-emulator-smoke.sh").read_text()
        match = re.search(
            r"'(surface=[^']*native_planner_runtime_plan=rust[^']*native_planner_plan_calls=1[^']*)'",
            smoke_script,
        )
        if match is None:
            self.fail("surface plan record regex was not found in the smoke script")
        pattern = match.group(1)
        fixtures = [
            ("I/NKScaleTV: surface=1920×1080 native_planner_runtime_plan=rust native_planner_plan_calls=1 native_planner_calls=9", True),
            ("I/NKScaleTV: surface=1920×1080 native_planner_runtime_plan=rust native_planner_plan_calls=10 native_planner_calls=10", False),
            ("I/NKScaleTV: surface=1920×1080 native_planner_runtime_plan=rust\nI/NKScaleTV: native_planner_plan_calls=1", False),
        ]
        for line, expected_match in fixtures:
            result = subprocess.run(["grep", "-E", "-q", pattern], input=line, text=True, check=False)
            self.assertEqual(result.returncode == 0, expected_match, line)

    def test_unchanged_geometry_skips_replanning_before_jni(self) -> None:
        repository_root = Path(__file__).resolve().parents[1]
        renderer_source = (
            repository_root
            / "android/app/src/main/java/org/nightknight/scale/tv/FsrDemoRenderer.kt"
        ).read_text()
        start = renderer_source.index("fun updateDecodedVideoGeometry(")
        end = renderer_source.index("\n    private fun recordFrameInterval", start)
        handler = renderer_source[start:end]
        guard = "if (requestKey == appliedPlanRequestKey) return"
        self.assertIn(guard, handler)
        self.assertLess(handler.index(guard), handler.index("createScalingPlan("))

    def test_surface_mode_adoption_resets_fallback_state_before_replanning(self) -> None:
        repository_root = Path(__file__).resolve().parents[1]
        renderer_source = (
            repository_root
            / "android/app/src/main/java/org/nightknight/scale/tv/FsrDemoRenderer.kt"
        ).read_text()
        surface_start = renderer_source.index("override fun onSurfaceChanged(")
        surface_end = renderer_source.index("\n    /** Update aspect ratio override", surface_start)
        surface_callback = renderer_source[surface_start:surface_end]
        transition_guard = "if (mode != previousMode) resetModeTransitionState()"
        self.assertIn(transition_guard, surface_callback)
        self.assertLess(surface_callback.index(transition_guard), surface_callback.index("createScalingPlan("))
        apply_start = renderer_source.index("private fun applyRequestedMode()")
        apply_end = renderer_source.index("\n    private fun pollGpuTimerQuery", apply_start)
        self.assertIn("resetModeTransitionState()", renderer_source[apply_start:apply_end])

    def test_surface_mode_adoption_records_mode_after_resource_rebuild(self) -> None:
        repository_root = Path(__file__).resolve().parents[1]
        renderer_source = (
            repository_root
            / "android/app/src/main/java/org/nightknight/scale/tv/FsrDemoRenderer.kt"
        ).read_text()
        surface_start = renderer_source.index("override fun onSurfaceChanged(")
        surface_end = renderer_source.index("\n    /** Update aspect ratio override", surface_start)
        surface_callback = renderer_source[surface_start:surface_end]
        self.assertLess(surface_callback.index("createScalingPlan("), surface_callback.index("runCatching { createOrReplaceTargets() }"))
        target_start = renderer_source.index("private fun createOrReplaceTargets()")
        fallback_start = renderer_source.index("private fun fallbackAfterTargetAllocationFailure", target_start)
        target_allocator = renderer_source[target_start:fallback_start]
        self.assertIn("lastModeOnGlThread = mode", target_allocator)
        fallback_end = renderer_source.index("private fun createScalingPlan(", fallback_start)
        allocation_fallback = renderer_source[fallback_start:fallback_end]
        self.assertIn("lastModeOnGlThread = mode", allocation_fallback)

    def test_native_planner_evidence_is_saved_before_diagnostics_clears_logcat(self) -> None:
        repository_root = Path(__file__).resolve().parents[1]
        smoke_script = (repository_root / "android/scripts/tv-emulator-smoke.sh").read_text()
        snapshot = 'initial_planner_log="$(adb logcat -d -s NKScaleTV:V AndroidRuntime:E)"'
        self.assertIn(snapshot, smoke_script)
        self.assertLess(smoke_script.index(snapshot), smoke_script.rindex("assert_diagnostics_reachable_at_1080p"))
        self.assertIn('printf \'%s\\n\' "$initial_planner_log" > "$output_dir/native-planner-initial.logcat.txt"', smoke_script)
        self.assertIn('"$output_dir/native-planner-initial.logcat.txt"', smoke_script)

    def test_exit_logcat_capture_does_not_mutate_checksum_target(self) -> None:
        repository_root = Path(__file__).resolve().parents[1]
        smoke_script = (repository_root / "android/scripts/tv-emulator-smoke.sh").read_text()
        trap_line = next(line for line in smoke_script.splitlines() if line.startswith("trap "))
        self.assertIn("logcat-on-exit.txt", trap_line)
        self.assertNotIn('> "$output_dir/logcat.txt"', trap_line)
        final_snapshot = 'adb logcat -d -s NKScaleTV:V AndroidRuntime:E > "$output_dir/logcat.txt"'
        checksum_start = smoke_script.rindex("sha256sum")
        self.assertLess(smoke_script.rindex(final_snapshot), checksum_start)
        self.assertIn('"$output_dir/logcat.txt"', smoke_script[checksum_start:])

    def test_renderer_mode_changes_are_queued_outside_draw_callback(self) -> None:
        repository_root = Path(__file__).resolve().parents[1]
        renderer_source = (
            repository_root
            / "android/app/src/main/java/org/nightknight/scale/tv/FsrDemoRenderer.kt"
        ).read_text()
        activity_source = (
            repository_root
            / "android/app/src/main/java/org/nightknight/scale/tv/MainActivity.kt"
        ).read_text()
        draw_start = renderer_source.index("override fun onDrawFrame(")
        draw_end = renderer_source.index("\n    private fun ", draw_start)
        draw_callback = renderer_source[draw_start:draw_end]
        preset_start = activity_source.index("private fun setPreset(")
        preset_end = activity_source.index("\n    private fun ", preset_start)
        preset_handler = activity_source[preset_start:preset_end]
        self.assertNotIn("applyRequestedMode()", draw_callback)
        self.assertIn("private var mode = RenderMode.FSR1", renderer_source)
        self.assertIn("var requestedMode = RenderMode.FSR1", renderer_source)
        self.assertIn("fun applyRequestedModeOnGlThread()", renderer_source)
        self.assertIn("CoalescedGlEventDispatcher", activity_source)
        self.assertIn("renderer.requestedMode = preset.renderMode", preset_handler)
        self.assertNotIn("renderer.mode = preset.renderMode", preset_handler)
        self.assertIn("renderModeUpdateDispatcher.request", preset_handler)
        self.assertIn("plan.easuStages.isEmpty() -> \"not-required\"", renderer_source)

    def test_rejects_non_elf_native_library(self) -> None:
        entries = {
            f"lib/{abi}/libnightknight_render_core.so": b"not an ELF"
            for abi in EXPECTED_ABIS
        }
        with self.assertRaisesRegex(ApkValidationError, "ELF"):
            verify_rust_apk(self.make_apk(entries))


if __name__ == "__main__":
    unittest.main()
