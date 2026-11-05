#!/usr/bin/env bash
set -euo pipefail

apk_path="${1:?usage: tv-emulator-smoke.sh APK_PATH OUTPUT_DIR}"
output_dir="${2:?usage: tv-emulator-smoke.sh APK_PATH OUTPUT_DIR}"
package_name="org.nightknight.scale.tv"
activity_name="${package_name}/.MainActivity"

mkdir -p "$output_dir"
target_device="${ANDROID_SERIAL:-}"
if [[ ! "$target_device" =~ ^emulator-[0-9]+$ ]]; then
    echo "Refusing to issue ADB commands without ANDROID_SERIAL set to an emulator-* serial: ${target_device:-unset}" >&2
    exit 2
fi
export ANDROID_SERIAL="$target_device"
trap 'adb logcat -d -s NKScaleTV:V AndroidRuntime:E > "$output_dir/logcat-on-exit.txt" 2>/dev/null || true' EXIT
adb wait-for-device
if [[ "$(adb get-serialno)" != "$target_device" ]]; then
    echo "ADB did not select the requested emulator serial: $target_device" >&2
    exit 2
fi

for attempt in $(seq 1 90); do
    if [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = "1" ]; then
        break
    fi
    if [ "$attempt" -eq 90 ]; then
        echo "Android TV emulator did not finish booting" >&2
        exit 1
    fi
    sleep 1
done

adb install -r "$apk_path"
adb shell pm clear "$package_name" >/dev/null
# Keep a 1080p pixel surface while using a dense UI scale that forces the control panel to scroll.
adb shell wm size 1920x1080 >/dev/null
adb shell wm density 400 >/dev/null
adb logcat -c
adb shell am force-stop "$package_name"
adb shell am start -W -n "$activity_name" --ez nkscale_validate_native_planner true

wait_for_log_marker() {
    marker="$1"
    description="$2"
    attempt_limit="${3:-90}"
    for attempt in $(seq 1 "$attempt_limit"); do
        local log_output
        log_output="$(adb logcat -d -s NKScaleTV:I)"
        if grep -Fq "$marker" <<< "$log_output"; then
            return 0
        fi
        sleep 1
    done
    echo "Timed out waiting for emulator runtime marker: $description ($marker)" >&2
    adb logcat -d -s NKScaleTV:V AndroidRuntime:E >&2
    exit 1
}

wait_for_log_occurrences() {
    marker="$1"
    expected="$2"
    description="$3"
    for attempt in $(seq 1 90); do
        occurrences="$(adb logcat -d -s NKScaleTV:I | grep -Fc "$marker" || true)"
        if [ "${occurrences:-0}" -ge "$expected" ]; then
            return 0
        fi
        sleep 1
    done
    echo "Timed out waiting for $expected occurrences of $marker: $description" >&2
    adb logcat -d -s NKScaleTV:V AndroidRuntime:E >&2
    exit 1
}

wait_for_exact_plan_record_occurrences() {
    local pattern="$1"
    local expected="$2"
    local description="$3"
    for attempt in $(seq 1 90); do
        local log_output
        log_output="$(adb logcat -d -s NKScaleTV:I)"
        local occurrences
        occurrences="$(grep -Ec "$pattern" <<< "$log_output" || true)"
        if [ "${occurrences:-0}" -eq "$expected" ]; then
            return 0
        fi
        if [ "${occurrences:-0}" -gt "$expected" ]; then
            echo "Observed $occurrences plan records; expected exactly $expected: $description ($pattern)" >&2
            adb logcat -d -s NKScaleTV:V AndroidRuntime:E >&2
            exit 1
        fi
        sleep 1
    done
    echo "Timed out waiting for exactly $expected plan records: $description ($pattern)" >&2
    adb logcat -d -s NKScaleTV:V AndroidRuntime:E >&2
    exit 1
}

wait_for_plan_record_minimum() {
    local pattern="$1"
    local minimum="$2"
    local description="$3"
    for attempt in $(seq 1 90); do
        local log_output
        log_output="$(adb logcat -d -s NKScaleTV:I)"
        local occurrences
        occurrences="$(grep -Ec "$pattern" <<< "$log_output" || true)"
        if [ "${occurrences:-0}" -ge "$minimum" ]; then
            return 0
        fi
        sleep 1
    done
    echo "Timed out waiting for at least $minimum plan records: $description ($pattern)" >&2
    adb logcat -d -s NKScaleTV:V AndroidRuntime:E >&2
    exit 1
}

