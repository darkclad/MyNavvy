#!/usr/bin/env bash
#
# Build a complete MyNavvy region package (charts + routing grid + descriptor [+ basemap])
# for a US zipcode (or lat,lon) and radius. Runs in WSL as root:
#
#   wsl -u root bash -c "sed 's/\r$//' /mnt/d/Work/Programming/Android/MyNavvy/charts-pipeline/build_region.sh > /tmp/br.sh && bash /tmp/br.sh 92106 300nm --name 'SoCal' --id socal"
#
# Output: charts-pipeline/out/regions/<id>/{charts.mbtiles, routing_grid.png, routing_grid.json,
#         region.json [, basemap.mbtiles]}
#
# Replaces the single-region build_charts_wsl.sh + build_routing_grid_wsl.sh flow:
#   - cells picked from the NOAA product catalog by circle intersection (multi-state radii work)
#   - per-cell zips cached in /root/mynavvy_work/cache/enc (If-Modified-Since re-download)
#   - per-region work dirs under /root/mynavvy_work/regions/<id> (no more clobbering)
#   - routing grid covers the WHOLE region bbox at adaptive resolution (<=3000 px/side,
#     Android must decode the PNG as one bitmap)
#   - every NOAA feature gets SYN=0; the synthetic foreign tier (make_synthetic.py) appends
#     SYN=1 features. Rasterize synthetic FIRST so any real NOAA band overwrites it.
#
# ENC USAGE BANDS (unchanged from build_charts_wsl.sh — the thing that bit us):
#   NOAA ships six bands; each is a GENERALISATION of the finer ones. Every feature is tagged
#   with its band as INTU (3rd char of the cell name); fills draw band-by-band coarse->fine.
set -euo pipefail

PIPE=/mnt/d/Work/Programming/Android/MyNavvy/charts-pipeline
BASE_URL="https://www.charts.noaa.gov/ENCs"
WORK=/root/mynavvy_work
CACHE=$WORK/cache
MINZOOM="${MINZOOM:-2}"
MAXZOOM="${MAXZOOM:-16}"
GRID_MAX_PX="${GRID_MAX_PX:-3000}"   # routing_grid.png max pixels per side (old-tablet bitmap cap)

# ---------- args ----------
POS=(); NAME=""; ID=""; BASEMAP=1
while [ $# -gt 0 ]; do case "$1" in
  --name) NAME="$2"; shift 2;;
  --id) ID="$2"; shift 2;;
  --no-basemap) BASEMAP=0; shift;;
  *) POS+=("$1"); shift;;
esac; done
CENTER="${POS[0]:?usage: build_region.sh <zip|lat,lon> <radius[nm|mi]> [--name X] [--id x] [--no-basemap]}"
RADIUS="${POS[1]:?radius required, e.g. 300nm or 250mi}"
[ -n "$NAME" ] || NAME="Region $CENTER $RADIUS"
[ -n "$ID" ] || ID=$(echo "$NAME" | tr '[:upper:]' '[:lower:]' | tr -cs 'a-z0-9' '-' | sed 's/^-*//;s/-*$//')
RDIR=$WORK/regions/$ID
OUT=$PIPE/out/regions/$ID
mkdir -p "$CACHE/enc" "$RDIR" "$OUT"
echo "== region id=$ID name='$NAME' center=$CENTER radius=$RADIUS =="

# ---------- [1/8] toolchain ----------
if ! command -v tippecanoe >/dev/null 2>&1; then
  DEBIAN_FRONTEND=noninteractive apt-get install -y build-essential libsqlite3-dev zlib1g-dev git >/dev/null
  rm -rf "$WORK/tippecanoe-src" && git clone --depth 1 https://github.com/felt/tippecanoe "$WORK/tippecanoe-src" >/dev/null 2>&1
  make -C "$WORK/tippecanoe-src" -j"$(nproc)" >/dev/null && make -C "$WORK/tippecanoe-src" install >/dev/null
fi
python3 -c "from osgeo import gdal" 2>/dev/null || \
  DEBIAN_FRONTEND=noninteractive apt-get install -y python3-gdal python3-numpy >/dev/null

# ---------- [2/8] center + bbox ----------
# GeoNames US postal file (CC-BY 4.0) — the Census ZCTA gazetteer URLs 404 as of 2026-07.
GAZ=$CACHE/geonames_US.txt
if [[ "$CENTER" =~ ^[0-9]{5}$ ]] && [ ! -s "$GAZ" ]; then
  curl -fsSL --retry 3 -o "$CACHE/geonames_US.zip" "https://download.geonames.org/export/zip/US.zip"
  unzip -p "$CACHE/geonames_US.zip" US.txt > "$GAZ"; rm -f "$CACHE/geonames_US.zip"
  echo "gazetteer: GeoNames US ($(wc -l < "$GAZ") rows)"
  [ -s "$GAZ" ] || { echo "FATAL: could not download GeoNames US postal file"; exit 1; }
