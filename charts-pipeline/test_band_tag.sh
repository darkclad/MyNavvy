#!/usr/bin/env bash
# Dry-run the band-tagging SQL on a single harbour cell before committing to a full extraction.
set -uo pipefail
cd /root/mynavvy_work
CELL=$(find . -name 'US5SANCD.000' | head -1)
[ -z "$CELL" ] && CELL=$(find . -name 'US5*.000' | head -1)
band=$(basename "$CELL" | cut -c3)
echo "cell=$CELL  band=$band"

export OGR_S57_OPTIONS="SPLIT_MULTIPOINT=ON,ADD_SOUNDG_DEPTH=ON,RETURN_PRIMITIVES=OFF,RETURN_LINKAGES=OFF,LNAM_REFS=OFF"
rm -f /tmp/bandtest.gpkg
ogr2ogr -f GPKG -nln DEPARE -nlt PROMOTE_TO_MULTI \
    -dialect sqlite -sql "SELECT *, $band AS INTU FROM \"DEPARE\"" \
    /tmp/bandtest.gpkg "$CELL" 2>&1 | head -3

echo "--- resulting layer ---"
ogrinfo -q -so /tmp/bandtest.gpkg DEPARE 2>/dev/null | grep -E 'Feature Count|Geometry:'
echo "--- INTU + DRVAL1 survived? ---"
ogrinfo -q /tmp/bandtest.gpkg -sql "SELECT INTU, DRVAL1, DRVAL2 FROM DEPARE LIMIT 3" 2>/dev/null | grep -E 'INTU|DRVAL'
echo "--- geometry non-null? ---"
ogrinfo -q /tmp/bandtest.gpkg -sql "SELECT count(*) AS with_geom FROM DEPARE WHERE geom IS NOT NULL" 2>/dev/null | grep with_geom