assert_surface_plan_calls_once() {
    local log_output="$1"
    local record_pattern="$2"
    local description="$3"
    local records invalid_records
    records="$(grep -E "$record_pattern" <<< "$log_output" || true)"
    if [[ -z "$records" ]]; then
        echo "No surface plan record matched: $description" >&2
        printf '%s\n' "$log_output" >&2
        exit 1
    fi
    invalid_records="$(grep -Ev 'native_planner_runtime_plan=rust native_planner_plan_calls=1([[:space:]]|$)' <<< "$records" || true)"
    if [[ -n "$invalid_records" ]]; then
        echo "Every matching surface plan must use exactly one Rust planner call: $description" >&2
        printf '%s\n' "$invalid_records" >&2
        exit 1
    fi
}

assert_c_surface_plan_calls_once() {
    assert_surface_plan_calls_once \
        "$1" \
        'surface=.*strategy_requested=c_easu_1080_linear_2160 .*native_planner_runtime_plan=' \
        'requested native-4K C strategy'
}

assert_c_surface_plan_matches_output() {
    local log_output="$1"
    local expected_size="$2"
    local expected_effective_strategy="$3"
    local require_linear_final="$4"
    local expected_requested_buffer="$5"
    local pattern="surface=${expected_size} .*target=[0-9]+×[0-9]+ "
    if [[ -n "$expected_requested_buffer" ]]; then
        pattern+="requested_buffer=${expected_requested_buffer} "
    else
        pattern+="requested_buffer=(3840×2160|1920×1080) "
    fi
    pattern+="strategy_requested=c_easu_1080_linear_2160 strategy_effective=${expected_effective_strategy} "
    case "$require_linear_final" in
        true) pattern+=".*linear_final=true " ;;
        false) pattern+=".*linear_final=false " ;;
        *) echo "Invalid linear-final expectation: $require_linear_final" >&2; exit 2 ;;
    esac
    pattern+="native_planner_runtime_plan=rust native_planner_plan_calls=1([[:space:]]|$)"
    if ! grep -E "$pattern" <<< "$log_output" | python3 -c '
import re, sys
width, height = map(int, sys.argv[1].split("×"))
for line in sys.stdin:
    match = re.search(r"(?:^|\s)target=([0-9]+)×([0-9]+)(?:\s|$)", line)
    if match and 0 < int(match[1]) <= width and 0 < int(match[2]) <= height:
        sys.exit(0)
sys.exit(1)
' "$expected_size"; then
        echo "C plan record did not match the effective output buffer $expected_size" >&2
        printf '%s\n' "$log_output" >&2
        exit 1
    fi
}


validate_native4k_snapshot() {
    python3 - "$1" <<'PYVALIDATOR'
import re
import sys

lines = sys.argv[1].splitlines()
state = None
last_transition = -1
last_profile = -1
fallback = None
fallback_pending_request = False
plans = []
surface_plans = []

def fail(message):
    print(message, file=sys.stderr)
    sys.exit(1)

def field(line, name):
    values = re.findall(r"(?:^|\s)" + re.escape(name) + r"=([^\s]*)", line)
    if len(values) != 1 or not values[0]:
        fail("missing, blank or duplicated " + name + ": " + line)
    return values[0]

def size(value):
    if not re.fullmatch(r"[0-9]{1,10}×[0-9]{1,10}", value):
        fail("invalid dimensions: " + value)
    pair = tuple(map(int, value.split("×")))
    if any(n < 1 or n > 2147483647 for n in pair):
        fail("out-of-range dimensions: " + value)
    return pair

def bounded(target, surface):
    t, s = size(target), size(surface)
    if any(a > b for a, b in zip(t, s)):
        fail("target exceeds surface")

for index, line in enumerate(lines):
    if re.search(r"\boutput_resolution_(?:request|verified|auto_fallback|fallback|unverified)\b", line):
        last_transition = index
        if "output_resolution_request " in line:
            plans.clear()
            surface_plans.clear()
            requested_buffer = field(line, "requested_buffer")
            if requested_buffer != "1920×1080" or not fallback_pending_request:
                fallback = None
            fallback_pending_request = False
        elif re.search(r"\boutput_resolution_(?:auto_fallback|fallback)\b", line):
            if field(line, "requested") != "native_4k":
                fail("fallback belongs to another output request")
            fallback = field(line, "reason")
            fallback_pending_request = True
        else:
            fallback_pending_request = False
        if "output_resolution_verified " in line:
            state = (index, line)
        else:
            state = None
        continue
    if "native4k_auto_profile=validated" in line:
        last_profile = index
    # Ignore telemetry; these are the renderer's plan-bearing records.
    candidate = ("decoded_size=" in line or "requested_buffer=" in line or
                 "strategy_requested=c_easu_1080_linear_2160" in line and "surface=" in line or
                 "surface=" in line and "source=" in line or
                 "target=" in line and ("surface=" in line or "source=" in line))
    if not candidate:
        continue
    surface = field(line, "surface")
    target = field(line, "target")
    bounded(target, surface)
    if field(line, "native_planner_runtime_plan") != "rust" or field(line, "native_planner_plan_calls") != "1":
        fail("plan must have exactly one Rust planner call")
    plans.append((index, surface, target))
    if "decoded_size=" not in line:
        if field(line, "strategy_requested") != "c_easu_1080_linear_2160":
            fail("unexpected requested strategy")
        requested = field(line, "requested_buffer")
        if requested not in ("3840×2160", "1920×1080"):
            fail("unexpected requested buffer")
        effective = field(line, "strategy_effective")
        linear = field(line, "linear_final")
        if (surface, effective, linear) not in (
            ("3840×2160", "c_easu_1080_linear_2160", "true"),
            ("1920×1080", "a_1080p_system_scaler", "false"),
        ):
            fail("inconsistent C strategy surface plan")
        surface_plans.append((index, surface, target, effective, requested))

if state is None or not plans or not surface_plans:
    fail("no current verified state and matching plans")
_, line = state
if field(line, "requested") != "native_4k":
    fail("wrong output request")
effective = field(line, "effective")
surface = field(line, "surface")
egl = field(line, "egl")
target = field(line, "target")
bounded(target, surface)
if (effective, surface, egl) not in (("native_4k", "3840×2160", "3840×2160"), ("1080p", "1920×1080", "1920×1080")):
    fail("unverified output dimensions")
if plans[-1][0] <= state[0] or plans[-1][1:] != (surface, target) or surface_plans[-1][1] != surface:
    fail("latest plan must follow and match the verified output")
if effective == "native_4k":
    if last_profile <= last_transition or surface_plans[-1][4] != "3840×2160":
        fail("native-4K profile is not current")
elif not fallback:
    fail("fallback has no reason")
print(effective + " " + target)
PYVALIDATOR
}

