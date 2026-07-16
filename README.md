# MyNavvy

Personal offline marine navigation app for **US waters (San Diego Bay + ~300 nm)**, built to run on an
old (~2018) Android tablet. Free NOAA charts, offline, no cloud, no Google Play Services.

## Status — Phase 0 + Phase 1 pipeline

- **App** (`app/`): MapLibre map + live GPS (COG/SOG HUD), renders NOAA charts offline from a local
  MBTiles file via a bundled localhost tile server. Falls back to demo tiles until charts are installed.
- **Charts** (`charts-pipeline/`): Docker pipeline that turns free NOAA ENC (S-57) data into `charts.mbtiles`.

### Design choices for old-tablet support
| Concern | Choice |
|---|---|
| OS floor | `minSdk 21` (Android 5.0) |
| GPU | MapLibre **11.8.0 OpenGL ES** (12/13.x default to Vulkan — old GPUs lack it) |
| UI | Plain Android Views, **no Jetpack Compose** |
| Location | Framework `LocationManager` GPS — **no Play Services** |
| Offline charts | `NanoHTTPD` serves MBTiles over `127.0.0.1` |

## Build the app
```
set JAVA_HOME to Android Studio's jbr, then:
gradlew.bat :app:assembleDebug
```
APK: `app/build/outputs/apk/debug/app-debug.apk`

## Build + install the charts (needs Docker Desktop)
```
cd charts-pipeline
./run_pipeline.ps1 -Install -Push   # build charts, install app, push to tablet
```
The chart file lands at `/sdcard/Android/data/com.dvladi.mynavvy/files/charts.mbtiles`.

## Chart data
- Source: NOAA `CA_ENCs.zip` (free, public domain), clipped to the San Diego + 300 nm bbox.
- Rendered S-57 layers: DEPARE, DRGARE, LNDARE, COALNE, DEPCNT, SOUNDG, LIGHTS, BOYLAT
  (styled in `app/src/main/assets/style.json`).
- **Not for primary navigation** — a planning aid; cross-check with official charts and your depth sounder.

## Roadmap
- Phase 2: waypoints/routes, ETA, offline chart download manager, track log
- Phase 3: weather + tide overlays (Open-Meteo, NOAA CO-OPS) with forecast time slider
- Phase 4: draft-aware isochrone weather routing (native C++/NDK) with sail/motor/motor-sailing modes
- Phase 5: NMEA-0183/2000 over WiFi/Bluetooth, AIS overlay, anchor alarm
