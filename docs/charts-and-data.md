# Charts & offline data

The APK ships only code. All chart/routing data is built by `charts-pipeline/` and either
pushed over adb (dev) or downloaded by the app itself on first run (field tablet).

## The pipeline (`charts-pipeline/`)

Docker/WSL toolchain that turns free, public-domain **NOAA ENC (S-57)** data into the
app's offline artifacts:

```
NOAA CA_ENCs.zip ──▶ ogr2ogr (S-57 → GeoJSON, clipped to bbox)
                       ├─▶ tippecanoe ──▶ out/charts.mbtiles      (vector chart tiles)
                       ├─▶ rasterize  ──▶ out/routing_grid.png + .json   (routing mask)
                       └─▶ make_style.py / add_depth_labels.py ──▶ app assets style.json
```

- **Rendered S-57 layers:** DEPARE, DRGARE, LNDARE, COALNE, DEPCNT, SOUNDG, LIGHTS, BOYLAT.
- **Usage bands:** ENC bands (overview…harbour) are generalisations of one another, so
  `make_style.py` emits fills band-by-band, *coarse first*, water-then-land within a band —
  a finer band's water overdraws a coarser band's land, while coarse data still covers
  offshore areas with no fine survey.
- **Depth labels:** `add_depth_labels.py` patches the style to label soundings and contours
  in feet (ENC values are metres). Idempotent.
- Entry point: `run_pipeline.ps1` (`-Push` adb-pushes charts, `-Install` installs the APK).

## Offline tile serving — `MbTilesServer`

A tiny localhost HTTP server (NanoHTTPD) serves tiles straight out of the MBTiles SQLite
files: MapLibre's style points at `http://127.0.0.1:<port>/tiles/{z}/{x}/{y}.pbf`.
MBTiles rows are TMS scheme; the server flips to XYZ (`tms_y = 2^z − 1 − y`).

**Basemap read-through cache ("grow as you cruise"):** the OSM land basemap starts from a
seed `basemap.mbtiles`. When online, a tile miss is fetched from the remote tile server
and *persisted into a cache MBTiles* before being served — any area viewed while online
becomes part of the offline basemap. Chart tiles never do this; they are complete offline.

## Routing grid

`routing_grid.png` (+ `.json` georeferencing) encodes draft-aware navigability, north-up:

| Pixel | Meaning |
|---|---|
| `0` | land — blocked |
| `255` | unknown / deep — navigable |
| `1..254` | charted depth: `depth_m = (v−1)/253 × 50` |

[RoutingGrid](../app/src/main/java/com/dvladi/mynavvy/RoutingGrid.kt) samples it in-app;
the same file drives `boatsim`'s land-check when placing the simulated boat. Whether a
depth pixel is "navigable" depends on the boat's configured draft + safety margin
(`BoatProfile`), evaluated at query time — the grid itself is boat-agnostic.

## First-run download — `DataAssets`

On a fresh install the app offers to download the data set over Wi-Fi from the publish
server (a static HTTPS file server):

- A JSON manifest lists each artifact with its **SHA-256** and size.
- Files download to the app's external files dir, are hash-verified, then moved into
  place; a `<name>.sha` marker records the installed version so a 700 MB file is never
  re-hashed just to check currency.
- The same mechanism serves later chart updates (a top banner offers them; a forced
  re-download lives in Configuration → *Reload charts* for repairing corrupt files).

Artifacts: `charts.mbtiles`, `basemap.mbtiles`, `routing_grid.png`, `routing_grid.json`,
plus an optional fuller `boats.json` catalogue.

APK updates themselves are delivered outside the app (any static-file installer flow
works, e.g. Obtainium watching the publish directory).

## Weather & tide data

[WeatherRepository](../app/src/main/java/com/dvladi/mynavvy/WeatherRepository.kt) fetches
off the main thread and disk-caches every raw response, so a later offline launch still
has data:

- **Wind grid:** Open-Meteo forecast API (GFS/ECMWF blend), hourly, 3 days, knots.
  Re-fetched when the viewed area moves far enough from the last fetch.
- **Tides:** NOAA CO-OPS hourly predictions, 48 h window (station currently hard-coded to
  San Diego 9410170 — becomes location-aware when more chart areas exist).

## On-device data layout (app external files dir)

```
files/
  charts.mbtiles          basemap.mbtiles        basemap_cache.mbtiles
  routing_grid.png        routing_grid.json      boats.json (optional)
  tracks/track-<epochDay>.csv     ← always-on track log, one file per UTC day
  logs/                            ← GPX route exports, track JSON exports
  weather cache files
```

Track CSV line format: `epochMs,lat,lon,sogKn,cogDeg` (cog empty when unknown). Retention
~6 months, pruned by whole-day file deletion. See [navigation.md](navigation.md).