press_preset() {
    local preset_id="$1"
    local description="$2"
    local sample_mode="${3:-}"
    local marker="preset=${preset_id} "
    local plan_mode expected_plan expected_calls plan_pattern current_plan_count expected_plan_count
    local current_count expected_count sample_marker sample_count expected_sample_count
    case "$preset_id" in
        quality) plan_mode="fsr1_denoise"; expected_plan="rust"; expected_calls=1 ;;
        balanced) plan_mode="fsr1"; expected_plan="rust"; expected_calls=1 ;;
        performance) plan_mode="easu_only"; expected_plan="rust"; expected_calls=1 ;;
        off) plan_mode="bilinear"; expected_plan="not-required"; expected_calls=0 ;;
        *) echo "Unknown preset id for plan validation: $preset_id" >&2; exit 1 ;;
    esac
    plan_pattern="render_resources mode=${plan_mode} .*native_planner_runtime_plan=${expected_plan} native_planner_plan_calls=${expected_calls}([[:space:]]|$)"
    current_plan_count="$(adb logcat -d -s NKScaleTV:I | grep -Ec "$plan_pattern" || true)"
    expected_plan_count=$(( ${current_plan_count:-0} + 1 ))
    current_count="$(adb logcat -d -s NKScaleTV:I | grep -Fc "$marker" || true)"
    expected_count=$(( ${current_count:-0} + 1 ))
    if [[ -n "$sample_mode" ]]; then
        sample_marker="performance_sample requested=${sample_mode} effective=${sample_mode} "
        sample_count="$(adb logcat -d -s NKScaleTV:I | grep -Fc "$sample_marker" || true)"
        expected_sample_count=$(( ${sample_count:-0} + 1 ))
    fi
    adb shell input keyevent KEYCODE_DPAD_CENTER
    wait_for_log_occurrences "$marker" "$expected_count" "$description"
    wait_for_exact_plan_record_occurrences "$plan_pattern" "$expected_plan_count" "${description} plan selection"
    if [[ -n "$sample_mode" ]]; then
        wait_for_log_occurrences "$sample_marker" "$expected_sample_count" "settled ${sample_mode} CPU/renderer sample"
    fi
}

wait_for_first_performance_sample() {
    local requested_mode="$1"
    local marker="performance_sample requested=${requested_mode} effective=${requested_mode} "
    wait_for_log_occurrences "$marker" 1 "first active ${requested_mode} CPU/renderer sample"
}