fi
eval "$(python3 "$PIPE/resolve_center.py" "$CENTER" "$RADIUS" "$GAZ")"
echo "center=($LAT,$LON) r=${RADIUS_NM}nm bbox=($XMIN,$YMIN)->($XMAX,$YMAX)"

# ---------- [3/8] select + download cells ----------
CAT=$CACHE/ENCProdCat_19115.xml
if [ ! -s "$CAT" ] || [ -z "$(find "$CAT" -mmin -1440 2>/dev/null)" ]; then
  echo "refreshing ENC product catalog..."
  curl -fsSL --retry 3 -o "$CAT" "$BASE_URL/ENCProdCat_19115.xml"
fi
python3 "$PIPE/select_cells.py" "$CAT" "$LAT" "$LON" "$RADIUS_NM" > "$RDIR/cells.tsv"
NCELLS=$(wc -l < "$RDIR/cells.tsv")
[ "$NCELLS" -gt 0 ] || { echo "no ENC cells intersect this region"; exit 1; }
echo "downloading $NCELLS cell zips (cached, 8-way)..."
dl_cell() {
  local url="$1" f="$CACHE/enc/$(basename "$1")"
  if [ -s "$f" ]; then curl -fsSL --retry 3 -z "$f" -o "$f" "$url" || true
  else curl -fsSL --retry 3 -o "$f" "$url" || echo "WARN: failed $url" >&2; fi
}
export -f dl_cell; export CACHE
cut -f2 "$RDIR/cells.tsv" | xargs -P8 -n1 -I{} bash -c 'dl_cell "$1"' _ {}

rm -rf "$RDIR/ENC_ROOT"
while IFS=$'\t' read -r cell url rev _rest; do
  f="$CACHE/enc/$(basename "$url")"
  [ -s "$f" ] && unzip -qo "$f" -d "$RDIR" || echo "WARN: missing $cell"
done < "$RDIR/cells.tsv"
mapfile -t CELLS < <(find "$RDIR/ENC_ROOT" -name '*.000' | sort)
echo "unzipped ${#CELLS[@]} ENC cells"
[ "${#CELLS[@]}" -gt 0 ] || { echo "no cells unzipped"; exit 1; }

# ---------- [4/8] extract + merge (INTU band tag + SYN=0) ----------
export OGR_S57_OPTIONS="SPLIT_MULTIPOINT=ON,ADD_SOUNDG_DEPTH=ON,RETURN_PRIMITIVES=OFF,RETURN_LINKAGES=OFF,LNAM_REFS=OFF"
CLASSES=(DEPARE DRGARE LNDARE COALNE DEPCNT SOUNDG LIGHTS BOYLAT)
GPKG=$RDIR/charts.gpkg
rm -f "$GPKG"
echo "== extracting ${#CELLS[@]} cells x ${#CLASSES[@]} classes =="
for cell in "${CELLS[@]}"; do
  band=$(basename "$cell" | cut -c3)          # US5SANCD.000 -> 5
  case "$band" in [1-6]) ;; *) band=0 ;; esac
  for C in "${CLASSES[@]}"; do
    ogr2ogr -f GPKG -update -append -skipfailures \
      -nln "$C" -nlt PROMOTE_TO_MULTI \
      -spat "$XMIN" "$YMIN" "$XMAX" "$YMAX" \
      -clipsrc spat_extent \
      -dialect sqlite -sql "SELECT *, $band AS INTU, 0 AS SYN FROM \"$C\"" \
      "$GPKG" "$cell" 2>/dev/null || true
  done
done
echo "-- band coverage sanity check (DEPARE) --"
ogrinfo -q "$GPKG" -sql "SELECT INTU, count(*) AS n FROM DEPARE GROUP BY INTU" 2>/dev/null \
  | grep -E 'INTU|n \(' | paste - - || true

# ---------- [5/8] synthetic foreign tier (Mexico/Canada) ----------
# make_synthetic.py appends SYN=1 features (DEPARE/DEPCNT/LNDARE/COALNE/BOYLAT/LIGHTS + DATLIM)
# into charts.gpkg for the part of the bbox outside NOAA coverage. No-op inside US-only regions.
SYN_MX=0; SYN_CA=0; SYN_ARGS=()
if [ -f "$PIPE/build_synthetic.sh" ] && [ "${SYNTHETIC:-auto}" != "off" ]; then
  sed 's/\r$//' "$PIPE/build_synthetic.sh" > /tmp/bsyn.sh
  bash /tmp/bsyn.sh "$RDIR" "$XMIN" "$YMIN" "$XMAX" "$YMAX" || echo "WARN: synthetic tier failed, continuing with NOAA-only"
  [ -f "$RDIR/synthetic_meta" ] && . "$RDIR/synthetic_meta"   # sets SYN_MX/SYN_CA/GEBCO_VER/NONNA_VER
  [ "$SYN_MX" = 1 ] && SYN_ARGS+=(--synthetic MX ${GEBCO_VER:+--gebco "$GEBCO_VER"})
  [ "$SYN_CA" = 1 ] && SYN_ARGS+=(--synthetic CA ${NONNA_VER:+--nonna "$NONNA_VER"})
