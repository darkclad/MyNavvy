#!/usr/bin/env python3
"""Report what a charts.mbtiles actually contains: zoom range, tile count, and — the thing that
keeps silently breaking — whether each vector layer still carries its depth attributes."""
import sqlite3, json, sys

path = sys.argv[1] if len(sys.argv) > 1 else \
    "/mnt/d/Work/Programming/Android/MyNavvy/charts-pipeline/out/charts.mbtiles"
c = sqlite3.connect(path)
m = dict(c.execute("select name,value from metadata"))

print("format ", m.get("format"), "| minzoom", m.get("minzoom"), "| maxzoom", m.get("maxzoom"))
print("bounds ", m.get("bounds"))
print("tiles  ", c.execute("select count(*) from tiles").fetchone()[0])
print("zooms  ", [z for (z,) in c.execute(
    "select distinct zoom_level from tiles order by zoom_level")])
print()

WANT = {"DEPARE": "DRVAL1", "DRGARE": "DRVAL1", "DEPCNT": "VALDCO", "SOUNDG": "DEPTH"}
for l in json.loads(m.get("json", "{}")).get("vector_layers", []):
    lid = l["id"]
    fields = l.get("fields", {})
    need = WANT.get(lid)
    status = ""
    if need:
        status = f"  {need}: {'PRESENT' if need in fields else '*** MISSING ***'}"
    print(f"  {lid:<8} z{l.get('minzoom')}-{l.get('maxzoom')}  fields={len(fields)}{status}")