wait_for_easu_validation_result() {
    for attempt in $(seq 1 90); do
        local log_output
        log_output="$(adb logcat -d -s NKScaleTV:I)"
        if grep -Fq 'easu_equivalence=pass ' <<< "$log_output"; then
            if grep -Fq 'easu_external=validated;' <<< "$log_output"; then
                echo "Direct OES EASU passed pixel equivalence and was enabled for this debug session."
                return 0
            fi
        elif grep -Fq 'easu_equivalence=fail ' <<< "$log_output"; then
            if grep -Fq 'easu_external=disabled fallback=rgba-copy after output comparison' <<< "$log_output"; then
                echo "Direct OES EASU differed; verified the explicit RGBA-copy fallback."
                return 0
            fi
        elif grep -Fq 'easu_external=unavailable' <<< "$log_output" && \
            grep -Fq 'video_frame=consumed' <<< "$log_output"; then
            echo "Direct OES EASU shader unavailable; verified video-frame consumption for fallback."
            return 0
        fi
        sleep 1
    done
    echo "Timed out waiting for EASU equivalence result or explicit fallback." >&2
    adb logcat -d -s NKScaleTV:V AndroidRuntime:E >&2
    return 1
}

assert_native4k_linear_final_strategy() {
    echo "Native-4K strategy smoke: requesting the 576p anamorphic C pipeline on the emulator"
    adb shell am force-stop "$package_name"
    adb logcat -c
    adb shell am start -W -n "$activity_name" \
        --es nkscale_output_resolution native_4k \
        --es nkscale_strategy c_easu_1080_linear_2160 \
        --es nkscale_test_source 720x576-16x9-25
    wait_for_log_marker 'output_resolution_request requested=native_4k requested_buffer=3840×2160' 'native-4K buffer request' 30
    wait_for_log_marker 'video=playing source=test-videos/scale-test-720x576-16x9-25fps.mp4' '576p 16:9 anamorphic benchmark clip' 45
    wait_for_log_marker 'video_frame=consumed' 'decoded 576p benchmark frame' 45
    # Logcat was cleared above; wait for a C-strategy surface record, then validate every such record below.
    wait_for_plan_record_minimum \
        'surface=.*strategy_requested=c_easu_1080_linear_2160' \
        1 'native-4K C surface plan'

    wait_for_log_marker 'performance_sample requested=fsr1 ' 'native-4K strategy performance window' 90
    local log_output decision=""
    for attempt in $(seq 1 90); do
        log_output="$(adb logcat -d -s NKScaleTV:V)"
        if decision="$(validate_native4k_snapshot "$log_output" 2>/dev/null)"; then
            break
        fi
        sleep 1
    done
    # Decide again from the immutable artifact snapshot, not an earlier polling result.
    log_output="$(adb logcat -d -s NKScaleTV:V)"
    if ! decision="$(validate_native4k_snapshot "$log_output")"; then
        echo "Native-4K output did not settle on a verified, matching plan" >&2
        printf '%s\n' "$log_output" >&2
        exit 1
    fi
    local effective target
    read -r effective target <<< "$decision"
    if [[ "$effective" == native_4k ]]; then
        echo "Emulator-only: verified 3840×2160 surface/EGL with processing target $target and native-4K profile; not physical GPU or panel evidence."
    else
        echo "Emulator-only: native-4K request fell back to verified 1920×1080 surface/EGL with processing target $target; no native-4K success is claimed."
    fi
    adb exec-out screencap -p > "$output_dir/native4k-strategy-c.png"
    printf '%s\n' "$log_output" > "$output_dir/native4k-strategy-c.logcat.txt"
    adb shell am force-stop "$package_name"
    adb logcat -c
    adb shell am start -W -n "$activity_name" --es nkscale_output_resolution 1080p --es nkscale_strategy auto
    wait_for_log_marker 'video=playing source=' 'restart after native-4K probe' 45
    wait_for_log_marker 'output_resolution_verified requested=1080p effective=1080p surface=1920×1080 egl=1920×1080' 'verified EGL surface after process/surface recreation' 45
}

assert_diagnostics_reachable_at_1080p() {
    echo "1080p diagnostics test: waiting for startup at density 400"
    wait_for_log_marker 'shaders=ready' 'GLES initialization at 1080p dense UI scale'
    wait_for_log_marker 'video_frame=consumed' 'video frame at 1080p dense UI scale'
    wait_for_log_marker 'surface=1920×1080' '1920x1080 GLES surface'
    wait_for_log_marker 'output_resolution_verified requested=auto effective=1080p surface=1920×1080 egl=1920×1080' 'verified AUTO 1080p callback and EGL buffer'

    # Focus chain: selected preset -> picker -> playback -> sharpness -> output resolution -> strategy -> Diagnostics.
    for _ in 1 2 3 4 5 6; do adb shell input keyevent KEYCODE_DPAD_DOWN; done
    wait_for_log_marker 'diagnostics=focused visible=true' 'visible Diagnostics focus after D-pad navigation' 15
    adb shell input keyevent KEYCODE_DPAD_CENTER
    wait_for_log_marker 'diagnostics=open' 'Diagnostics dialog opening at 1080p' 15
    adb shell input keyevent KEYCODE_BACK

    # Dismiss returns focus to Diagnostics; UP reaches Strategy and CENTER selects A.
    adb shell input keyevent KEYCODE_DPAD_UP
    adb shell input keyevent KEYCODE_DPAD_CENTER
    wait_for_log_marker 'scaling_strategy_selected=a_1080p_system_scaler' 'D-pad strategy selector activation' 15
    wait_for_log_marker 'scaling_strategy requested=a_1080p_system_scaler effective=a_1080p_system_scaler' 'renderer reconfiguration for strategy A' 15
    wait_for_exact_plan_record_occurrences \
        'scaling_strategy requested=a_1080p_system_scaler .*native_planner_runtime_plan=rust native_planner_plan_calls=1([[:space:]]|$)' \
        1 'strategy A plan uses one batched Rust JNI call'

    # Restore the AVD's default density before pixel-comparison playback tests.
    adb shell am force-stop "$package_name"
    adb shell pm clear "$package_name" >/dev/null
    adb shell wm density reset >/dev/null
    adb logcat -c
    adb shell am start -W -n "$activity_name" --ez nkscale_validate_easu_input true --es nkscale_output_resolution 1080p --es nkscale_strategy auto
    echo "1080p diagnostics test: passed; restored default density"
}

