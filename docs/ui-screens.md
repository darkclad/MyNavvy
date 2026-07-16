# UI & screens

The design language is a Simrad NSO-Evo chartplotter: dark chrome, angled panels,
monospace condensed values, chunky touch targets for use underway.

## Screen map

Everything is reached from the ☰ menu (top-left):

| Tile | Screen |
|---|---|
| **Chart** | The base screen: full-screen north-up chart, instrument sidebar, toolbar |
| **Navigation** | Course-up, 60°-tilted chart with the transparent Simrad nav HUD overlay |
| **Helm** | Steering page: compass rose, track lane, six data boxes (Canvas gauges) |
| **Weather** | Full forecast screen: wind field, tide curve, time slider |
| **Route** | Passage planning: waypoint list, leg stats, ETA, GPX export, weather routing |
| **Trip** | Trip odometer/stats snapshot |
| **Wind** | Boat-fixed relative wind dial (true + apparent, derived from forecast + COG) |
| **Split view** | Submenu listing every two-pane combination |
| **Tracks** | Recorded day-tracks browser: share / export / delete |
| **Configuration** | Propulsion mode, units, default range, history tracks, boat config, offline data |

Hardware back walks backwards: split submenu → menu → close; covering screen → chart.

## The chart screen

- **Instrument sidebar** (right): DEPTH (chart+tide, red when UKC is thin), SOG, COG,
  POSITION, WIND (forecast), TEMP, TIME, TIDE with a mini 48 h curve. Content-sized — it
  ends after the tide tile.
- **Left toolbar** (auto-hides): undo waypoint, clear route, route options, weather
  overlay, depth labels, drop mark, anchor watch.
- **Zoom / center buttons** (bottom-right): `◎` snaps to the boat at the configured
  default range and re-engages follow; `+/−` zoom the *last-touched* map.
- **Boat-follow & auto-recenter:** panning breaks follow; the chart snaps back to the boat
  after 10 s of inactivity. The countdown restarts on every gesture and is held while a
  finger is down — the chart never recenters mid-drag.
- **Tap** = charted depth at that point (tide-corrected, with UKC); **long-press** = manage
  route waypoints / marks / anchor. Tapping a mark or waypoint manages it.

## Navigation screen

Transparent Compose HUD (`NavHudScreen` / `NavHudRenderer`) over the live tilted map:
four angled corner panels (STEER, DEPTH, SOG, COG), a centre data-bar (DTW · WPT · TTG)
and a heading tape with a red course cursor. Portrait and landscape are separate layouts
of the same elements, chosen by aspect ratio. Chart, boat wedge and route stay real map
layers underneath — the HUD draws no geography.

## Split views

Two panes: side-by-side in landscape, stacked in portrait. At most one pane is a *map*
screen (Chart or Nav) — except **Chart+Nav**, which runs a second MapView for the nav pane
(north-up chart left, course-up nav right). Map-pane combos leave the pane transparent
over the padded full-screen map; gauge panes are opaque fragments. Offered pairs:
Chart+Nav, Chart+Helm, Chart+Wind, Nav+Helm, Nav+Wind, Helm+Wind, Wind+Trip.

## Layout rules (uniform across screens)

These invariants are enforced in one place each (`updateZoomControlsMargins`,
`fitSidebarToPane`, `applyOrientationChrome` in MainActivity):

1. **Sidebar is content-sized** wherever a chart pane shows it — it ends after the tide
   tile. In a split pane it is additionally capped to the pane *minus room for the zoom
   buttons*, and scrolls internally when clipped.
2. **Zoom/center buttons anchor to the pane holding their target map** — bottom-right,
   10 dp from that pane's edge: the screen edge on full-screen chart/nav, pane B for
   Chart+Nav, pane A (the map pane) for map+gauge splits. They never hide behind an opaque
   gauge pane and never overlap the sidebar (post-layout check drops them just below it).
3. **Transient chrome has ONE auto-hide policy** (`flashChrome()`): any map interaction
   (tap, pan, pinch, zoom buttons — on either map) shows the participating chrome; one
   shared 4 s timer fades it out. Participants per screen: the chart toolbar (chart base /
   chart split pane) + the scale bar(s) (any map screen; both bars in Chart+Nav).
4. The scale bar is a shared widget (`ScaleBarView`, marine units) with one instance per
   live map.

## Configuration dialog

- **Propulsion mode** (Sail / Motor / Mtr+Sail) — feeds the router's boat model.
- **Units** Metric/Imperial — vertical measures only (depth/tide); speed & distance stay
  kn/nm. Selected toggle = black text, unselected = blue.
- **Default range** — chart width the map opens at and `◎` returns to.
- **History tracks** — how many recorded day-tracks draw on the chart (Off…30, default 5).
- Boat configuration (draft, polar, catalogue pick), offline data pre-download, land-map
  download for the current view, forced chart reload.

## Compose migration status

Migrated: Instruments, Helm, Wind gauges, Navigation HUD. Still XML (to migrate): the
menu, HUD sidebar, chart toolbar/scale bar, Weather + Route fragments, dialogs,
BoatConfigActivity, TideGraphView. The map itself stays a classic MapView behind Compose
overlays by design — MapLibre owns its GL surface.
