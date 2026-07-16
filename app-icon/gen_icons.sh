#!/usr/bin/env bash
# Rasterise the legacy launcher PNGs (used on API 21-25; API 26+ uses the adaptive XML).
set -euo pipefail
command -v rsvg-convert >/dev/null || { DEBIAN_FRONTEND=noninteractive apt-get install -y librsvg2-bin >/dev/null; }

ICON=/mnt/d/Work/Programming/Android/MyNavvy/app-icon/icon.svg
ROUND=/mnt/d/Work/Programming/Android/MyNavvy/app-icon/icon_round.svg
RES=/mnt/d/Work/Programming/Android/MyNavvy/app/src/main/res

# density -> px
declare -A SZ=( [mdpi]=48 [hdpi]=72 [xhdpi]=96 [xxhdpi]=144 [xxxhdpi]=192 )
for d in "${!SZ[@]}"; do
    n=${SZ[$d]}
    mkdir -p "$RES/mipmap-$d"
    rsvg-convert -w "$n" -h "$n" "$ICON"  -o "$RES/mipmap-$d/ic_launcher.png"
    rsvg-convert -w "$n" -h "$n" "$ROUND" -o "$RES/mipmap-$d/ic_launcher_round.png"
    echo "  mipmap-$d: ${n}x${n}"
done
echo "done"