wait_for_log_marker 'native_planner_validation=pass' 'Rust planner JNI output matches the Kotlin reference corpus'
wait_for_plan_record_minimum \
    'surface=.*native_planner_runtime_plan=rust native_planner_plan_calls=1([[:space:]]|$)' \
    1 'live surface plan uses one batched Rust JNI call'
initial_planner_log="$(adb logcat -d -s NKScaleTV:V AndroidRuntime:E)"
assert_surface_plan_calls_once "$initial_planner_log" 'surface=.*native_planner_runtime_plan=' 'initial surface plan'
printf '%s\n' "$initial_planner_log" > "$output_dir/native-planner-initial.logcat.txt"
assert_diagnostics_reachable_at_1080p

wait_for_log_marker 'shaders=ready' 'GLES shader initialization'
wait_for_log_marker 'video=playing' 'MediaPlayer prepare/start'
wait_for_log_marker 'video_frame=consumed' 'decoded SurfaceTexture frame update'
wait_for_log_marker 'video_frame=consumed path=rgba-copy' 'unvalidated direct EASU remains on RGBA reference input'
wait_for_easu_validation_result
wait_for_log_marker 'render_fps=' 'renderer callback FPS'
wait_for_log_marker 'gpu_timer_query=' 'GPU timer-query capability detection'
wait_for_first_performance_sample 'fsr1'

# Two moving decoded captures prove the packaged video is updating the GLES input.
adb exec-out screencap -p > "$output_dir/video-motion-a.png"
sleep 1
adb exec-out screencap -p > "$output_dir/video-motion-b.png"

# Initial focus is BALANCED. DOWN reaches the local-video picker, second DOWN the pause control; two captures after resume prove playback advances again.
adb shell input keyevent KEYCODE_DPAD_DOWN
adb shell input keyevent KEYCODE_DPAD_DOWN
adb shell input keyevent KEYCODE_DPAD_CENTER
wait_for_log_marker 'video=paused' 'remote pause control'
wait_for_log_marker 'frame_pacing_reset reason=playback_paused' 'pause resets renderer pacing/hysteresis'
adb shell input keyevent KEYCODE_DPAD_CENTER
wait_for_log_occurrences 'video=playing source=' 2 'remote resume control'
wait_for_log_marker 'frame_pacing_reset reason=playback_resumed' 'resume resets renderer pacing/hysteresis'
sleep 1
adb exec-out screencap -p > "$output_dir/video-resumed-a.png"
sleep 1
adb exec-out screencap -p > "$output_dir/video-resumed-b.png"

# Benchmark each requested preset while the decoder is actively supplying frames.
# Renderer callback cadence is recorded only as renderer cadence, never display FPS.
adb shell input keyevent KEYCODE_DPAD_UP
adb shell input keyevent KEYCODE_DPAD_UP
adb shell input keyevent KEYCODE_DPAD_LEFT
press_preset 'quality' 'active-video QUALITY preset' 'fsr1_denoise'
adb shell input keyevent KEYCODE_DPAD_RIGHT
press_preset 'balanced' 'active-video BALANCED preset' 'fsr1'
adb shell input keyevent KEYCODE_DPAD_RIGHT
press_preset 'performance' 'active-video PERFORMANCE preset' 'easu_only'
adb shell input keyevent KEYCODE_DPAD_LEFT
press_preset 'balanced' 'active-video BALANCED before OFF'
adb shell input keyevent KEYCODE_DPAD_LEFT
press_preset 'quality' 'active-video revisit QUALITY'
adb shell input keyevent KEYCODE_DPAD_LEFT
press_preset 'off' 'active-video OFF preset' 'bilinear'
adb shell input keyevent KEYCODE_DPAD_RIGHT
press_preset 'quality' 'active-video return through QUALITY'
adb shell input keyevent KEYCODE_DPAD_RIGHT
press_preset 'balanced' 'active-video return to BALANCED'

