# Development

## Building

```powershell
# JAVA_HOME → Android Studio's bundled JBR
.\gradlew.bat :app:assembleDebug
adb install -r -g app\build\outputs\apk\debug\app-debug.apk
```

- `versionCode`/`versionName` live in `app/build.gradle.kts`. Field updates require a
  **higher versionCode**.
- `release` builds are currently **unsigned** (no keystore configured) and won't install —
  use `debug`, which keeps a stable signature via the machine's debug keystore.

### Build flags (Gradle `-P` properties)

| Property | Effect |
|---|---|
| `-PsimEnabled=true\|false` | Force the boat-simulator hook on/off. Default: **on for debug, off for release**. Published/field APKs are built with `false` — the SIM_FIX receiver is compiled out entirely. |
| `-PSENTRY_DSN=<dsn>` | Crash-reporting DSN baked into `BuildConfig` (empty = reporting disabled). Keep DSNs out of tracked files. |

**Gotcha:** if the app ignores the simulator, you are almost certainly running a
`simEnabled=false` build (e.g. one downloaded from the publish server). Install a local
`assembleDebug` build instead — broadcasts are silently dropped otherwise, with no error
anywhere.

## The boat simulator (`boatsim/`)

A PC-side Tk helm (map, throttle/helm levers, presets) that drives a virtual boat and
streams fixes into a running debug build at 1 Hz over adb:

```
adb shell am broadcast -a com.dvladi.mynavvy.SIM_FIX -p com.dvladi.mynavvy \
    --ed lat 32.69 --ed lon -117.20 --ef sog 6.0 --ef cog 90
```

- Run `boatsim/boatsim.bat` (needs a CPython with `tkintermapview`; see `boatsim/README.md`).
  `--serial <device>` targets a specific adb device; `--demo 20` is a headless pipeline test.
- Why not the emulator's `geo fix`? It cannot inject **bearing**, so COG would stick at 0.
  The broadcast path feeds the service's normal fix funnel (`trusted=true` skips the
  age/accuracy/spike gates but still gets COG/track/anchor/delivery).
- On an **emulator**, a sim build disables real GPS entirely so the injected stream is the
  sole source. On **real hardware** real GPS always stays on.

Debug-only adb triggers (sim builds): `--ez crash true` (test crash report),
`--ez snapshot true` (on-demand diagnostics snapshot) as extras to `am start` on
`MainActivity`.

## NMEA-over-WiFi bench testing

`boatsim.py --nmea-server [PORT]` additionally serves the sim boat as **NMEA 0183 over
TCP** (RMC/VTG/DPT/VHW @ 1 Hz, default port 10110) — a bench stand-in for the GO7's GoFree
feed that exercises the real client → parser → position-authority chain
(see [architecture.md](architecture.md)):

```
# PC: serve NMEA (works with --demo and the GUI; GUI status line shows client count)
py -3.14 boatsim\boatsim.py --demo 60 --nmea-server

# app (sim build): start/stop the NMEA source — 10.0.2.2 = host PC from the emulator;
# from a real tablet on the PC's Mobile Hotspot use 192.168.137.1
adb shell am broadcast -a com.dvladi.mynavvy.NMEA_DEBUG -p com.dvladi.mynavvy \
    --es host 10.0.2.2 --ei port 10110
adb shell am broadcast -a com.dvladi.mynavvy.NMEA_DEBUG -p com.dvladi.mynavvy --ez stop true

# watch: raw sentences (also how GO7 parser fixtures get captured) + authority handoffs
adb logcat -s NmeaRaw NmeaClient WatchService
```

While NMEA is live the DEPTH tile reads "sounder live", phone GPS is suppressed and
SIM_FIX is ignored; stop the server (or walk out of range) and the app falls back to
phone GPS / SIM_FIX within ~10 s. The emulator can't exercise the WiFi socket-binding
gotcha — that needs a real tablet on an AP (e.g. the PC's Mobile Hotspot).

The normal (non-debug) path is **Configuration → Data source**: *Boat (auto)* discovers
the source (GoFree announce → gateway probe; on the emulator the probe finds boatsim via
`10.0.2.2`), *Manual TCP* takes host:port, and **Test connection** shows a live raw-sentence
log — that screen doubles as the on-boat GO7 probe. The choice persists (`nmea` prefs) and
the service reconnects on every app start; the chrome chip next to ☰ shows
`NMEA … / LIVE / STALE` and opens the dialog on tap. boatsim's `--nmea-server` also
multicasts a GoFree-style announce every 5 s, so auto-discovery is testable on a real
tablet over the PC's Mobile Hotspot (multicast doesn't reach the emulator).

## Emulator gotchas

- **GLES crash guard:** the emulator's guest GL encoder SIGSEGVs when MapLibre renders a
  *tilted or course-up-rotated* camera (any `-gpu` backend). Nav mode therefore runs
  **north-up + flat on emulators** (`navTiltDeg()` / `navCameraMode()` gate on
  `isEmulator()`); real devices get the full course-up 60° tilt. A crash-restart loop
  shows up as "Chart server failed: … EADDRINUSE" (the dead instance still holds the tile
  port).
- Cold-boot emulators (`-no-snapshot`) — a restored snapshot can wedge the input service.
- The tablet AVD's *natural* orientation is landscape: `user_rotation 0` = landscape,
  `1` = portrait.

## Diagnostics

[Diagnostics](../app/src/main/java/com/dvladi/mynavvy/Diagnostics.kt) reports uncaught
crashes/ANRs/NDK crashes to a self-hosted **Sentry-protocol** server (GlitchTip), queueing
offline. A background thread tails the app's *own* logcat into a ring buffer; W/E lines
become breadcrumbs and every event embeds the recent log tail — so a remote tablet needs
no adb session to debug. Long-press the chart HUD to send a snapshot manually.

Manifest must keep `io.sentry.auto-init=false` (init is manual with the BuildConfig DSN).

## Publishing (private infra)

The publish scripts and the ops runbook are **deliberately untracked** — they describe
private infrastructure (hosts, shares, credentials locations) and must never reach a
public mirror. They live in the project root on disk:
`publish-mynavvy.ps1` (APK build+versioning+manifest), `publish-mynavvy-data.ps1`
(chart/routing artifacts + SHA-256 manifest), `REMOTE-BUILD-AND-LOGS.md`,
`TABLET-SETUP.md`. Keep it that way: anything containing local hostnames, IPs, share
paths or credential locations stays out of git.

## Conventions

- Shared one-liners live in `AppEnv.kt` (`isEmulator()`, `Location.ageMs()`,
  `Context/Fragment.dp()`) and `GeoUtils` (the one haversine: `distanceM`, plus
  `distanceNm`, bearings, destination, cross-track). Don't re-implement these per class.
- Map overlays are vector layers, never runtime bitmap icons (they don't render reliably
  against this style).
- All lengths are metres internally; units are display-only.
- New chrome goes through the shared policies: `flashChrome()` for auto-hide,
  `updateZoomControlsMargins()` / `fitSidebarToPane()` for layout (see
  [ui-screens.md](ui-screens.md)).
