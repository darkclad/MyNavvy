#!/usr/bin/env bash
# Re-tile the already-extracted (clipped) GeoJSON without redoing the slow ENC extraction.
# Flags mirror build_charts_wsl.sh step 5 — see the comment there about why coalescing is banned.
set -euo pipefail
cd /root/mynavvy_work

OUT=/mnt/d/Work/Programming/Android/MyNavvy/charts-pipeline/out
MINZOOM="${MINZOOM:-2}"
MAXZOOM="${MAXZOOM:-16}"

for f in DEPARE DRGARE LNDARE COALNE DEPCNT SOUNDG LIGHTS BOYLAT; do
    [ -s "$f.geojson" ] || { echo "missing $f.geojson"; exit 1; }
done

echo "tiling z$MINZOOM-$MAXZOOM (attributes preserved: no coalescing, no tiny-polygon reduction)"
tippecanoe -o "$OUT/charts.mbtiles" --force \
    -Z"$MINZOOM" -z"$MAXZOOM" \
    --drop-densest-as-needed \
    --no-tiny-polygon-reduction \
    --maximum-tile-bytes=1000000 \
    -n "MyNavvy NOAA San Diego" \
    DEPARE.geojson DRGARE.geojson LNDARE.geojson COALNE.geojson \
    DEPCNT.geojson SOUNDG.geojson LIGHTS.geojson BOYLAT.geojson

echo "== done =="
ls -lh "$OUT/charts.mbtiles"
python3 - <<'EOF'
import sqlite3, json
c = sqlite3.connect("/mnt/d/Work/Programming/Android/MyNavvy/charts-pipeline/out/charts.mbtiles")
m = dict(c.execute("select name,value from metadata"))
print("minzoom", m.get("minzoom"), "maxzoom", m.get("maxzoom"))
print("bounds ", m.get("bounds"))
print("tiles  ", c.execute("select count(*) from tiles").fetchone()[0])
v = json.loads(m.get("json", "{}")).get("vector_layers", [])
for l in v:
    fields = ",".join(sorted(l.get("fields", {}).keys()))[:70]
    print(f"  layer {l['id']:<8} fields: {fields}")
EOF