# Freeze a stable frame for image-regression captures and the aspect-ratio control test.
adb shell input keyevent KEYCODE_DPAD_DOWN
adb shell input keyevent KEYCODE_DPAD_DOWN
adb shell input keyevent KEYCODE_DPAD_CENTER
wait_for_log_occurrences 'video=paused' 2 'pause after active-video preset benchmarks'
# Cycle every explicit aspect override from the focused Play/Pause row, then return to the preset row.
adb shell input keyevent KEYCODE_DPAD_RIGHT
adb shell input keyevent KEYCODE_DPAD_CENTER
wait_for_log_marker 'aspect_mode=16_9' 'Force 16:9 aspect override'
adb shell input keyevent KEYCODE_DPAD_CENTER
wait_for_log_marker 'aspect_mode=4_3' 'Force 4:3 aspect override'
adb shell input keyevent KEYCODE_DPAD_CENTER
wait_for_log_marker 'aspect_mode=1_1' 'square-pixel aspect override'
adb shell input keyevent KEYCODE_DPAD_CENTER
wait_for_log_marker 'aspect_mode=auto' 'restore automatic aspect mode'
adb shell input keyevent KEYCODE_DPAD_LEFT
# Return focus from the pause control through the local-video button to the selected preset before comparing filter modes.
adb shell input keyevent KEYCODE_DPAD_UP
adb shell input keyevent KEYCODE_DPAD_UP
sleep 1
adb exec-out screencap -p > "$output_dir/fsr1-paused.png"

# The smoke starts with BALANCED selected. Exercise QUALITY, BALANCED, PERFORMANCE and OFF using the TV remote.
adb exec-out screencap -p > "$output_dir/fsr1-paused.png"
adb shell input keyevent KEYCODE_DPAD_LEFT
press_preset 'quality' 'paused QUALITY screenshot mode'
sleep 1
adb exec-out screencap -p > "$output_dir/quality-paused.png"
adb shell input keyevent KEYCODE_DPAD_RIGHT
press_preset 'balanced' 'return to BALANCED for paused screenshot'
adb shell input keyevent KEYCODE_DPAD_RIGHT
press_preset 'performance' 'PERFORMANCE (EASU only)'
sleep 1
adb exec-out screencap -p > "$output_dir/performance-paused.png"
adb shell input keyevent KEYCODE_DPAD_LEFT
press_preset 'balanced' 'return to BALANCED before OFF screenshot'
adb shell input keyevent KEYCODE_DPAD_LEFT
press_preset 'quality' 'revisit QUALITY for paused comparison'
adb shell input keyevent KEYCODE_DPAD_LEFT
press_preset 'off' 'OFF preset for paused screenshot'
sleep 1
adb exec-out screencap -p > "$output_dir/off-paused.png"

# Return to BALANCED, set sharpness to 9, then restart and assert both settings reload.
adb shell input keyevent KEYCODE_DPAD_RIGHT
press_preset 'quality' 'return through QUALITY for final BALANCED'
adb shell input keyevent KEYCODE_DPAD_RIGHT
press_preset 'balanced' 'restore BALANCED for RCAS setting test'
adb shell input keyevent KEYCODE_DPAD_DOWN
adb shell input keyevent KEYCODE_DPAD_DOWN
adb shell input keyevent KEYCODE_DPAD_DOWN
adb shell input keyevent KEYCODE_DPAD_RIGHT
sleep 1
adb exec-out screencap -p > "$output_dir/fsr1-sharpness9.png"
adb shell input keyevent KEYCODE_DPAD_DOWN
adb shell input keyevent KEYCODE_DPAD_DOWN
adb shell input keyevent KEYCODE_DPAD_DOWN
adb shell input keyevent KEYCODE_DPAD_CENTER
sleep 1
adb exec-out screencap -p > "$output_dir/diagnostics-dialog.png"
adb shell uiautomator dump /sdcard/nkscale-diagnostics.xml >/dev/null 2>&1
adb pull /sdcard/nkscale-diagnostics.xml "$output_dir/diagnostics-dialog.xml" >/dev/null 2>&1
if ! grep -Fq 'NightKnight · Diagnostics' "$output_dir/diagnostics-dialog.xml" || \
   ! grep -Fq 'Output resolution requested' "$output_dir/diagnostics-dialog.xml" || \
   ! grep -Fq 'EGL window-surface query' "$output_dir/diagnostics-dialog.xml" || \
   ! grep -Fq 'Benchmark pipeline' "$output_dir/diagnostics-dialog.xml" || \
   ! grep -Fq 'EASU pass count' "$output_dir/diagnostics-dialog.xml" || \
   ! grep -Fq 'Planned app-owned RGBA targets' "$output_dir/diagnostics-dialog.xml" || \
   ! grep -Fq 'GPU execution time' "$output_dir/diagnostics-dialog.xml" || \
   ! grep -Fq 'Display presentation timing' "$output_dir/diagnostics-dialog.xml" || \
   ! grep -Fq 'SurfaceTexture callback cadence' "$output_dir/diagnostics-dialog.xml" || \
   ! grep -Fq 'CPU submit windows avg/max' "$output_dir/diagnostics-dialog.xml" || \
   ! grep -Fq 'CPU pass submit avg/max' "$output_dir/diagnostics-dialog.xml" || \
   ! grep -Fq 'Video input route' "$output_dir/diagnostics-dialog.xml"; then
    echo "D-pad diagnostics dialog did not expose its title and renderer metrics" >&2
    cat "$output_dir/diagnostics-dialog.xml" >&2
    exit 1
