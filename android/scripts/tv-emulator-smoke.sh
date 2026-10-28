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
# Stay in Enhanced while the sparse 120-callback glFinish sample is collected.
wait_for_log_marker 'render_completion_ms=' 'sparse submit-to-GL-finish sample'

# Compare two moving decoded frames before pausing to prove MediaPlayer is feeding GLES.
adb exec-out screencap -p > "$output_dir/video-motion-a.png"
sleep 1
adb exec-out screencap -p > "$output_dir/video-motion-b.png"

# The activity starts on Enhanced and focuses its button. DOWN opens the video control.
# Pause playback so the same decoded frame can be compared through both scaler paths.
adb shell input keyevent KEYCODE_DPAD_DOWN
adb shell input keyevent KEYCODE_DPAD_CENTER
wait_for_log_marker 'video=paused' 'remote pause control'
sleep 1
adb shell input keyevent KEYCODE_DPAD_UP
sleep 1
adb exec-out screencap -p > "$output_dir/enhanced-paused.png"
adb shell input keyevent KEYCODE_DPAD_LEFT
adb shell input keyevent KEYCODE_DPAD_CENTER
sleep 1
adb exec-out screencap -p > "$output_dir/original-paused.png"

# Move DOWN through the pause button to the sharpness slider, then 8 -> 9.
adb shell input keyevent KEYCODE_DPAD_DOWN
adb shell input keyevent KEYCODE_DPAD_DOWN
adb shell input keyevent KEYCODE_DPAD_RIGHT
sleep 1
adb logcat -d -s NKScaleTV:V AndroidRuntime:E > "$output_dir/logcat.txt"

for marker in 'shaders=ready' 'video=playing' 'video_frame=uploaded' 'video=paused' 'surface=' 'render_fps=' 'output=enhanced' 'output=bilinear' 'sharpness=9'; do
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

# The lower-left control panel cannot cover the rightmost chart slice.
crop_chart_region "$output_dir/video-motion-a.png" "$output_dir/video-motion-a.rgb"
crop_chart_region "$output_dir/video-motion-b.png" "$output_dir/video-motion-b.rgb"
crop_chart_region "$output_dir/enhanced-paused.png" "$output_dir/enhanced-paused.rgb"
crop_chart_region "$output_dir/original-paused.png" "$output_dir/original-paused.rgb"

if cmp -s "$output_dir/video-motion-a.rgb" "$output_dir/video-motion-b.rgb"; then
    echo "Decoded H.264 frame captures did not change across one second of playback" >&2
    exit 1
fi
if cmp -s "$output_dir/enhanced-paused.rgb" "$output_dir/original-paused.rgb"; then
    echo "Enhanced and bilinear captures were identical for the same paused video frame" >&2
    exit 1
fi

sha256sum \
    "$output_dir/video-motion-a.png" \
    "$output_dir/video-motion-b.png" \
    "$output_dir/enhanced-paused.png" \
    "$output_dir/original-paused.png" \
    "$output_dir/logcat.txt"
echo "Android TV H.264 decode/GLES smoke passed on SwiftShader. This is emulator-only evidence, not target GPU or physical-TV validation."
