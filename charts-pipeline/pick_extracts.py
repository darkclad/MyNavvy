#!/usr/bin/env python3
"""
Pick the smallest set of Geofabrik OSM extracts covering a bbox.

  pick_extracts.py <geofabrik-index-v1.json> <XMIN> <YMIN> <XMAX> <YMAX>

Output: one line per extract:  ID \t PBF_URL

Algorithm: take every extract whose polygon intersects the bbox, then drop any extract that
geometrically CONTAINS another hit (>=97% of the smaller one's area) — the index's `parent`
field is useless for this (us/washington's parent is 'north-america', while the overlapping
'us' and 'us-west' bundles are siblings, verified 2026-07-16). What survives is the smallest
tiling: a cross-border bbox (San Diego + Baja) yields e.g. `socal`+`mexico`+neighbour states.
"""
import json, sys
from osgeo import ogr

path, xmin, ymin, xmax, ymax = sys.argv[1], *[float(v) for v in sys.argv[2:6]]

ring = ogr.Geometry(ogr.wkbLinearRing)
for x, y in [(xmin, ymin), (xmax, ymin), (xmax, ymax), (xmin, ymax), (xmin, ymin)]:
    ring.AddPoint_2D(x, y)
bbox = ogr.Geometry(ogr.wkbPolygon)
bbox.AddGeometry(ring)

idx = json.load(open(path, encoding="utf-8"))
hits = {}      # id -> (geom, url, area)
for feat in idx.get("features", []):
    props = feat.get("properties", {})
    fid = props.get("id")
    url = (props.get("urls") or {}).get("pbf")
    if not fid or not url:
        continue
    try:
        geom = ogr.CreateGeometryFromJson(json.dumps(feat["geometry"]))
    except Exception:
        continue
    if geom is None or not geom.Intersects(bbox):
        continue
    hits[fid] = (geom, url, geom.GetArea())

def covers(big, small):
    """big contains >=97% of small's area (tolerates boundary jitter in the index polygons)."""
    try:
        inter = big.Intersection(small)
        return inter is not None and small.GetArea() > 0 and \
            inter.GetArea() >= 0.97 * small.GetArea()
    except Exception:
        return False

drop = set()
ids = list(hits)
for a in ids:
    for b in ids:
        if a == b or a in drop:
            continue
        ga, _, aa = hits[a]
        gb, _, ab = hits[b]
        if aa > ab and covers(ga, gb):
            drop.add(a)        # a is a bigger bundle containing b -> prefer the smaller b
            break

picked = sorted(fid for fid in hits if fid not in drop)
if not picked:
    sys.exit("no Geofabrik extract intersects the bbox")
for fid in picked:
    print(f"{fid}\t{hits[fid][1]}")
print(f"picked {len(picked)} extract(s): {', '.join(picked)}", file=sys.stderr)
