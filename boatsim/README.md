# MyNavvy Boat Simulator

An interactive **helm** that runs on the PC, simulates a moving boat on a real map, and
streams its **position + SOG + COG** into a running MyNavvy build so you can exercise every
screen (HUD, course-up nav, track recording, anchor watch, routing) without a real GPS or boat.

The left pane is a live slippy **map** (OpenStreetMap / Google road / Google satellite):

- **Pan** by dragging (this turns **Follow boat** off so the view stays put; re-tick Follow
  to re-centre on the boat and keep it centred under way).
- **Double-click** a spot to place the boat there — it checks MyNavvy's chart mask first. On
  water it asks to confirm (showing the charted depth); on charted **land** it refuses and
  sounds an error chime instead of moving.
- **Right-click → "Set boat position here"** places the boat with no check.
- **Zoom** with the on-map +/− buttons or the mouse wheel; a live **scale bar** (m + nm) sits
  bottom-left. It opens at ~300 m scale.

## Requirements

- **Python 3.14** (`py -3.14`) with the map widget:
  `py -3.14 -m pip install --user tkintermapview`
  (On this machine the default `python` is msys2's Python 3.12, which has no pip and can't
  run the map — that's why `boatsim.bat` calls `py -3.14`.)
- Internet, for map tiles.

## Quick start

1. Start the emulator (or plug in the tablet) and launch a **debug** MyNavvy build so it's
   on screen. Grant location once: `adb shell pm grant com.dvladi.mynavvy android.permission.ACCESS_FINE_LOCATION`.
2. Run the helm:

   ```
   boatsim.bat            (or)   py -3.14 boatsim.py
   ```

   It auto-detects the adb device and starts streaming at 1 Hz (real GPS rate).

## Controls

| Key            | Action                                             |
|----------------|----------------------------------------------------|
| ↑ / ↓          | Throttle — ordered speed up / down                 |
| ← / →          | Helm — **hold** to swing the rudder; release centres it |
| 0–9            | Set ordered speed directly to N knots              |
| Space          | All stop (way comes off gradually)                 |
| R              | Centre helm                                        |

Both bottom controls are levers with tick marks — **drag** to set, **double-click** to centre:

- **THROTTLE** (ticks 5/10/25/50/75/100% each way): centre = stop, right = ahead, left = astern.
  Full ahead = **9 kn**, full astern = **4 kn**; the readout shows % and knots. Double-click = stop.
- **HELM** (ticks 5–90°): ±90° (90° = rudder perpendicular to the keel); shows the live angle.
  Double-click = centre.

The boat only turns when it's **making way** — rate of turn scales with SOG, and at a standstill
the helm has no effect (no steerage). Backing down, the helm answers the other way, like a real
boat. A **Current** (set °/drift kn) can be dialled in to make the boat crab — COG then differs
from heading, good for testing set-and-drift on the chart.
The **Start** dropdown teleports to San Diego preset positions and resets the track.

## How it reaches the app (MyNavvy v0.47+ WatchService)

Position in MyNavvy is owned by an always-on `WatchService` that runs the GPS filter, **derives
COG itself** from successive positions, and (in a **sim-enabled build**) registers a `SIM_FIX`
receiver. Each fix is a single trusted broadcast:

```
adb shell am broadcast -a com.dvladi.mynavvy.SIM_FIX -p com.dvladi.mynavvy \
    --ed lat <deg> --ed lon <deg> --ef sog <kn> --ef cog <deg>
```

`SIM_FIX` fixes are *trusted*: the service bypasses its spike/accuracy gates for them, so
place-boat teleports land instantly. **On the emulator, a sim build disables the real GPS
entirely** (`WatchService.isEmulator()`), so there's no competing fix — one broadcast is all it
takes. The service derives COG from the position stream, so the `cog` we send is advisory only;
a smooth position stream is what produces a good COG. `sog` drives the speed readout.

### Sim on/off is a build flag (field APKs are safe)

`BuildConfig.SIM_ENABLED` gates the whole thing:

- **`gradlew assembleDebug`** (emulator dev) → sim ON. On the emulator, real GPS is suppressed and
  only `SIM_FIX` drives the boat.
- **`publish-mynavvy.ps1`** (deliver) builds with `-PsimEnabled=false` → sim **compiled out**: no
  `SIM_FIX` receiver, always real GPS. Release builds force it off too.
- Even in a sim build, the real GPS is only suppressed **on an actual emulator** — a real device
  always uses real GPS, so a sim build can never leave a field device without a fix.

So a delivered/field APK ignores these broadcasts completely.

## Other modes

```
python boatsim.py --list         # list adb devices
python boatsim.py --demo 20       # headless: drive a 20 s S-turn (pipeline test)
python boatsim.py --serial <sn>   # target a specific device
python boatsim.py --smoke 3       # launch GUI and auto-close (self-test)
```
