#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
asset_dir="$repo_root/android/app/src/main/assets/test-videos"
mkdir -p "$asset_dir"

generate_video() {
    local dimensions="$1"
    local frame_rate="$2"
    local sar="$3"
    local filename="$4"
    local output="$asset_dir/$filename"
    if [ -e "$output" ]; then
        echo "Keeping existing test source (not overwriting): $output"
        return
    fi

    ffmpeg -nostdin -hide_banner -loglevel error \
        -f lavfi -i "testsrc2=size=${dimensions}:rate=${frame_rate}" \
        -vf "setsar=${sar}" -frames:v 180 -an \
        -c:v libx264 -preset veryfast -crf 30 -pix_fmt yuv420p \
        -g "$frame_rate" -keyint_min "$frame_rate" -sc_threshold 0 -threads 1 \
        -fflags +bitexact -flags:v +bitexact -map_metadata -1 \
        -movflags +faststart "$output"
    ffprobe -v error -select_streams v:0 \
        -show_entries stream=codec_name,width,height,avg_frame_rate,pix_fmt,sample_aspect_ratio,display_aspect_ratio \
        -of default=noprint_wrappers=1 "$output"
}

for dimensions in 640x360 960x540 1280x720 1920x1080; do
    generate_video "$dimensions" 30 1/1 "scale-test-${dimensions}.mp4"
done

generate_video 720x576 25 16/15 scale-test-720x576.mp4
generate_video 960x540 25 1/1 scale-test-960x540-25fps.mp4
generate_video 960x540 50 1/1 scale-test-960x540-50fps.mp4
generate_video 960x540 60 1/1 scale-test-960x540-60fps.mp4
generate_video 720x576 25 16/15 scale-test-720x576-4x3-25fps.mp4
generate_video 720x576 25 64/45 scale-test-720x576-16x9-25fps.mp4
