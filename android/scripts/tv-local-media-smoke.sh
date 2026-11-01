#!/usr/bin/env bash
set -euo pipefail

output_dir="${1:?usage: tv-local-media-smoke.sh OUTPUT_DIR}"
package_name="org.nightknight.scale.tv"
activity_name="${package_name}/.MainActivity"
work_dir="$output_dir/.local-media-work"
mkdir -p "$output_dir" "$work_dir"
trap 'rm -rf "$work_dir"' EXIT

make_clip() {
    local name="$1" size="$2" fps="$3" duration="$4" sar="$5" rotation="$6"
    local filters="setsar=${sar}"
    ffmpeg -hide_banner -loglevel error -y \
        -f lavfi -i "testsrc2=size=${size}:rate=${fps}" -t "$duration" -an \
        -vf "$filters" -c:v libx264 -preset ultrafast -crf 30 -pix_fmt yuv420p \
        -color_primaries smpte170m -color_trc smpte170m -colorspace smpte170m \
        -metadata:s:v:0 "rotate=${rotation}" -movflags +faststart "$work_dir/$name"
    local probe
    probe="$(ffprobe -v error -select_streams v:0 -show_entries stream=width,height,r_frame_rate \
        -of csv=p=0:s=x "$work_dir/$name")"
    if [ "$probe" != "${size}x${fps}/1" ]; then
        echo "Generated local fixture $name has unexpected video metadata: $probe" >&2
        exit 1
    fi
    ffprobe -v error -select_streams v:0 \
        -show_entries stream=width,height,r_frame_rate,sample_aspect_ratio,color_space,color_transfer,color_primaries \
        -of default=noprint_wrappers=1 "$work_dir/$name" >> "$output_dir/local-input-fixtures.txt"
    printf '\n' >> "$output_dir/local-input-fixtures.txt"
    adb push "$work_dir/$name" "/sdcard/Download/$name" >/dev/null
    adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE \
        -d "file:///sdcard/Download/$name" >/dev/null
    for attempt in $(seq 1 45); do
        if adb shell content query --uri content://media/external/video/media --projection _display_name \
            2>/dev/null | grep -Fq "$name"; then
            return 0
        fi
        sleep 1
    done
    echo "MediaStore did not index test clip $name" >&2
    exit 1
}

ui_tap_matching() {
    local needle="$1" limit="${2:-45}"
    local output_file="$work_dir/window.xml"
    for attempt in $(seq 1 "$limit"); do
        adb shell uiautomator dump /sdcard/nkscale-window.xml >/dev/null 2>&1 || true
        adb pull /sdcard/nkscale-window.xml "$output_file" >/dev/null 2>&1 || true
        local center
        center="$(python3 - "$output_file" "$needle" <<'PY'
import sys
import xml.etree.ElementTree as ET
from pathlib import Path
path, needle = sys.argv[1], sys.argv[2].lower()
try:
    root = ET.parse(path).getroot()
except (OSError, ET.ParseError):
    raise SystemExit(1)
def walk(node, ancestors=()):
    text = (node.attrib.get('text', '') + ' ' + node.attrib.get('content-desc', '')).lower()
    if needle in text:
        options = (node, *reversed(ancestors))
        target = next((n for n in options if n.attrib.get('clickable') == 'true'), node)
        bounds = target.attrib.get('bounds', '')
        try:
            left_top, right_bottom = bounds.strip('[]').split('][')
            x1, y1 = map(int, left_top.split(',')); x2, y2 = map(int, right_bottom.split(','))
            print((x1+x2)//2, (y1+y2)//2)
            raise SystemExit(0)
        except (ValueError, IndexError):
            pass
    for child in node:
        walk(child, (*ancestors, node))
walk(root)
raise SystemExit(1)
PY
        )" || true
        if [ -n "$center" ]; then
            read -r x y <<< "$center"
            adb shell input tap "$x" "$y"
            return 0
        fi
        sleep 1
    done
    echo "Could not find a clickable picker item containing '$needle'" >&2
    if [ "$limit" -gt 10 ]; then
        adb exec-out screencap -p > "$output_dir/picker-failure.png" || true
        adb pull /sdcard/nkscale-window.xml "$output_dir/picker-failure.xml" >/dev/null 2>&1 || true
    fi
    return 1
}

