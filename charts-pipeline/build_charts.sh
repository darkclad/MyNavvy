#!/usr/bin/env bash
#
# Build charts.mbtiles for MyNavvy from free NOAA ENC (S-57) data.
#
#   NOAA CA_ENCs.zip  ->  ogr2ogr (S-57 driver)  ->  per-class GeoJSON  ->  tippecanoe  ->  charts.mbtiles
#
# Output layer names == S-57 object-class acronyms, matching app/src/main/assets/style.json.
# Overridable via env: BBOX, STATE_ZIP, MINZOOM, MAXZOOM.
set -euo pipefail

# San Diego Bay + ~300 nm (xmin ymin xmax ymax). 300 nm ~= 5 deg lat; lon widened by 1/cos(lat).
BBOX="${BBOX:--123.2 27.6 -111.2 37.8}"
STATE_ZIP="${STATE_ZIP:-CA_ENCs.zip}"
BASE_URL="https://www.charts.noaa.gov/ENCs"
MINZOOM="${MINZOOM:-2}"
MAXZOOM="${MAXZOOM:-16}"

WORK=/work
OUT=/data
mkdir -p "$WORK" "$OUT"
cd "$WORK"

echo "== [1/4] Downloading $STATE_ZIP =="
curl -fSL --retry 3 -o enc.zip "$BASE_URL/$STATE_ZIP"

echo "== [2/4] Unzipping =="
rm -rf ENC_ROOT
unzip -q -o enc.zip
mapfile -t CELLS < <(find . -name '*.000' | sort)
echo "Found ${#CELLS[@]} ENC cells"
if [ "${#CELLS[@]}" -eq 0 ]; then echo "No .000 cells found!"; exit 1; fi

# S-57 read options: soundings -> points carrying DEPTH; drop geometry primitives/linkages.
export OGR_S57_OPTIONS="SPLIT_MULTIPOINT=ON,ADD_SOUNDG_DEPTH=ON,RETURN_PRIMITIVES=OFF,RETURN_LINKAGES=OFF,LNAM_REFS=OFF"

# S-57 object classes we render (must match style.json source-layers).
CLASSES=(DEPARE DRGARE LNDARE COALNE DEPCNT SOUNDG LIGHTS BOYLAT)

echo "== [3/4] Extracting + merging object classes (clipped to bbox) =="
rm -f charts.gpkg
for cell in "${CELLS[@]}"; do
  for C in "${CLASSES[@]}"; do
    ogr2ogr -f GPKG -update -append -skipfailures \
      -nln "$C" -nlt PROMOTE_TO_MULTI \
      -spat $BBOX \
      charts.gpkg "$cell" "$C" 2>/dev/null || true
  done
done

GEOJSON_ARGS=()
for C in "${CLASSES[@]}"; do
  if ogrinfo charts.gpkg "$C" >/dev/null 2>&1; then
    ogr2ogr -f GeoJSON "$C.geojson" charts.gpkg "$C"
    cnt=$(ogrinfo -so charts.gpkg "$C" | awk -F': ' '/Feature Count/{print $2}')
    echo "  layer $C: ${cnt:-0} features"
    GEOJSON_ARGS+=("$C.geojson")
  else
    echo "  layer $C: (none in area)"
  fi
done

if [ "${#GEOJSON_ARGS[@]}" -eq 0 ]; then echo "No features in bbox!"; exit 1; fi

echo "== [4/4] Tiling with tippecanoe =="
tippecanoe -o "$OUT/charts.mbtiles" --force \
  -Z"$MINZOOM" -z"$MAXZOOM" \
  --drop-densest-as-needed --extend-zooms-if-still-dropping --coalesce-densest-as-needed \
  -n "MyNavvy NOAA San Diego" \
  "${GEOJSON_ARGS[@]}"

echo "== Done =="
ls -lh "$OUT/charts.mbtiles"
