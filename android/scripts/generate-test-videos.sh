#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
asset_dir="$repo_root/android/app/src/main/assets/test-videos"
mkdir -p "$asset_dir"

for dimensions in 640x360 960x540 1280x720 1920x1080; do
    output="$asset_dir/scale-test-${dimensions}.mp4"
    if [ -e "$output" ]; then
        echo "Refusing to overwrite existing test source: $output" >&2
        exit 1
    fi
    ffmpeg -nostdin -hide_banner -loglevel error \
        -f lavfi -i "testsrc2=size=${dimensions}:rate=30" \
        -frames:v 180 -an \
        -c:v libx264 -preset veryfast -crf 30 -pix_fmt yuv420p \
        -g 30 -keyint_min 30 -sc_threshold 0 -threads 1 \
        -fflags +bitexact -flags:v +bitexact -map_metadata -1 \
        -movflags +faststart "$output"
    ffprobe -v error -select_streams v:0 \
        -show_entries stream=codec_name,width,height,avg_frame_rate,pix_fmt \
        -of default=noprint_wrappers=1 "$output"
done