wait_for_activity() {
    local package="$1"
    for attempt in $(seq 1 45); do
        local resumed
        resumed="$(adb shell dumpsys activity activities | python3 -c 'import sys; print(next((x.strip() for x in sys.stdin if "topResumedActivity=" in x), ""))')"
        if [[ "$resumed" == *"$package"* ]]; then return 0; fi
        sleep 1
    done
    echo "Timed out waiting for activity package $package" >&2
    return 1
}

wait_for_app_log() {
    local marker="$1"
    for attempt in $(seq 1 60); do
        local log_output
        log_output="$(adb logcat -d -s NKScaleTV:V)"
        if grep -Fq "$marker" <<< "$log_output"; then return 0; fi
        sleep 1
    done
    echo "Timed out waiting for local-video marker: $marker" >&2
    adb logcat -d -s NKScaleTV:V AndroidRuntime:E >&2
    return 1
}

select_and_verify() {
    local filename="$1" duration_label="$2" expected_size="$3" expected_fps="$4" expected_stages="$5" first="$6"
    if [ "$first" = yes ]; then
        adb shell input keyevent KEYCODE_DPAD_DOWN
    fi
    adb logcat -c
    adb shell input keyevent KEYCODE_DPAD_CENTER
    wait_for_activity com.google.android.photopicker
    # The first-run privacy notice is dismissible and may overlay the video grid.
    ui_tap_matching "Dismiss" 3 >/dev/null 2>&1 || true
    ui_tap_matching "$duration_label"
    # Android TV Photo Picker stages the single selection; confirm explicitly.
    ui_tap_matching "Done" 15
    wait_for_activity "$package_name"
    wait_for_app_log "video=decoded-size ${expected_size} input=local video"
    wait_for_app_log "video=playing source=local-video"
    wait_for_app_log "easu_stages=$expected_stages"
    if [ "$expected_size" = "768x576" ]; then
        wait_for_app_log "rcas_output=direct"
    fi
    # Confirm motion through the processed renderer, rather than only a prepare callback.
    sleep 2
    adb exec-out screencap -p > "$output_dir/${filename%.mp4}-a.png"
    sleep 1
    adb exec-out screencap -p > "$output_dir/${filename%.mp4}-b.png"
    adb logcat -d -s NKScaleTV:V AndroidRuntime:E > "$output_dir/${filename%.mp4}-logcat.txt"
    if ! ffmpeg -hide_banner -loglevel error -y -i "$output_dir/${filename%.mp4}-a.png" \
        -vf 'crop=320:640:800:200' -frames:v 1 -f rawvideo -pix_fmt rgb24 "$work_dir/a.rgb"; then
        echo "Failed to crop local video frame A for $filename" >&2; return 1
    fi
    ffmpeg -hide_banner -loglevel error -y -i "$output_dir/${filename%.mp4}-b.png" \
        -vf 'crop=320:640:800:200' -frames:v 1 -f rawvideo -pix_fmt rgb24 "$work_dir/b.rgb"
    if cmp -s "$work_dir/a.rgb" "$work_dir/b.rgb"; then
        echo "Selected local video $filename did not advance in the GLES preview" >&2
        return 1
    fi
    printf 'played=%s size=%s expected_encoded_fps=%s expected_easu_stages=%s\n' "$filename" "$expected_size" "$expected_fps" "$expected_stages" \
        >> "$output_dir/local-input-playback.txt"
}

: > "$output_dir/local-input-fixtures.txt"
: > "$output_dir/local-input-playback.txt"
make_clip nk-local-25-576p.mp4 768x576 25 4 1/1 0
make_clip nk-local-30-480p.mp4 640x360 30 5 1/1 0
make_clip nk-local-50-576p.mp4 768x576 50 6 1/1 0
make_clip nk-local-60-480p.mp4 640x360 60 7 1/1 0
make_clip nk-local-portrait.mp4 360x640 30 8 1/1 0

select_and_verify nk-local-25-576p.mp4 'duration 00:04' '768x576' 25 1 yes
select_and_verify nk-local-30-480p.mp4 'duration 00:05' '640x360' 30 2 no
select_and_verify nk-local-50-576p.mp4 'duration 00:06' '768x576' 50 1 no
select_and_verify nk-local-60-480p.mp4 'duration 00:07' '640x360' 60 2 no
select_and_verify nk-local-portrait.mp4 'duration 00:08' '360x640' 30 1 no

echo "SAF/Photo Picker local-file playback smoke passed: 25/30/50/60 fps SDR clips, 4:3/16:9/portrait aspect ratios and 576p. Emulator-only; not a physical TV test."