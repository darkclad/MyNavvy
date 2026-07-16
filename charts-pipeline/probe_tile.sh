#!/usr/bin/env bash
# Decode specific tiles and report, per zoom, whether DEPARE features still carry DRVAL1.
# Usage: probe_tile.sh            (defaults to San Diego Bay entrance)
set -uo pipefail
MB=${MB:-/mnt/d/Work/Programming/Android/MyNavvy/charts-pipeline/out/charts.mbtiles}

# lon/lat -> tile x/y at zoom z
tile() { python3 -c "
import math,sys
lon,lat,z=float(sys.argv[1]),float(sys.argv[2]),int(sys.argv[3])
n=2**z
x=int((lon+180.0)/360.0*n)
y=int((1.0-math.log(math.tan(math.radians(lat))+1/math.cos(math.radians(lat)))/math.pi)/2.0*n)
print(x,y)" "$1" "$2" "$3"; }

LON=-117.190; LAT=32.680
for Z in 10 11 12 13 14 15 16; do
    read -r X Y < <(tile $LON $LAT $Z)
    out=$(tippecanoe-decode "$MB" "$Z" "$X" "$Y" 2>/dev/null)
    if [ -z "$out" ]; then echo "z$Z ($X,$Y): (no tile)"; continue; fi
    depare=$(printf '%s' "$out" | grep -c '"DEPARE"')
    drval=$(printf '%s' "$out" | grep -c 'DRVAL1')
    echo "z$Z ($X,$Y): DEPARE mentions=$depare   DRVAL1 mentions=$drval"
done
