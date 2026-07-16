#!/usr/bin/env python3
"""
Generate app/src/main/assets/style.json.

ENC usage bands (INTU: 1=overview .. 5=harbour) are generalisations of one another. Merged
together, the overview band's LNDARE paints straight across San Diego Bay. So the fills are
emitted band-by-band, COARSE FIRST, and within each band DEPARE (water) then LNDARE (land):
a finer band's water therefore overdraws a coarser band's land, while coarse data still fills
in where no finer survey exists (offshore).

Depth colours here are only defaults — MainActivity.applySafetyShading() rewrites the DEPARE
fill at runtime from the configured draft + under-keel margin.
"""
import json, sys

BANDS = [1, 2, 3, 4, 5, 6]

# Depth palette: a pure BATHYMETRIC blue ramp — deep water dark navy, lightening to pale blue as
# it shoals. Water shallower than the safety depth is the PALEST blue: still unmistakably water,
# never a yellow that reads as land (land is the only tan on the chart). The runtime shader
# (MainActivity.applySafetyShading) overrides this keyed on the real draft; here we bake a default
# safety depth of 2 m so the chart looks right before a profile is set.
DEEP    = "#1b5e91"   # deep water, dark navy
# Uncharted depth == the background/deep colour on purpose: over a 300 nm radius the coverage
# edge and any depth-less areas should blend into open sea, not show as a grey seam. Land, shoals
# and contours are what carry the information; open water reads as one uniform surface.
UNKNOWN = DEEP
DRIES   = "#7cc47f"   # DRVAL1 < 0: uncovers at low water (green, chart convention)
SHOAL   = "#cfe6f5"   # shallower than safety depth — palest blue, still water (not a land colour)
SAFE    = "#8fc0e2"   # just deeper than safety
MID     = "#4a90c2"
LAND    = "#f2e39c"
LAND_EDGE = "#c9b063"
DREDGED = "#e7c9e7"   # magenta tint, chart convention

_DEF_SAFETY = 2.0
DEPTH_FILL = [
    "step", ["to-number", ["get", "DRVAL1"], -999],
    UNKNOWN,
    -100, DRIES,
    0, SHOAL,
    _DEF_SAFETY, SAFE,
    _DEF_SAFETY * 2, MID,
    _DEF_SAFETY * 4, DEEP,
]

def band(n):
    """Match one usage band.

    Band 1 also swallows INTU=0, i.e. features from an OLDER chart file that predates band
    tagging. Without this the filters match nothing and the map renders with no land or water at
    all. Legacy charts then behave exactly as they used to (all bands flattened into one pass),
    which is wrong-but-recognisable rather than blank.
    """
    intu = ["to-number", ["get", "INTU"], 0]
    if n == 1:
        return ["any", ["==", intu, 1], ["==", intu, 0]]
    return ["==", intu, n]

layers = [
    {"id": "background-water", "type": "background", "paint": {"background-color": DEEP}}
]

# Fills, band-by-band COARSE -> FINE: the finer (larger-scale) band ALWAYS draws on top, whether
# it's land or water — exactly what an ECDIS does ("largest scale available wins"). This is the ONE
# correct rule and it handles both failure modes:
#   * marina slip water (fine DEPARE) overdraws the overview band's generalised land -> shows water;
#   * Harbor/Shelter Island (fine LNDARE, a man-made island the overview band charts as open water)
#     overdraws the coarse DEPARE -> shows land.
# Within a single band DEPARE and LNDARE are a gap-free, non-overlapping partition (S-57 skin of the
# earth), so their order inside the band is immaterial — only the cross-band coarse->fine order matters.
for b in BANDS:
    layers.append({
        "id": f"DEPARE-b{b}", "type": "fill", "source": "charts", "source-layer": "DEPARE",
        "filter": band(b), "paint": {"fill-color": DEPTH_FILL},
    })
    # Dredged channels (ship channel) get the SAME depth ramp as open water, so the channel reads as
    # one continuous water body from outside the bay to inside — no magenta seam.
    layers.append({
        "id": f"DRGARE-b{b}", "type": "fill", "source": "charts", "source-layer": "DRGARE",
        "filter": band(b), "paint": {"fill-color": DEPTH_FILL},
    })
    layers.append({
        "id": f"LNDARE-b{b}", "type": "fill", "source": "charts", "source-layer": "LNDARE",
        "filter": band(b),
        "paint": {"fill-color": LAND, "fill-outline-color": LAND_EDGE},
    })

