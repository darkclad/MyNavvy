# Charts & offline data

The APK ships only code. All chart/routing data is built by `charts-pipeline/` into **region
packages** and either pushed over adb (dev) or downloaded by the app itself (field tablet).

## Region packages

A *region* is a self-contained chart set built for a circle around a point (usually a US
zipcode), e.g. "SoCal — San Diego 300 nm". Each region directory holds:

```
region.json          descriptor: id, name, bounds, center, radius, data sources
charts.mbtiles       vector chart tiles (NOAA ENC + synthetic foreign tier)
routing_grid.png/json  draft-aware routing mask over the whole region bbox
basemap.mbtiles      optional OSM land basemap seed (Planetiler/OpenMapTiles)
```

## The pipeline (`charts-pipeline/`)

WSL toolchain. One command builds a complete region package:

```
build_region.sh <zip|lat,lon> <radius[nm|mi]> [--name X] [--id x] [--no-basemap]
# e.g.  build_region.sh 92106 300nm --name 'SoCal' --id socal
```

```
zip/lat,lon ─▶ resolve_center.py (GeoNames US postal file, cached)
NOAA product catalog ─▶ select_cells.py (cells intersecting the circle) ─▶ per-cell zips (cached)
                       ├─▶ ogr2ogr (S-57 → charts.gpkg, INTU band + SYN=0 tags, clipped to bbox)
                       ├─▶ make_synthetic.py  ── synthetic foreign tier (see below)
                       ├─▶ tippecanoe ──▶ out/regions/<id>/charts.mbtiles
                       ├─▶ rasterize  ──▶ out/regions/<id>/routing_grid.png + .json
                       ├─▶ build_basemap.sh (Geofabrik → osmium → Planetiler) ──▶ basemap.mbtiles
                       └─▶ make_region_json.py ──▶ region.json
```

- **Rendered S-57 layers:** DEPARE, DRGARE, LNDARE, COALNE, DEPCNT, SOUNDG, LIGHTS, BOYLAT
  (+ DATLIM, the official-data-limit line).
- **Usage bands:** ENC bands (overview…harbour) are generalisations of one another, so the
  style draws fills band-by-band, *coarse first* — a finer band's water overdraws a coarser
  band's land, while coarse data still covers offshore areas with no fine survey.
- **Routing grid resolution is adaptive**: `RES = max(0.0008°, span/3000px)` so the PNG stays
  decodable as a single bitmap on the old tablet regardless of region size.
- **Style:** `app/src/main/assets/style.json` is hand-curated (basemap layers, labels,
  DATLIM); `make_style.py` now REFUSES to overwrite it (regeneration would strip those).
  `add_depth_labels.py` label patching stays idempotent.
- Shared caches in WSL `/root/mynavvy_work/cache/` (ENC cells, product catalog, GEBCO grid,
  OSM land polygons, GeoNames zips, Geofabrik index, planetiler jar); per-region work dirs
  under `/root/mynavvy_work/regions/<id>/`.

## Synthetic foreign tier (Mexico / Canada)

No free official ENCs exist outside US waters (CHS and SEMAR both sell theirs; NGA DNC is
gov-only). When a region's circle crosses NOAA's coverage edge, `make_synthetic.py` fills
the gap from free sources, tagged `SYN=1` / `INTU=2`:

| What | Mexico (and generic) | Canada |
|---|---|---|
| Bathymetry → DEPARE bands + DEPCNT | GEBCO global 15″ grid (~450 m) | CHS **NONNA-10** via WCS (10 m, patchy) over a GEBCO underlay |
| Coastline → LNDARE/COALNE | OSM land polygons | OSM land polygons |
| Buoys/lights → BOYLAT/LIGHTS | OSM seamarks (Overpass) | OSM seamarks (Overpass) |

Band edges: 0/2/5/10/20/50/100/200/500/1000/2000 m (`DRVAL1`/`DRVAL2` like real DEPARE), so
safety shading, the depth HUD and the router work unchanged. **Marking:** the chart draws a
dashed magenta DATLIM line at the NOAA coverage edge; the depth HUD source reads `syn`; a
map-tap shows "⚠ SYNTHETIC (GEBCO/NONNA) — not survey data". NONNA WCS specifics (verified):
coverage `nonna__NONNA 10 Coverage`, EPSG:3857, subset axes `x`/`y`, scale axes `i`/`j`.