fi
adb shell input keyevent KEYCODE_DPAD_CENTER
adb shell am force-stop "$package_name"
adb shell am start -W -n "$activity_name"
wait_for_log_marker 'settings=loaded preset=balanced sharpness=9' 'persisted preset and sharpness after process restart'
wait_for_log_marker 'video=playing source=' 'packaged video resume after restart'

# Save the settings/preset navigation log before local-file scenarios replace the current logcat buffer.
adb logcat -d -s NKScaleTV:V AndroidRuntime:E > "$output_dir/preset-and-settings-logcat.txt"
grep -F 'performance_sample requested=' "$output_dir/preset-and-settings-logcat.txt" > "$output_dir/performance-samples.logcat.txt" || true
: > "$output_dir/performance-samples-active.logcat.txt"
for benchmark_mode in fsr1_denoise fsr1 easu_only bilinear; do
    if ! grep -Fq "performance_sample requested=${benchmark_mode} " "$output_dir/performance-samples.logcat.txt"; then
        echo "Missing settled performance sample for requested mode $benchmark_mode" >&2
        cat "$output_dir/performance-samples.logcat.txt" >&2
        exit 1
    fi
    active_samples="$(grep -F "performance_sample requested=${benchmark_mode} effective=${benchmark_mode} " "$output_dir/performance-samples.logcat.txt" | grep -Fv 'cpu_input_update_avg_ms=N/A' || true)"
    if [ -z "$active_samples" ]; then
        echo "Missing active-decoder performance sample for requested mode $benchmark_mode" >&2
        cat "$output_dir/performance-samples.logcat.txt" >&2
        exit 1
    fi
    printf '%s\n' "$active_samples" >> "$output_dir/performance-samples-active.logcat.txt"
done
quality_stage_samples="$(grep -F 'performance_sample requested=fsr1_denoise effective=fsr1_denoise ' "$output_dir/performance-samples-active.logcat.txt" | grep -Fv 'cpu_denoise_submit_avg_ms=N/A' | grep -Fv 'cpu_easu_submit_avg_ms=N/A' | grep -Fv 'cpu_rcas_submit_avg_ms=N/A' || true)"
if [ -z "$quality_stage_samples" ]; then
    echo "QUALITY was requested/effective, but no active NR+EASU+RCAS CPU stage timings were recorded" >&2
    cat "$output_dir/performance-samples-active.logcat.txt" >&2
    exit 1
fi
echo "QUALITY NR+EASU+RCAS stages were measured; clean synthetic frames need not differ pixel-for-pixel from BALANCED."
if grep -Fq 'display_fps=' "$output_dir/performance-samples.logcat.txt"; then
    echo "Performance sample incorrectly reports renderer callbacks as display FPS" >&2
    exit 1
fi

# Preserve and validate the debug-only equivalence result before the local-media smoke
# clears Logcat for each picker selection.
validation_result_count="$(grep -Ec 'NKScaleTV: easu_equivalence=(pass|fail) ' "$output_dir/preset-and-settings-logcat.txt" || true)"
if [[ "$validation_result_count" -eq 0 ]]; then
    if ! grep -Fq 'easu_external=unavailable' "$output_dir/preset-and-settings-logcat.txt"; then
        echo "EASU equivalence probe produced no result and no explicit unavailable fallback" >&2
        exit 1
    fi
elif [[ "$validation_result_count" -ne 1 ]]; then
    echo "Expected exactly one per-session EASU equivalence result; saw $validation_result_count" >&2
    exit 1
fi

assert_native4k_linear_final_strategy

# Exercise actual local selection/playback at 25/30/50/60 fps and portrait/576p aspect shapes.
bash android/scripts/tv-local-media-smoke.sh "$output_dir/local-media"
adb logcat -d -s NKScaleTV:V AndroidRuntime:E > "$output_dir/logcat.txt"

