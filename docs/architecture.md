# Architecture

Single-module Kotlin Android app (`app/`). One activity hosts a full-screen MapLibre map;
screens are either *overlays on the map* (nav HUD, anchor watch) or *fragments covering it*
(Helm, Wind, Weather, Route, Trip). A foreground service owns the GPS. Everything is built
to keep working offline and to survive an old tablet (minSdk 21, GL ES, no Play Services).

```
                       ┌────────────────────────────────────────────┐
                       │                MainActivity                │
   fixes (onFix)  ┌──▶ │  map + style, chrome, screens, menus,      │
                  │    │  HUD sidebar, splits, weather, routing     │
┌──────────────┐  │    └───────┬────────────────────────────────────┘
│ WatchService │──┘            │ owns (created on style load)
│  (FGS)       │               ▼
│  GPS · NMEA  │    RouteManager · TrackHistory.Overlay · WeatherOverlay
│  sim·filters │    AnchorWatch · MarkStore · BoatMarker      (MapLibre layers)
│  COG · track │
│  anchor alarm│──▶ TrackStore (CSV/day on disk)
└──────────────┘
   ▲        ▲                MbTilesServer (localhost) ◀── charts.mbtiles / basemap.mbtiles
   │        │ SIM_FIX broadcast (debug builds)
   │   boatsim.py (PC)
   │ NMEA 0183 over WiFi TCP (GO7 GoFree / ESP32 / boatsim --nmea-server)
```

## Position pipeline — `WatchService`

`WatchService` is a foreground service and the **single position authority**. It requests
all framework location providers (no fused/Play), and every fix — real GPS or the debug
simulator — goes through one funnel, `handleFix()`:

1. **Gates** (untrusted fixes only): stale-fix age check, accuracy check, and a
   spike/glitch filter (implausible jump speed → rejected, with a resync escape hatch so a
   genuinely teleported boat recovers after ~30 s).
2. **COG derivation** from successive positions (held when nearly still; reset on teleport).
   The derived COG is stamped onto the fix's `bearing`.
3. **Track recording** into [TrackStore](../app/src/main/java/com/dvladi/mynavvy/TrackStore.kt)
   (always on — there is no record button).
4. **Anchor watch** evaluation + alarm (works with the screen off; the service is sticky).
5. Delivery to the UI: `callback.onFix(location, teleported)` on the main thread.

`MainActivity.onFix()` does UI only: boat marker, camera follow, breadcrumb trail, HUD
values, nav HUD refresh. The service and the UI can never disagree about filtering because
the filtering happens once, in the service.

**Simulator mode:** debug builds (`BuildConfig.SIM_ENABLED`) register an exported
`SIM_FIX` broadcast receiver; on an *emulator* the real GPS is not requested at all, so the
injected stream is the sole source. On real hardware real GPS always runs — a field device
can never lose its fix because a sim build was left installed. See
[development.md](development.md).

### NMEA-over-WiFi source (`com.dvladi.mynavvy.nmea`)

Live boat-instrument data (Simrad GO7 GoFree AP, later an ESP32 N2K gateway, or boatsim's
`--nmea-server` bench feed) enters through the same funnel:

- [NmeaClient](../app/src/main/java/com/dvladi/mynavvy/nmea/NmeaClient.kt) — TCP stream as
  a `Flow<String>`, auto-reconnect with backoff. It pins its sockets to the **WiFi
  `Network`** (`requestNetwork(TRANSPORT_WIFI)` + that network's socket factory): a boat AP
  has no internet, and an unpinned socket would silently route over cellular.
- [NmeaParser](../app/src/main/java/com/dvladi/mynavvy/nmea/NmeaParser.kt) — pure
  checksum-validated `String -> NavUpdate?` (RMC/GLL/VTG/DPT/DBT/VHW, wind spec'd for the
  ESP32); JVM unit tests in `app/src/test`.
- `WatchService` runs the pipeline in its FGS scope. RMC/GLL become
  `Location("nmea")` → `handleFix(trusted = true, trustedCog = true)` — the source's real
  COG/SOG are used **verbatim** (position-derived COG is garbage at anchor). Depth / STW /
  heading feed age-gated getters (`nmeaDepthM()` …) that the HUD sidebar, nav HUD, helm and
  anchor-shoaling check consume (DEPTH shows "sounder live" and beats chart+tide while fresh).

**Position authority:** while NMEA fixes are fresh (≤10 s) they are *the* position — phone
GPS is unregistered and SIM_FIX/GPS fixes are dropped at the funnel. When the stream goes
stale the watchdog re-registers the phone GPS immediately (auto-fallback), and NMEA takes
authority back on its next fix.

**Source selection** (Configuration → Data source, persisted in
[NmeaPrefs](../app/src/main/java/com/dvladi/mynavvy/nmea/NmeaPrefs.kt), applied by
`WatchService.applyNmeaPrefs()` on start and on change): *Phone GPS* (off) · *Boat (auto)* ·
*Manual TCP host:port*. Auto mode resolves the endpoint on **every** connect cycle via
[GoFreeDiscovery](../app/src/main/java/com/dvladi/mynavvy/nmea/GoFreeDiscovery.kt) —
GoFree JSON announce on UDP multicast `239.2.1.1:2052` (MulticastLock held), falling back
to probing the AP gateway on TCP 10110/2053 (that fallback is also how the emulator finds
boatsim through `10.0.2.2`). A chrome chip by the ☰ menu shows `NMEA … / LIVE / STALE`
(hidden when source = GPS); tapping it opens the dialog, whose **Test connection** runs an
independent client with a raw-sentence log (the on-boat GO7 probe). The debug `NMEA_DEBUG`
broadcast (sim builds) still force-starts/stops a source over the prefs.

## Map & style

- **MapLibre 11.8 (GL ES)** renders a generated `style.json` (see
  [charts-and-data.md](charts-and-data.md)) with two vector sources: NOAA chart tiles and
  an OSM land basemap — both served from local MBTiles by
  [MbTilesServer](../app/src/main/java/com/dvladi/mynavvy/MbTilesServer.kt) on `127.0.0.1`.
- App overlays are **vector layers, not bitmap icons** — runtime bitmap images don't render
  reliably against this style, so the boat marker, wind barbs, anchor graphics etc. are all
  built from line/fill/circle/symbol layers
  ([BoatMarker](../app/src/main/java/com/dvladi/mynavvy/BoatMarker.kt),
  [WeatherOverlay](../app/src/main/java/com/dvladi/mynavvy/WeatherOverlay.kt)).
- Ground-unit geometry (heading wedge, barbs) is **reprojected on zoom** to hold a constant
  on-screen size.
- Layer stack, bottom → top: track history (dashed day-tracks) → breadcrumb trail →
  computed route → planned route → waypoints → weather barbs → anchor watch → marks →
  boat marker (always on top).

## Screens & UI system

Two UI generations coexist while the XML → Compose migration completes
(see [ui-screens.md](ui-screens.md)):

- **Compose + Canvas gauges** (target style): Helm, Wind, Trip, Instruments, and the
  transparent Navigation HUD overlay. A fragment snapshots live app state ~1 Hz into an
  immutable data object (`game/HelmData`, `game/NavData`); `render/*Renderer` classes draw
  Simrad-style gauges onto the native canvas (`render/SimradGfx` holds the shared palette
  and text fitting).
- **XML chrome** (being retired): the menu, HUD sidebar, chart toolbar, config dialogs,
  weather/route fragments.

Screens read live state through `MainActivity.ui*()` accessor methods rather than owning
copies.

## Key components

| Component | Responsibility |
|---|---|
| `WatchService` | GPS, fix filtering, COG, always-on track log, anchor alarm (FGS) |
| `MainActivity` | Map, style, screens, chrome, menus, splits, camera policy |
| `MbTilesServer` | Offline tiles over localhost; read-through basemap cache |
| `DataAssets` | First-run/on-demand download of charts + routing grid (SHA-256 verified) |
| `RouteManager` | Waypoints, route line, breadcrumb trail, GPX export |
| `TrackStore` / `TrackHistory` | Persistent day-track CSVs / their chart overlay + browser |
| `AnchorWatch` | Anchor graphics (drop point, swing circle, rode, swing breadcrumbs) |
| `MarkStore` | Saved named POI pins (JSON in prefs) |
| `BoatProfile` / `BoatModel` / `BoatCatalog` | Draft & polar config → routing safety + speed |
| `RoutingGrid` | Draft-aware navigability mask sampled from `routing_grid.png` |
| `WeatherRepository` | Open-Meteo wind grid + NOAA CO-OPS tides, disk-cached |
| `WeatherRouter` | Time-optimal isochrone routing (wind-aware, draft-aware) |
| `Diagnostics` | Sentry-protocol crash/log reporting + own-logcat ring buffer |
| `AppEnv` / `GeoUtils` | Shared helpers: emulator detection, fix age, dp, great-circle math |

## Threading model

- UI thread: everything user-visible; fix delivery is posted to it by the service.
- `WeatherRepository`, `DataAssets`, `TrackHistory` loading, GPX/JSON export: worker
  threads, results posted back.
- Renderers (`render/*`) are single-threaded by contract — one draw at a time from Compose.