## Publishing & the v2 manifest

`publish-mynavvy-data.ps1 -Region <id>` copies `out/regions/<id>/` to the dist server and
**upserts** that region into `data.json` (`-Remove <id>` delists; `-List` shows the catalog):

```json
{ "package": "com.dvladi.mynavvy", "manifestVersion": 2,
  "regions": [ { "id": "socal", "name": "SoCal", "bounds": {...}, "center": {...},
                 "synthetic": ["MX"], "assets": [ {"name": "charts.mbtiles",
                 "url": ".../regions/socal/charts.mbtiles", "sha256": "...", "size": 0} ] } ] }
```

## Multi-region on device — `Regions` + `DataAssets`

- Installed regions live in `files/regions/<id>/`; `region.json` is the marker of a usable
  install. **Active region**: manual pin (Configuration → Charts region) wins; otherwise
  auto-by-GPS switches when the boat sits inside another installed region for 60 s.
  Switching reloads via `recreate()` (servers stopped in onDestroy, reopened on the new
  region's files). Camera home = active region center.
- First run is still **"no charts, no app"**: the blocking overlay lists published regions
  and requires at least one download. Updates (sha drift) arrive via the same top banner.
- **Migration:** a pre-region install (files directly in `files/`) is moved into
  `regions/legacy-socal/` with a synthesized `region.json` — no re-download at sea.
- Downloads are hash-verified to `.part` files with `<name>.sha` markers, exactly as before.

## Offline tile serving — `MbTilesServer`

A tiny localhost HTTP server (NanoHTTPD) serves tiles straight out of the MBTiles SQLite
files: MapLibre's style points at `http://127.0.0.1:<port>/tiles/{z}/{x}/{y}.pbf`.
MBTiles rows are TMS scheme; the server flips to XYZ (`tms_y = 2^z − 1 − y`).

**Basemap read-through cache ("grow as you cruise"):** the OSM land basemap starts from the
active region's seed `basemap.mbtiles`. When online, a tile miss is fetched from the remote
tile server and *persisted into a cache MBTiles* (global, shared across regions) before
being served. Chart tiles never do this; they are complete offline per region.

## Routing grid

`routing_grid.png` (+ `.json` georeferencing) encodes draft-aware navigability, north-up:

| Pixel | Meaning |
|---|---|
| `0` | land — blocked |
| `255` | unknown / deep — navigable |
| `1..254` | charted depth: `depth_m = (v−1)/253 × 50` |

Synthetic (SYN=1) depth areas are rasterized FIRST, then NOAA bands coarse→fine, so real
survey data always wins — and a Baja route now sees real depths instead of "unknown".
[RoutingGrid](../app/src/main/java/com/dvladi/mynavvy/RoutingGrid.kt) samples it in-app;
navigability depends on the boat's draft + margin (`BoatProfile`) at query time.

## Weather & tide data

[WeatherRepository](../app/src/main/java/com/dvladi/mynavvy/WeatherRepository.kt) fetches
off the main thread and disk-caches every raw response, so a later offline launch still
has data:

- **Wind grid:** Open-Meteo forecast API (GFS/ECMWF blend), hourly, 3 days, knots.
  Re-fetched when the viewed area moves far enough from the last fetch.
- **Tides:** NOAA CO-OPS 6-minute predictions, 48 h window, from the **nearest prediction
  station** to the current view (station directory cached in `filesDir/offline/`). Beyond
  100 nm from any US station (Mexico/Canada) the tide tile honestly shows **"no station"**,
  and the Wx readout names the station + distance it is using.

## On-device data layout (app external files dir)

```
files/
  regions/<id>/
    region.json  charts.mbtiles  routing_grid.png  routing_grid.json  basemap.mbtiles
  basemap_cache.mbtiles           ← shared read-through cache (grow as you cruise)
  boats.json (optional)
  tracks/track-<epochDay>.csv     ← always-on track log, one file per UTC day
  logs/                           ← GPX route exports, track JSON exports
files-internal (filesDir)/offline/  ← weather/tide caches + CO-OPS station directory
```

Track CSV line format: `epochMs,lat,lon,sogKn,cogDeg` (cog empty when unknown). Retention
~6 months, pruned by whole-day file deletion. See [navigation.md](navigation.md).