# Lines/points: only draw a band where it's the appropriate scale, else the overview coastline
# scribbles across the harbour chart.
BAND_ZOOM = {1: (2, 6), 2: (2, 8), 3: (6, 11), 4: (9, 13), 5: (11, 22), 6: (13, 22), 0: (2, 22)}

def zoomed(b, extra=None):
    lo, hi = BAND_ZOOM.get(b, (2, 22))
    d = {"minzoom": lo, "maxzoom": hi, "filter": band(b)}
    if extra:
        d.update(extra)
    return d

for b in BANDS:
    layers.append(dict({
        # Depth contours must read on BOTH dark deep water and light shoals -> semi-transparent white.
        "id": f"DEPCNT-b{b}", "type": "line", "source": "charts", "source-layer": "DEPCNT",
        "paint": {"line-color": "#ffffff", "line-width": 0.6, "line-opacity": 0.45},
    }, **zoomed(b)))
    layers.append(dict({
        "id": f"COALNE-b{b}", "type": "line", "source": "charts", "source-layer": "COALNE",
        "paint": {"line-color": "#6b5a28", "line-width": 1.2},
    }, **zoomed(b)))

for b in BANDS:
    lo, hi = BAND_ZOOM.get(b, (2, 22))
    layers.append({
        # Soundings sit mostly in shoal (light) water; a dark dot with a faint light halo keeps
        # them legible if they fall on the dark deep fill too.
        "id": f"SOUNDG-b{b}", "type": "circle", "source": "charts", "source-layer": "SOUNDG",
        "filter": band(b), "minzoom": max(lo, 12), "maxzoom": hi,
        "paint": {
            "circle-radius": 1.6, "circle-color": "#173042",
            "circle-stroke-width": 0.6, "circle-stroke-color": "#ffffff", "circle-stroke-opacity": 0.5,
        },
    })

# Navaids: draw all bands, they're sparse and you always want them.
layers.append({
    "id": "navaids", "type": "circle", "source": "charts", "source-layer": "LIGHTS",
    "paint": {"circle-radius": 3.5, "circle-color": "#d21f1f",
              "circle-stroke-width": 1, "circle-stroke-color": "#ffffff"},
})
layers.append({
    "id": "buoys-lateral", "type": "circle", "source": "charts", "source-layer": "BOYLAT",
    "paint": {"circle-radius": 3, "circle-color": "#1f7a1f",
              "circle-stroke-width": 1, "circle-stroke-color": "#ffffff"},
})

style = {
    "version": 8,
    "name": "MyNavvy NOAA (band-aware)",
    "sources": {
        "charts": {
            "type": "vector",
            "tiles": ["http://127.0.0.1:8123/tiles/{z}/{x}/{y}.pbf"],
            # Overwritten at runtime from the MBTiles' real zoom range (MbTilesServer).
            "minzoom": 2,
            "maxzoom": 16,
        }
    },
    "layers": layers,
}

out = sys.argv[1] if len(sys.argv) > 1 else \
    "/mnt/d/Work/Programming/Android/MyNavvy/app/src/main/assets/style.json"
with open(out, "w") as f:
    json.dump(style, f, indent=2)
print(f"wrote {out}: {len(layers)} layers ({len(BANDS)} bands x fills, band-scoped lines)")
