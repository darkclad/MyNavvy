#!/usr/bin/env bash
# OSM land basemap for a region (OpenMapTiles schema via Planetiler), called by
# build_region.sh step [8/8] — or standalone:
#   build_basemap.sh <XMIN> <YMIN> <XMAX> <YMAX> <out.mbtiles>
# Reproduces (and replaces) the previously one-off manual Planetiler run. Extract choice is
# automatic from Geofabrik's index; a cross-border bbox clips + merges several extracts.
set -euo pipefail
XMIN=$1; YMIN=$2; XMAX=$3; YMAX=$4; OUT=$5
PIPE=/mnt/d/Work/Programming/Android/MyNavvy/charts-pipeline
CACHE=/root/mynavvy_work/cache
WORK=$(mktemp -d /root/mynavvy_work/basemap.XXXXXX)
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$CACHE/osm" "$CACHE/planetiler-data"

command -v java >/dev/null 2>&1 || \
  DEBIAN_FRONTEND=noninteractive apt-get install -y openjdk-21-jre-headless >/dev/null
command -v osmium >/dev/null 2>&1 || \
  DEBIAN_FRONTEND=noninteractive apt-get install -y osmium-tool >/dev/null

JAR=$CACHE/planetiler.jar
[ -s "$JAR" ] || curl -fSL --retry 3 -o "$JAR" \
  "https://github.com/onthegomap/planetiler/releases/latest/download/planetiler.jar"

IDX=$CACHE/geofabrik-index-v1.json
if [ ! -s "$IDX" ] || [ -z "$(find "$IDX" -mtime -30 2>/dev/null)" ]; then
  curl -fsSL --retry 3 -o "$IDX" "https://download.geofabrik.de/index-v1.json"
fi

echo "== picking Geofabrik extract(s) for ($XMIN,$YMIN)->($XMAX,$YMAX) =="
python3 "$PIPE/pick_extracts.py" "$IDX" "$XMIN" "$YMIN" "$XMAX" "$YMAX" > "$WORK/extracts.tsv"
CLIPPED=()
while IFS=$'\t' read -r id url; do
  fid=${id//\//-}                     # ids like "us/arizona" -> flat cache filename
  pbf="$CACHE/osm/$fid.osm.pbf"
  echo "-- extract $id"
  if [ -s "$pbf" ]; then curl -fsSL --retry 3 -z "$pbf" -o "$pbf" "$url" || true
  else curl -fSL --retry 3 -o "$pbf" "$url"; fi
  clip="$WORK/$fid-clip.osm.pbf"
  osmium extract -b "$XMIN,$YMIN,$XMAX,$YMAX" "$pbf" -o "$clip" --overwrite
  CLIPPED+=("$clip")
done < "$WORK/extracts.tsv"
[ "${#CLIPPED[@]}" -gt 0 ] || { echo "no extracts picked"; exit 1; }

if [ "${#CLIPPED[@]}" -gt 1 ]; then
  MERGED="$WORK/merged.osm.pbf"
  osmium merge "${CLIPPED[@]}" -o "$MERGED" --overwrite
else
  MERGED="${CLIPPED[0]}"
fi

echo "== planetiler ($(du -h "$MERGED" | cut -f1) OSM) =="
java -Xmx4g -jar "$JAR" \
  --download --download-dir="$CACHE/planetiler-data" \
  --osm-path="$MERGED" \
  --output="$OUT" \
  --bounds="$XMIN,$YMIN,$XMAX,$YMAX" \
  --maxzoom=14 \
  --force
ls -lh "$OUT"
