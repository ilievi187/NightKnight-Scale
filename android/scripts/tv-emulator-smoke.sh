#!/usr/bin/env bash
set -euo pipefail

apk_path="${1:?usage: tv-emulator-smoke.sh APK_PATH OUTPUT_DIR}"
output_dir="${2:?usage: tv-emulator-smoke.sh APK_PATH OUTPUT_DIR}"
package_name="org.nightknight.scale.tv"
activity_name="${package_name}/.MainActivity"

mkdir -p "$output_dir"
trap 'adb logcat -d -s NKScaleTV:V AndroidRuntime:E > "$output_dir/logcat.txt" 2>/dev/null || true' EXIT
adb wait-for-device
target_device="$(adb get-serialno)"
if [[ "$target_device" != emulator-* ]]; then
    echo "Refusing to alter a non-emulator display during the 1080p density smoke: $target_device" >&2
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
adb shell am start -W -n "$activity_name"

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

press_preset() {
    local preset_id="$1"
    local description="$2"
    local sample_mode="${3:-}"
    local marker="preset=${preset_id} "
    local current_count expected_count sample_marker sample_count expected_sample_count
    current_count="$(adb logcat -d -s NKScaleTV:I | grep -Fc "$marker" || true)"
    expected_count=$(( ${current_count:-0} + 1 ))
    if [[ -n "$sample_mode" ]]; then
        sample_marker="performance_sample requested=${sample_mode} effective=${sample_mode} "
        sample_count="$(adb logcat -d -s NKScaleTV:I | grep -Fc "$sample_marker" || true)"
        expected_sample_count=$(( ${sample_count:-0} + 1 ))
    fi
    adb shell input keyevent KEYCODE_DPAD_CENTER
    wait_for_log_occurrences "$marker" "$expected_count" "$description"
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

assert_diagnostics_reachable_at_1080p() {
    echo "1080p diagnostics test: waiting for startup at density 400"
    wait_for_log_marker 'shaders=ready' 'GLES initialization at 1080p dense UI scale'
    wait_for_log_marker 'video_frame=consumed' 'video frame at 1080p dense UI scale'
    wait_for_log_marker 'surface=1920×1080' '1920x1080 GLES surface'

    # Focus chain: selected preset -> picker -> playback -> sharpness -> Diagnostics.
    for _ in 1 2 3 4; do adb shell input keyevent KEYCODE_DPAD_DOWN; done
    wait_for_log_marker 'diagnostics=focused visible=true' 'visible Diagnostics focus after D-pad navigation' 15
    adb shell input keyevent KEYCODE_DPAD_CENTER
    wait_for_log_marker 'diagnostics=open' 'Diagnostics dialog opening at 1080p' 15
    adb shell input keyevent KEYCODE_BACK

    # Restore the AVD's default density before pixel-comparison playback tests.
    adb shell am force-stop "$package_name"
    adb shell wm density reset >/dev/null
    adb logcat -c
    adb shell am start -W -n "$activity_name" --ez nkscale_validate_easu_input true
    echo "1080p diagnostics test: passed; restored default density"
}

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
adb shell input keyevent KEYCODE_DPAD_CENTER
wait_for_log_occurrences 'video=playing source=' 2 'remote resume control'
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
adb shell input keyevent KEYCODE_DPAD_CENTER
sleep 1
adb exec-out screencap -p > "$output_dir/diagnostics-dialog.png"
adb shell uiautomator dump /sdcard/nkscale-diagnostics.xml >/dev/null 2>&1
adb pull /sdcard/nkscale-diagnostics.xml "$output_dir/diagnostics-dialog.xml" >/dev/null 2>&1
if ! grep -Fq 'NightKnight · Diagnostics' "$output_dir/diagnostics-dialog.xml" || \
   ! grep -Fq 'EASU pass count' "$output_dir/diagnostics-dialog.xml" || \
   ! grep -Fq 'RGBA render-target estimate' "$output_dir/diagnostics-dialog.xml" || \
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
if grep -Fq 'display_fps=' "$output_dir/performance-samples.logcat.txt"; then
    echo "Performance sample incorrectly reports renderer callbacks as display FPS" >&2
    exit 1
fi

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
assert_different_pixels "$output_dir/fsr1-paused.rgb" "$output_dir/quality-paused.rgb" \
    "QUALITY and BALANCED captures were identical"
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
    "$output_dir/preset-and-settings-logcat.txt" \
    "$output_dir/logcat.txt" \
    "$output_dir/local-media/local-input-playback.txt"
echo "Android TV emulator smoke passed: four render presets, persisted settings, packaged playback and automated local Photo Picker playback across 25/30/50/60 fps sources. SwiftShader emulator only; no TCL hardware validation. Pixel differences are not quality scores."
