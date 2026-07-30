#!/usr/bin/env python3
"""
Emit the region.json descriptor for a built region package.

  make_region_json.py <out.json> --id socal --name "SoCal" --zip 92106 --radius-nm 300 \
      --lat 32.72 --lon -117.24 --bounds W,S,E,N --noaa-cells 378 \
      [--synthetic MX --synthetic CA] [--gebco 2025] [--nonna 10] [--osm-extract socal ...]

The app's RegionStore reads this file verbatim; keep the schema in sync with Region.kt.
"""
import argparse, json, sys
from datetime import datetime, timezone

p = argparse.ArgumentParser()
p.add_argument("out")
p.add_argument("--id", required=True)
p.add_argument("--name", required=True)
p.add_argument("--zip", default=None)
p.add_argument("--radius-nm", type=float, required=True)
p.add_argument("--lat", type=float, required=True)
p.add_argument("--lon", type=float, required=True)
p.add_argument("--bounds", required=True, help="W,S,E,N")
p.add_argument("--noaa-cells", type=int, default=0)
p.add_argument("--synthetic", action="append", default=[], choices=["MX", "CA"])
p.add_argument("--gebco", default=None)
p.add_argument("--nonna", default=None)
p.add_argument("--osm-extract", action="append", default=[])
a = p.parse_args()

w, s, e, n = [float(x) for x in a.bounds.split(",")]
doc = {
    "id": a.id,
    "name": a.name,
    "zip": a.zip,
    "radiusNm": a.radius_nm,
    "center": {"lat": a.lat, "lon": a.lon},
    "bounds": {"west": w, "south": s, "east": e, "north": n},
    "sources": {
        "noaaCells": a.noaa_cells,
        "syntheticMX": "MX" in a.synthetic,
        "syntheticCA": "CA" in a.synthetic,
        "gebco": a.gebco,
        "nonna": a.nonna,
        "osmExtracts": a.osm_extract,
    },
    "generatedUtc": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
}
json.dump(doc, open(a.out, "w"), indent=2)
print(f"wrote {a.out}", file=sys.stderr)
