# Navigation features

## Tracks (always-on recording)

There is **no record button** — while `WatchService` runs, every filtered fix is appended
to [TrackStore](../app/src/main/java/com/dvladi/mynavvy/TrackStore.kt):

- **Storage:** one CSV per UTC day, `files/tracks/track-<epochDay>.csv`, lines
  `epochMs,lat,lon,sogKn,cogDeg`. Whole-file-per-day makes retention pruning a cheap
  delete; default retention ≈ 6 months.
- **Throttling:** a fix is logged when the boat moved ≥ 3 m, or every 30 s while
  stationary — an anchored night doesn't bloat the log.
- **On the chart:** [TrackHistory](../app/src/main/java/com/dvladi/mynavvy/TrackHistory.kt)
  draws the last *N* day-tracks (Configuration → History tracks) as thin dashed grey lines
  with the date riding along the line. Days are split into segments on a >15 min gap or a
  >1 km jump, so teleports/gaps don't draw as false passage lines. Drawn beneath the live
  trail and route layers.
- **Tracks browser** (menu → Tracks): every stored day with distance + point count.
  *Share* = JSON blob via the Android share sheet; *Export* = JSON file into `files/logs/`;
  *Delete* = remove the day file (confirmed).
- **Track JSON format:**
  ```json
  {"app":"MyNavvy","type":"track","date":"YYYY-MM-DD",
   "points":[{"t":epochMs,"lat":..,"lon":..,"sog":..,"cog":..}, ...]}
  ```
- Separate from the **breadcrumb trail** — a bounded, session-only cyan line fed every fix
  (RouteManager), cleared on teleport; purely visual.

## Routes & marks

- **Waypoints** are tap-planned on the chart (long-press → add), reorderable implicitly by
  add order, undoable, renameable. The route line is dashed pink; legs and totals show on
  the Route screen with ETA at the configured cruise speed.
- **GPX export** writes the route to `files/logs/mynavvy_<timestamp>.gpx`.
- **Marks** are persistent named POI pins, distinct from route waypoints. Dropped at the
  boat (toolbar ⚑) or via long-press; stored as JSON in prefs.

## Weather routing

[WeatherRouter](../app/src/main/java/com/dvladi/mynavvy/WeatherRouter.kt) computes a
time-optimal path with a classic **isochrone expansion**:

1. From the start, expand a reachability front in fixed time steps; at each front point
   try many headings.
2. Advance each candidate by the boat speed for that heading — from
   [BoatModel](../app/src/main/java/com/dvladi/mynavvy/BoatModel.kt): a cruising-monohull
   sail polar (bilinear-interpolated), a fixed motor cruise speed, or max(both) for
   motor-sailing — using the **time-varying hourly wind** at that place and time.
3. Discard moves that cross non-navigable water: `RoutingGrid` sampled with the boat's
   draft + safety margin (`BoatProfile`). Draft is safety-critical and always editable.
4. Prune the front to its outer envelope (farthest per bearing sector), repeat until the
   destination is reachable within a step, then backtrack the optimal path.

The computed route draws in green under the planned route. Currents are not yet modelled.

## Anchor watch

Toolbar ⚓ → set the alarm radius and drop the anchor at the boat's position:

- [AnchorWatch](../app/src/main/java/com/dvladi/mynavvy/AnchorWatch.kt) draws the drop
  point, the swing circle, a live rode line to the boat, and a breadcrumb of recent fixes
  (the swing pattern tells you if you're dragging before the alarm does).
- The **alarm logic lives in `WatchService`** — a foreground service — so the drag alarm
  fires with the screen off and the app in the background. Distance to anchor is
  re-evaluated on every filtered fix; outside the radius → full-volume alarm +
  max-priority notification.
- The chart's auto-recenter is suspended while the anchor framing owns the camera.
- Raise / adjust from the same ⚓ button. The anchor state survives app restarts (it lives
  with the service + prefs).

## Depth & under-keel clearance

The DEPTH tile and tap-a-depth readouts combine:

- **charted depth** — queried from the rendered vector tiles under the point (min across
  the depth features hit), falling back to the routing grid offshore;
- **tide correction** — the current NOAA CO-OPS predicted height added to chart datum
  (MLLW);
- **UKC** = corrected depth − configured draft; drawn red when thin.

Everything displays in the configured units (ft/m); internally metres everywhere.

## Boat profile

`BoatProfile` (JSON in filesDir) is the single source of truth for draft, safety margin,
cruise speed, polar choice and units. `BoatCatalog` offers per-keel-variant presets from a
bundled (or downloaded) `boats.json` — the keel variant, not the model year, decides the
draft, and the draft decides which water the router will let you through.