for marker in 'shaders=ready' 'gpu_timer_query=' 'easu_equivalence=' 'performance_sample requested=' 'video=playing' 'video_frame=consumed' 'video=paused' 'surface=' 'render_fps=' 'mode=fsr1' 'mode=fsr1_denoise' 'mode=easu_only' 'mode=bilinear' 'sharpness=9' 'settings=loaded preset=balanced sharpness=9' 'aspect_mode=16_9' 'aspect_mode=4_3' 'aspect_mode=1_1' 'aspect_mode=auto'; do
    if ! grep -Fq "$marker" "$output_dir/preset-and-settings-logcat.txt"; then
        echo "Expected preset/settings emulator marker was not observed: $marker" >&2
        cat "$output_dir/preset-and-settings-logcat.txt" >&2
        exit 1
    fi
done
if grep -Fq 'FATAL EXCEPTION' "$output_dir/logcat.txt"; then
    echo "Android app crashed in the TV emulator" >&2
    cat "$output_dir/logcat.txt" >&2
    exit 1
fi

crop_chart_region() {
    input_png="$1"
    output_rgb="$2"
    ffmpeg -hide_banner -loglevel error -y \
        -i "$input_png" \
        -vf 'crop=iw/8:ih:7*iw/8:0' -frames:v 1 -f rawvideo -pix_fmt rgb24 \
        "$output_rgb"
}

# The lower-left TV control panel cannot cover this rightmost chart strip.
crop_chart_region "$output_dir/video-motion-a.png" "$output_dir/video-motion-a.rgb"
crop_chart_region "$output_dir/video-motion-b.png" "$output_dir/video-motion-b.rgb"
crop_chart_region "$output_dir/video-resumed-a.png" "$output_dir/video-resumed-a.rgb"
crop_chart_region "$output_dir/video-resumed-b.png" "$output_dir/video-resumed-b.rgb"
crop_chart_region "$output_dir/fsr1-paused.png" "$output_dir/fsr1-paused.rgb"
crop_chart_region "$output_dir/quality-paused.png" "$output_dir/quality-paused.rgb"
crop_chart_region "$output_dir/performance-paused.png" "$output_dir/performance-paused.rgb"
crop_chart_region "$output_dir/off-paused.png" "$output_dir/off-paused.rgb"
crop_chart_region "$output_dir/fsr1-sharpness9.png" "$output_dir/fsr1-sharpness9.rgb"

assert_different_pixels() {
    first="$1"
    second="$2"
    message="$3"
    if cmp -s "$first" "$second"; then
        echo "$message" >&2
        exit 1
    fi
}

assert_different_pixels "$output_dir/video-motion-a.rgb" "$output_dir/video-motion-b.rgb" \
    "Decoded H.264 frame captures did not change across one second of playback"
assert_different_pixels "$output_dir/video-resumed-a.rgb" "$output_dir/video-resumed-b.rgb" \
    "Decoded H.264 frame captures did not resume advancing after the remote Play control"
assert_different_pixels "$output_dir/off-paused.rgb" "$output_dir/performance-paused.rgb" \
    "PERFORMANCE (EASU) and OFF (bilinear) captures were identical"
assert_different_pixels "$output_dir/off-paused.rgb" "$output_dir/fsr1-paused.rgb" \
    "BALANCED (EASU+RCAS) and OFF (bilinear) captures were identical"
assert_different_pixels "$output_dir/fsr1-paused.rgb" "$output_dir/fsr1-sharpness9.rgb" \
    "BALANCED sharpness 8 and 9 produced identical output"

sha256sum \
    "$output_dir/video-motion-a.png" \
    "$output_dir/video-motion-b.png" \
    "$output_dir/video-resumed-a.png" \
    "$output_dir/video-resumed-b.png" \
    "$output_dir/off-paused.png" \
    "$output_dir/performance-paused.png" \
    "$output_dir/quality-paused.png" \
    "$output_dir/fsr1-paused.png" \
    "$output_dir/fsr1-sharpness9.png" \
    "$output_dir/native4k-strategy-c.png" \
    "$output_dir/native4k-strategy-c.logcat.txt" \
    "$output_dir/native-planner-initial.logcat.txt" \
    "$output_dir/preset-and-settings-logcat.txt" \
    "$output_dir/logcat.txt" \
    "$output_dir/local-media/local-input-playback.txt"
echo "Android TV emulator smoke passed: four render presets, selectable A-D strategy UI, requested native-4K C-path verification/fallback, persisted settings and local Photo Picker playback at 25/30/50/60 fps. SwiftShader emulator only; no TCL hardware validation. Pixel differences are not quality scores."
