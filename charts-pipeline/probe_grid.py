#!/usr/bin/env python3
"""Sample routing_grid.png at lat/lon and decode it the way the app does.
Ground truth for each point comes from OpenStreetMap, so this either confirms or refutes the
usage-band fix rather than just describing the raster."""
import json, sys
from osgeo import gdal

base = "/mnt/d/Work/Programming/Android/MyNavvy/charts-pipeline/out"
meta = json.load(open(f"{base}/routing_grid.json"))
arr = gdal.Open(f"{base}/routing_grid.png").ReadAsArray()
H, W = meta["height"], meta["width"]

def sample(lat, lon):
    if not (meta["south"] <= lat <= meta["north"] and meta["west"] <= lon <= meta["east"]):
        return "OUTSIDE", None
    px = int((lon - meta["west"]) / (meta["east"] - meta["west"]) * (W - 1))
    py = int((meta["north"] - lat) / (meta["north"] - meta["south"]) * (H - 1))
    v = int(arr[py, px])
    if v == 0:
        return "LAND", None
    if v == 255:
        return "UNKNOWN(deep)", None
    if v == 254:
        return "WATER(depth unknown)", None
    return "WATER", (v - 1) / 253.0 * meta["depthMaxM"]

POINTS = [
    (32.6925, -117.1600, "under Coronado Bridge", "WATER"),
    (32.7050, -117.1850, "channel N of North Island", "WATER"),
    (32.6800, -117.1900, "bay entrance channel", "WATER"),
    # 32.710,-117.165 is downtown, NOT the waterfront: all fine bands say LNDARE.
    (32.7100, -117.1650, "downtown (inland)", "LAND"),
    (32.6900, -117.2000, "NAS North Island", "LAND"),
    (32.7000, -117.1750, "Coronado", "LAND"),
    (32.6600, -117.2500, "open ocean SW", "WATER"),
    # Mission Bay — same band trap: band 1 paints it land, bands 3-5 have real depth areas.
    (32.7852, -117.2458, "Mission Bay: Sail Bay", "WATER"),
    # NB 32.7706,-117.2276 looks like open water on a small-scale map but the harbour band puts
    # LNDARE within 20 m of it (Crown Point shore). Use a point the ENC agrees is water.
    (32.7700, -117.2300, "Mission Bay: main basin", "WATER"),
    (32.7654, -117.2325, "Mission Bay: Vacation Isle", "LAND"),
    (32.7617, -117.2108, "Mission Bay: Fiesta Island", "LAND"),
]

print(f"{'point':<30} {'expected':<7} {'grid says':<22} depth")
ok = bad = 0
for lat, lon, name, expect in POINTS:
    verdict, depth = sample(lat, lon)
    isw = verdict.startswith("WATER") or verdict.startswith("UNKNOWN")
    match = (isw and expect == "WATER") or (verdict == "LAND" and expect == "LAND")
    ok, bad = (ok + 1, bad) if match else (ok, bad + 1)
    d = f"{depth:.1f} m" if depth is not None else ""
    print(f"{name:<30} {expect:<7} {verdict:<22} {d}   {'OK' if match else '*** WRONG ***'}")
print(f"\n{ok} correct, {bad} wrong")
sys.exit(1 if bad else 0)
