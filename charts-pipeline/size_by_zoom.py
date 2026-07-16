#!/usr/bin/env python3
"""Where the mbtiles bytes actually go, per zoom level."""
import sqlite3, sys
path = sys.argv[1] if len(sys.argv) > 1 else \
    "/mnt/d/Work/Programming/Android/MyNavvy/charts-pipeline/out/charts.mbtiles"
c = sqlite3.connect(path)
rows = list(c.execute(
    "select zoom_level, count(*), sum(length(tile_data)) from tiles group by zoom_level order by zoom_level"))
total = sum(r[2] for r in rows)
print(f"{'z':>3} {'tiles':>9} {'MB':>8} {'% of total':>11}   cumulative")
cum = 0
for z, n, b in rows:
    cum += b
    print(f"{z:>3} {n:>9,} {b/1048576:>8.1f} {100*b/total:>10.1f}%   {cum/1048576:>7.1f} MB")
print(f"\ntotal {total/1048576:.1f} MB")
for cap in (12, 13, 14, 15):
    upto = sum(b for z, n, b in rows if z <= cap)
    print(f"  if maxzoom were {cap}: {upto/1048576:.1f} MB")
