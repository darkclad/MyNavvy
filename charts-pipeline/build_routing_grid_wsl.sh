#!/usr/bin/env bash
# Build the draft-aware routing mask (routing_grid.png + .json) from charts.gpkg.
#
# CRITICAL: rasterize the ENC usage bands COARSE -> FINE (INTU 1..6), so the harbour band's water
# overwrites the overview band's generalised land. Merging bands made the router believe San Diego
# Bay was dry land, which is why routes starting in the bay came back "partial".
set -euo pipefail

GPKG=/root/mynavvy_work/charts.gpkg
OUT=/mnt/d/Work/Programming/Android/MyNavvy/charts-pipeline/out
PY=/mnt/d/Work/Programming/Android/MyNavvy/charts-pipeline/make_routing_grid.py

# Routing area (San Diego focus) + resolution in degrees (~0.0008deg ~= 75-90 m).
XMIN=-117.60; YMIN=32.35; XMAX=-116.90; YMAX=33.05; RES=0.0008

mkdir -p "$OUT"
[ -f "$GPKG" ] || { echo "charts.gpkg missing at $GPKG — run build_charts_wsl.sh first"; exit 1; }
ogrinfo -so "$GPKG" DEPARE 2>/dev/null | grep -q INTU || {
    echo "charts.gpkg has no INTU column — re-run build_charts_wsl.sh (band tagging)"; exit 1; }

python3 -c "from osgeo import gdal" 2>/dev/null || {
    echo "== installing python3-gdal + numpy =="
    DEBIAN_FRONTEND=noninteractive apt-get install -y python3-gdal python3-numpy >/dev/null
}

cd /root/mynavvy_work
TE="-te $XMIN $YMIN $XMAX $YMAX -tr $RES $RES"

echo "== init rasters =="
# class: 0 unknown, 1 land, 2 water
gdal_rasterize -q -burn 0 -l LNDARE $TE -init 0 -ot Byte -where "1=0" "$GPKG" class.tif
# depth: DRVAL1 metres, -9999 = none
gdal_rasterize -q -burn 0 -l DEPARE $TE -init -9999 -a_nodata -9999 -ot Float32 -where "1=0" "$GPKG" depth.tif

for B in 1 2 3 4 5 6; do
    echo "== band $B =="
    # water first, then land within the band (they are disjoint); the NEXT band overwrites both
    gdal_rasterize -q -burn 2 -l DEPARE -where "INTU=$B" "$GPKG" class.tif || true
    gdal_rasterize -q -burn 1 -l LNDARE -where "INTU=$B" "$GPKG" class.tif || true
    gdal_rasterize -q -a DRVAL1 -l DEPARE -where "INTU=$B" "$GPKG" depth.tif || true
done

echo "== encode routing mask =="
python3 "$PY" class.tif depth.tif "$OUT/routing_grid.png" "$OUT/routing_grid.json"

ls -lh "$OUT/routing_grid.png" "$OUT/routing_grid.json"
cat "$OUT/routing_grid.json"; echo
