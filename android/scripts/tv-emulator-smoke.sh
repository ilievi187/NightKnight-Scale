#!/usr/bin/env bash
set -euo pipefail

apk_path="${1:?usage: tv-emulator-smoke.sh APK_PATH OUTPUT_DIR}"
output_dir="${2:?usage: tv-emulator-smoke.sh APK_PATH OUTPUT_DIR}"
package_name="org.nightknight.scale.tv"
activity_name="${package_name}/.MainActivity"

mkdir -p "$output_dir"
adb wait-for-device

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
adb logcat -c
adb shell am force-stop "$package_name"
adb shell am start -W -n "$activity_name"

wait_for_log_marker() {
    marker="$1"
    description="$2"
    for attempt in $(seq 1 90); do
        if adb logcat -d -s NKScaleTV:I | grep -Fq "$marker"; then
            return 0
        fi
        sleep 1
    done
    echo "Timed out waiting for emulator runtime marker: $description ($marker)" >&2
    adb logcat -d -s NKScaleTV:V AndroidRuntime:E >&2
    exit 1
}

wait_for_log_marker 'shaders=ready' 'GLES shader initialization'
wait_for_log_marker 'video=playing' 'MediaPlayer prepare/start'
wait_for_log_marker 'video_frame=uploaded' 'decoded SurfaceTexture frame upload'
wait_for_log_marker 'render_fps=' 'render callback FPS'
# Keep the initial FSR1 path running until the sparse 120-callback glFinish sample.
wait_for_log_marker 'render_completion_ms=' 'sparse submit-to-GL-finish sample'

# Two moving decoded captures prove the packaged video is updating the GLES input.
adb exec-out screencap -p > "$output_dir/video-motion-a.png"
sleep 1
adb exec-out screencap -p > "$output_dir/video-motion-b.png"

# Initial focus is the algorithm control in FSR1 mode. DOWN opens the pause control.
adb shell input keyevent KEYCODE_DPAD_DOWN
adb shell input keyevent KEYCODE_DPAD_CENTER
wait_for_log_marker 'video=paused' 'remote pause control'
sleep 1
adb shell input keyevent KEYCODE_DPAD_UP
sleep 1
adb exec-out screencap -p > "$output_dir/fsr1-paused.png"

# Cycle FSR1 → FSR1+NR → EASU-only with the TV remote on one paused frame.
adb shell input keyevent KEYCODE_DPAD_CENTER
wait_for_log_marker 'mode=fsr1_denoise' 'FSR1 plus edge-aware NR mode'
sleep 1
adb exec-out screencap -p > "$output_dir/fsr1-nr-paused.png"
adb shell input keyevent KEYCODE_DPAD_CENTER
wait_for_log_marker 'mode=easu_only' 'EASU-only mode'
sleep 1
adb exec-out screencap -p > "$output_dir/easu-only-paused.png"

# Select Bilinear Original.
adb shell input keyevent KEYCODE_DPAD_LEFT
adb shell input keyevent KEYCODE_DPAD_CENTER
wait_for_log_marker 'mode=bilinear' 'Bilinear Original mode'
sleep 1
adb exec-out screencap -p > "$output_dir/bilinear-paused.png"

# Return through EASU-only to FSR1, then change RCAS from 8 to 9 and capture the result.
adb shell input keyevent KEYCODE_DPAD_RIGHT
adb shell input keyevent KEYCODE_DPAD_CENTER
wait_for_log_marker 'mode=easu_only' 'revisit EASU-only mode'
adb shell input keyevent KEYCODE_DPAD_CENTER
wait_for_log_marker 'mode=fsr1' 'return to FSR1 for sharpness test'
adb shell input keyevent KEYCODE_DPAD_DOWN
adb shell input keyevent KEYCODE_DPAD_DOWN
adb shell input keyevent KEYCODE_DPAD_RIGHT
sleep 1
adb exec-out screencap -p > "$output_dir/fsr1-sharpness9.png"
adb logcat -d -s NKScaleTV:V AndroidRuntime:E > "$output_dir/logcat.txt"

for marker in 'shaders=ready' 'video=playing' 'video_frame=uploaded' 'video=paused' 'surface=' 'render_fps=' 'render_completion_ms=' 'mode=fsr1' 'mode=fsr1_denoise' 'mode=easu_only' 'mode=bilinear' 'sharpness=9'; do
    if ! grep -Fq "$marker" "$output_dir/logcat.txt"; then
        echo "Expected emulator runtime marker was not observed: $marker" >&2
        cat "$output_dir/logcat.txt" >&2
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
crop_chart_region "$output_dir/fsr1-paused.png" "$output_dir/fsr1-paused.rgb"
crop_chart_region "$output_dir/fsr1-nr-paused.png" "$output_dir/fsr1-nr-paused.rgb"
crop_chart_region "$output_dir/easu-only-paused.png" "$output_dir/easu-only-paused.rgb"
crop_chart_region "$output_dir/bilinear-paused.png" "$output_dir/bilinear-paused.rgb"
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
assert_different_pixels "$output_dir/bilinear-paused.rgb" "$output_dir/easu-only-paused.rgb" \
    "EASU-only and bilinear captures were identical"
assert_different_pixels "$output_dir/bilinear-paused.rgb" "$output_dir/fsr1-paused.rgb" \
    "FSR1 EASU+RCAS and bilinear captures were identical"
assert_different_pixels "$output_dir/fsr1-paused.rgb" "$output_dir/fsr1-nr-paused.rgb" \
    "FSR1 and edge-aware NR variants were identical"
assert_different_pixels "$output_dir/fsr1-paused.rgb" "$output_dir/fsr1-sharpness9.rgb" \
    "FSR1 sharpness 8 and 9 produced identical output"

sha256sum \
    "$output_dir/video-motion-a.png" \
    "$output_dir/video-motion-b.png" \
    "$output_dir/bilinear-paused.png" \
    "$output_dir/easu-only-paused.png" \
    "$output_dir/fsr1-paused.png" \
    "$output_dir/fsr1-nr-paused.png" \
    "$output_dir/fsr1-sharpness9.png" \
    "$output_dir/logcat.txt"
echo "Android TV emulator four-mode H.264/GLES smoke passed on SwiftShader. Pixel differences are not quality scores. This is emulator-only evidence, not target GPU or physical-TV validation."
