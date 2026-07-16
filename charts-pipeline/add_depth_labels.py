#!/usr/bin/env python3
"""Patch app/src/main/assets/style.json to LABEL the depth data:
  - SOUNDG: replace the plain dots with the sounding value as a number (feet).
  - DEPCNT: keep the contour lines (a touch bolder) and add the contour depth value (feet)
    as line-placed labels.
Depths in the NOAA ENC tiles are METRES (DEPCNT.VALDCO, SOUNDG.DEPTH); we show FEET to match the
app's default depth unit. Idempotent — safe to re-run. Chart + Navigation share this style, so the
labels show on both screens.
"""
import json, os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
STYLE = sys.argv[1] if len(sys.argv) > 1 else os.path.join(
    HERE, "..", "app", "src", "main", "assets", "style.json")

M_TO_FT = 3.28084
def feet(attr):  # metres attribute -> integer-feet string for a text-field
    return ["to-string", ["round", ["*", ["to-number", ["get", attr], 0], M_TO_FT]]]

s = json.load(open(STYLE))
layers = s["layers"]
out = []
added = []
for L in layers:
    lid = L.get("id", "")
    if lid.startswith("SOUNDG-lbl") or lid.startswith("DEPCNT-lbl"):
        continue  # drop previous run's labels so this stays idempotent
    if lid.startswith("SOUNDG-b"):
        # Replace the dot with a number label carrying the same band filter + zoom range.
        b = lid.split("-b")[1]
        out.append({
            "id": f"SOUNDG-lbl-b{b}", "type": "symbol", "source": "charts", "source-layer": "SOUNDG",
            "filter": L.get("filter"), "minzoom": L.get("minzoom", 12), "maxzoom": L.get("maxzoom", 22),
            "layout": {
                "text-field": feet("DEPTH"), "text-font": ["NotoSans-Regular"], "text-size": 11,
                "text-allow-overlap": False, "text-optional": True, "text-padding": 2,
            },
            "paint": {"text-color": "#0d2230", "text-halo-color": "#ffffff", "text-halo-width": 1.3},
        })
        added.append(f"SOUNDG-lbl-b{b}")
        continue  # (dot layer removed)
    out.append(L)
    if lid.startswith("DEPCNT-b"):
        # Bolder contour + a line-placed depth label just after it.
        L.setdefault("paint", {})
        L["paint"]["line-opacity"] = 0.7
        L["paint"]["line-width"] = 0.9
        b = lid.split("-b")[1]
        added.append({
            "id": f"DEPCNT-lbl-b{b}", "type": "symbol", "source": "charts", "source-layer": "DEPCNT",
            "filter": L.get("filter"), "minzoom": max(L.get("minzoom", 2), 8), "maxzoom": L.get("maxzoom", 22),
            "layout": {
                "symbol-placement": "line", "text-field": feet("VALDCO"), "text-font": ["NotoSans-Regular"],
                "text-size": 10, "symbol-spacing": 320, "text-max-angle": 35,
                "text-allow-overlap": False, "text-optional": True,
            },
            "paint": {"text-color": "#2a6ea0", "text-halo-color": "#ffffff", "text-halo-width": 1.4},
        })

# Append the DEPCNT label symbol layers on top (dict entries collected in `added`).
out += [a for a in added if isinstance(a, dict)]
s["layers"] = out
json.dump(s, open(STYLE, "w"), indent=2)
print(f"patched {STYLE}: {sum(1 for a in added)} depth-label layers, "
      f"{len(out)} layers total (feet labels for SOUNDG.DEPTH + DEPCNT.VALDCO)")
