#!/usr/bin/env bash
#
# Build charts.mbtiles from free NOAA ENC (S-57) in WSL.
#   GDAL extracts S-57 object classes -> per-class GeoJSON -> tippecanoe -> vector MBTiles.
#
# ENC USAGE BANDS (the thing that bit us):
#   NOAA ships six bands — 1=overview, 2=general, 3=coastal, 4=approach, 5=harbour, 6=berthing.
#   Each is a GENERALISATION of the finer ones. At overview scale San Diego Bay is simplified away
#   and LNDARE is drawn straight across it. If every band is merged into one layer, that coarse
#   land polygon paints over the harbour-band water at every zoom (the "yellow bay" bug), and the
#   routing grid then believes the bay is dry land.
#   So: every feature is tagged with its band as `INTU` (the 3rd char of the cell name, e.g.
#   US5SANCD -> 5). The style then draws fills band-by-band, coarse -> fine, so a finer band's
#   water correctly overdraws a coarser band's land, while coarse data still fills in offshore.
#
set -euo pipefail

XMIN=-123.2; YMIN=27.6; XMAX=-111.2; YMAX=37.8   # San Diego + ~300 nm (data itself ends ~-115.2)
STATE_ZIP="${STATE_ZIP:-CA_ENCs.zip}"
BASE_URL="https://www.charts.noaa.gov/ENCs"
MINZOOM="${MINZOOM:-2}"
# NB: app/src/main/assets/style.json no longer hardcodes this — MbTilesServer reads the real
# zoom range out of the file at runtime. Kept here only to drive tippecanoe.
MAXZOOM="${MAXZOOM:-16}"
OUT="/mnt/d/Work/Programming/Android/MyNavvy/charts-pipeline/out"
WORK="/root/mynavvy_work"

mkdir -p "$WORK" "$OUT"
cd "$WORK"
echo "workdir=$WORK  gdal=$(ogr2ogr --version)"

echo "== [1/5] ensure tippecanoe =="
if ! command -v tippecanoe >/dev/null 2>&1; then
  DEBIAN_FRONTEND=noninteractive apt-get install -y build-essential libsqlite3-dev zlib1g-dev git >/dev/null
  rm -rf tippecanoe-src && git clone --depth 1 https://github.com/felt/tippecanoe tippecanoe-src >/dev/null 2>&1
  make -C tippecanoe-src -j"$(nproc)" >/dev/null && make -C tippecanoe-src install >/dev/null
fi

echo "== [2/5] download $STATE_ZIP (cached) =="
[ -s enc.zip ] || curl -fSL --retry 3 -o enc.zip "$BASE_URL/$STATE_ZIP"

echo "== [3/5] unzip =="
[ -d ENC_ROOT ] || unzip -q -o enc.zip
mapfile -t CELLS < <(find . -name '*.000' | sort)
echo "found ${#CELLS[@]} ENC cells"
[ "${#CELLS[@]}" -gt 0 ] || { echo "no cells"; exit 1; }

export OGR_S57_OPTIONS="SPLIT_MULTIPOINT=ON,ADD_SOUNDG_DEPTH=ON,RETURN_PRIMITIVES=OFF,RETURN_LINKAGES=OFF,LNAM_REFS=OFF"
CLASSES=(DEPARE DRGARE LNDARE COALNE DEPCNT SOUNDG LIGHTS BOYLAT)

echo "== [4/5] extract + merge, tagging each feature with its usage band (INTU) =="
rm -f charts.gpkg
for cell in "${CELLS[@]}"; do
  band=$(basename "$cell" | cut -c3)          # US5SANCD.000 -> 5
  case "$band" in [1-6]) ;; *) band=0 ;; esac # unknown -> 0
  for C in "${CLASSES[@]}"; do
    ogr2ogr -f GPKG -update -append -skipfailures \
      -nln "$C" -nlt PROMOTE_TO_MULTI \
      -spat "$XMIN" "$YMIN" "$XMAX" "$YMAX" \
      -clipsrc spat_extent \
      -dialect sqlite -sql "SELECT *, $band AS INTU FROM \"$C\"" \
      charts.gpkg "$cell" 2>/dev/null || true
  done
done

GEOJSON_ARGS=()
for C in "${CLASSES[@]}"; do
  if ogrinfo charts.gpkg "$C" >/dev/null 2>&1; then
    rm -f "$C.geojson"
    ogr2ogr -f GeoJSON "$C.geojson" charts.gpkg "$C"
    n=$(ogrinfo -so charts.gpkg "$C" | awk -F': ' '/Feature Count/{print $2}')
    echo "  $C: ${n:-0} features"
    GEOJSON_ARGS+=("$C.geojson")
  fi
done
[ "${#GEOJSON_ARGS[@]}" -gt 0 ] || { echo "no features in bbox"; exit 1; }

echo "-- band coverage sanity check (DEPARE) --"
ogrinfo -q charts.gpkg -sql "SELECT INTU, count(*) AS n FROM DEPARE GROUP BY INTU" 2>/dev/null \
  | grep -E 'INTU|n \(' | paste - - || true

echo "== [5/5] tile with tippecanoe =="
# NEVER --coalesce-densest-as-needed, ALWAYS --no-tiny-polygon-reduction: both merge adjacent
# polygons to fit the tile budget, and a merged polygon keeps only ONE feature's attributes, so
# DEPARE loses DRVAL1 (and now INTU) and the depth shading silently disappears.
tippecanoe -o "$OUT/charts.mbtiles" --force \
  -Z"$MINZOOM" -z"$MAXZOOM" \
  --drop-densest-as-needed \
  --no-tiny-polygon-reduction \
  --maximum-tile-bytes=1000000 \
  -n "MyNavvy NOAA San Diego" \
  "${GEOJSON_ARGS[@]}"

echo "== done =="
ls -lh "$OUT/charts.mbtiles"
python3 /mnt/d/Work/Programming/Android/MyNavvy/charts-pipeline/inspect_mbtiles.py "$OUT/charts.mbtiles" || true