fi

# ---------- [6/8] GeoJSON + tippecanoe ----------
GEOJSON_ARGS=()
ALL_CLASSES=("${CLASSES[@]}" DATLIM)
for C in "${ALL_CLASSES[@]}"; do
  if ogrinfo "$GPKG" "$C" >/dev/null 2>&1; then
    rm -f "$RDIR/$C.geojson"
    ogr2ogr -f GeoJSON "$RDIR/$C.geojson" "$GPKG" "$C"
    n=$(ogrinfo -so "$GPKG" "$C" | awk -F': ' '/Feature Count/{print $2}')
    echo "  $C: ${n:-0} features"
    GEOJSON_ARGS+=("$RDIR/$C.geojson")
  fi
done
[ "${#GEOJSON_ARGS[@]}" -gt 0 ] || { echo "no features in bbox"; exit 1; }

echo "== tiling with tippecanoe (z$MINZOOM-z$MAXZOOM) =="
# NEVER --coalesce-densest-as-needed, ALWAYS --no-tiny-polygon-reduction: both merge adjacent
# polygons and a merged polygon keeps only ONE feature's attributes -> DEPARE loses DRVAL1/INTU
# and depth shading silently disappears.
tippecanoe -o "$OUT/charts.mbtiles" --force \
  -Z"$MINZOOM" -z"$MAXZOOM" \
  --drop-densest-as-needed \
  --no-tiny-polygon-reduction \
  --maximum-tile-bytes=1000000 \
  -n "MyNavvy $NAME" \
  "${GEOJSON_ARGS[@]}"

# ---------- [7/8] routing grid (whole region bbox, adaptive resolution) ----------
RES=$(python3 -c "print(max(0.0008, ($XMAX-($XMIN))/$GRID_MAX_PX, ($YMAX-($YMIN))/$GRID_MAX_PX))")
echo "== routing grid RES=${RES}deg =="
rm -f "$RDIR/class.tif" "$RDIR/depth.tif"
TE="-te $XMIN $YMIN $XMAX $YMAX -tr $RES $RES"
gdal_rasterize -q -burn 0 -l LNDARE $TE -init 0 -ot Byte -where "1=0" "$GPKG" "$RDIR/class.tif"
gdal_rasterize -q -burn 0 -l DEPARE $TE -init -9999 -a_nodata -9999 -ot Float32 -where "1=0" "$GPKG" "$RDIR/depth.tif"
# synthetic (SYN=1) first: any real NOAA band overwrites it
gdal_rasterize -q -burn 2 -l DEPARE -where "SYN=1" "$GPKG" "$RDIR/class.tif" 2>/dev/null || true
gdal_rasterize -q -burn 1 -l LNDARE -where "SYN=1" "$GPKG" "$RDIR/class.tif" 2>/dev/null || true
gdal_rasterize -q -a DRVAL1 -l DEPARE -where "SYN=1" "$GPKG" "$RDIR/depth.tif" 2>/dev/null || true
# NOAA bands coarse -> fine (fine wins; the "yellow bay" lesson)
for B in 1 2 3 4 5 6; do
  gdal_rasterize -q -burn 2 -l DEPARE -where "INTU=$B AND SYN=0" "$GPKG" "$RDIR/class.tif" || true
  gdal_rasterize -q -burn 1 -l LNDARE -where "INTU=$B AND SYN=0" "$GPKG" "$RDIR/class.tif" || true
  gdal_rasterize -q -a DRVAL1 -l DEPARE -where "INTU=$B AND SYN=0" "$GPKG" "$RDIR/depth.tif" || true
done
python3 "$PIPE/make_routing_grid.py" "$RDIR/class.tif" "$RDIR/depth.tif" "$OUT/routing_grid.png" "$OUT/routing_grid.json"

# ---------- [8/8] basemap + descriptor ----------
if [ "$BASEMAP" = 1 ] && [ -f "$PIPE/build_basemap.sh" ]; then
  sed 's/\r$//' "$PIPE/build_basemap.sh" > /tmp/bbm.sh
  bash /tmp/bbm.sh "$XMIN" "$YMIN" "$XMAX" "$YMAX" "$OUT/basemap.mbtiles" || \
    echo "WARN: basemap build failed (optional asset, continuing)"
fi

ZIPARG=(); [[ "$CENTER" =~ ^[0-9]{5}$ ]] && ZIPARG=(--zip "$CENTER")
# NB equals form (--lat=...) throughout: values are often negative and argparse would
# otherwise read them as option flags.
python3 "$PIPE/make_region_json.py" "$OUT/region.json" \
  --id "$ID" --name "$NAME" "${ZIPARG[@]}" --radius-nm="$RADIUS_NM" \
  --lat="$LAT" --lon="$LON" --bounds="$XMIN,$YMIN,$XMAX,$YMAX" \
  --noaa-cells "$NCELLS" "${SYN_ARGS[@]}"

echo "== done: $OUT =="
ls -lh "$OUT"
python3 "$PIPE/inspect_mbtiles.py" "$OUT/charts.mbtiles" || true
