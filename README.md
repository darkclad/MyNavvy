# MyNavvy

Personal offline marine navigation app for **US waters** (currently charted: San Diego Bay
+ ~300 nm), built to run on an old (~2018) Android tablet at the helm of a motoring
sailboat. Free NOAA chart data, fully offline, no cloud accounts, no Google Play Services.

> **Not for primary navigation.** A planning and situational-awareness aid — cross-check
> against official charts and your depth sounder.

## What it does

- **Offline vector charts** — NOAA ENC (S-57) rendered by MapLibre from a local MBTiles
  file, served by an in-app localhost tile server. A self-hosted **OpenMapTiles** land
  basemap (built with Planetiler) fills in shore/road detail and grows offline coverage
  as you cruise via a "read-through" tile cache.
- **Live navigation** — boat marker with heading wedge, course-up tilted Navigation HUD
  (Simrad NSO-style corner panels + heading tape), speed/course/position/depth sidebar,
  tide-corrected depth and under-keel clearance for *your* draft.
- **Instruments** — Helm (steering page), Wind (relative wind dial), Trip — drawn as
  custom Canvas gauges inside Jetpack Compose (no XML), styled after Simrad NSO-Evo.
- **Live boat data (NMEA-0183 over WiFi)** — reads position, COG/SOG, depth and wind
  straight from the boat's own GPS/chartplotter (GoFree auto-discovery or a manual
  TCP host), and falls back to the phone GPS automatically when the feed goes stale.
- **Day / night themes** — day, night, and red-on-black night-vision chart palettes;
  Auto mode flips at local sunrise/sunset and dims the screen for night watches.
- **Split views** — any chart/nav/gauge pair, side-by-side (landscape) or stacked (portrait).
- **Routes & marks** — tap-to-plan waypoints with GPX export; named saved marks.
- **Weather routing** — draft-aware, time-optimal isochrone router using the wind forecast
  and a boat polar (sail / motor / motor-sail).
- **Weather & tides** — Open-Meteo wind grid drawn as meteorological wind barbs;
  NOAA CO-OPS tide curve with a forecast time slider. Cached for offline use.
- **Always-on track recording** — one CSV per day, kept ~6 months; the last N day-tracks
  draw on the chart as dashed lines; browse / share / export / delete from the menu.
- **Anchor watch** — swing circle, live rode line, drag alarm (runs in a foreground
  service, screen off).
- **Self-updating & remote diagnostics** — an in-app auto-updater pulls new builds over
  HTTPS with SHA-256 verification; crashes and on-demand log snapshots report to a
  self-hosted Sentry-compatible server — so a remote tablet needs no adb.

## Repo layout

| Path | What |
|---|---|
| `app/` | The Android app (Kotlin, single module) |
| `charts-pipeline/` | Docker/WSL pipeline: NOAA ENC → `charts.mbtiles` + routing grid + style |
| `boatsim/` | PC helm simulator — drives a virtual boat into the app over adb (see its README) |
| `app-icon/` | Icon source art |
| `docs/` | **Documentation** — start with [docs/architecture.md](docs/architecture.md) |

## Documentation

- [Architecture](docs/architecture.md) — components, position pipeline, data flow
- [Charts & offline data](docs/charts-and-data.md) — ENC pipeline, tile serving, routing grid, first-run downloads
- [UI & screens](docs/ui-screens.md) — screens, menu, split views, layout & chrome rules
- [Navigation features](docs/navigation.md) — tracks, routes, anchor watch, weather routing
- [Development](docs/development.md) — building, simulator, emulator gotchas, diagnostics

## Quick start (dev)

```powershell
# JAVA_HOME → Android Studio's bundled JBR, then:
.\gradlew.bat :app:assembleDebug
adb install -r -g app\build\outputs\apk\debug\app-debug.apk
```

Debug builds enable the boat simulator hook — see
[docs/development.md](docs/development.md) for driving the app with `boatsim/`.

Chart data is not in the repo; the app offers to download it on first run, or build it
yourself with `charts-pipeline/` (needs Docker).

## Design constraints

| Concern | Choice |
|---|---|
| OS floor | `minSdk 21` (Android 5.0) — targets an old tablet |
| GPU | MapLibre **11.8 / OpenGL ES** (newer default to Vulkan; old GPUs lack it) |
| UI | Jetpack Compose chrome + custom Canvas gauges; XML being retired |
| Location | Framework `LocationManager` — **no Play Services** on purpose |
| Connectivity | Everything must work offline; online only refreshes caches |
