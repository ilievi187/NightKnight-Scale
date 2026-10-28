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
# Allow first-launch resource generation, GLES shader compilation, and chart rendering.
sleep 12
adb exec-out screencap -p > "$output_dir/enhanced.png"

# The activity starts on Enhanced with focus on that button. Move LEFT to Original,
# activate it, move DOWN to the sharpness slider, then change the value from 8 to 9.
adb shell input keyevent KEYCODE_DPAD_LEFT
adb shell input keyevent KEYCODE_DPAD_CENTER
sleep 1
adb shell input keyevent KEYCODE_DPAD_DOWN
adb shell input keyevent KEYCODE_DPAD_RIGHT
sleep 4
adb exec-out screencap -p > "$output_dir/original.png"
adb logcat -d -s NKScaleTV:V AndroidRuntime:E > "$output_dir/logcat.txt"

for marker in 'shaders=ready' 'surface=' 'render_fps=' 'output=enhanced' 'output=bilinear' 'sharpness=9'; do
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

# The control panel is on the lower-left. Compare the rightmost eighth, where the
# deterministic test chart is unobstructed, to prove Original and Enhanced pixels differ.
ffmpeg -hide_banner -loglevel error -y \
    -i "$output_dir/enhanced.png" \
    -vf 'crop=iw/8:ih:7*iw/8:0' -frames:v 1 -f rawvideo -pix_fmt rgb24 \
    "$output_dir/enhanced-chart-region.rgb"
ffmpeg -hide_banner -loglevel error -y \
    -i "$output_dir/original.png" \
    -vf 'crop=iw/8:ih:7*iw/8:0' -frames:v 1 -f rawvideo -pix_fmt rgb24 \
    "$output_dir/original-chart-region.rgb"
if cmp -s "$output_dir/enhanced-chart-region.rgb" "$output_dir/original-chart-region.rgb"; then
    echo "Enhanced and bilinear chart captures were identical in the sampled region" >&2
    exit 1
fi

sha256sum "$output_dir/enhanced.png" "$output_dir/original.png" "$output_dir/logcat.txt"
echo "Android TV emulator smoke passed with software GLES rendering. This is emulator-only evidence, not target GPU or physical-TV validation."
