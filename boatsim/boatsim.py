#!/usr/bin/env python3
"""
MyNavvy Boat Simulator  —  an interactive helm that drives a virtual boat on this
PC and streams its position / SOG / COG into a running MyNavvy build so every
screen (HUD, course-up nav, track recording, anchor watch, routing) can be
exercised without a real GPS or a real boat.

How it reaches the app
----------------------
MyNavvy reads the fix straight from Android's LocationManager. The Android
emulator's own GPS can inject position and (via `geo fix`'s velocity arg) speed,
but it *cannot* inject bearing — so COG would be stuck at 0. To get a real COG we
push each fix through a tiny DEBUG-only broadcast receiver added to MyNavvy:

    adb shell am broadcast -a com.dvladi.mynavvy.SIM_FIX -p com.dvladi.mynavvy \
        --ed lat <deg> --ed lon <deg> --ef sog <kn> --ef cog <deg>

The app builds a Location("sim") with speed+bearing set and feeds its normal
onLocationChanged sink. This same broadcast works on a real tablet over adb too.

Controls (interactive GUI)
--------------------------
  Up / Down .......... throttle (ordered speed) up / down
  Left / Right ....... helm — hold to turn, release to centre the rudder
  Space .............. all stop (orders 0 kn; way comes off gradually)
  0-9 ................ set ordered speed directly to N knots
  R .................. centre rudder
  On-screen sliders mirror the keys; a set/drift "current" can be dialled in.

Run
---
  python boatsim.py                 # interactive helm GUI
  python boatsim.py --demo 20       # headless: drive a 20 s S-turn (pipeline test)
  python boatsim.py --list          # list adb devices and exit
"""

import argparse
import math
import os
import subprocess
import sys
import threading
import time

# --- Configuration -----------------------------------------------------------

PACKAGE = "com.dvladi.mynavvy"
ACTION = "com.dvladi.mynavvy.SIM_FIX"

# adb: honour PATH / ANDROID env first, then fall back to the known SDK location.
def _find_adb():
    for cand in (
        os.environ.get("ADB"),
        os.path.join(os.environ.get("ANDROID_SDK_ROOT", ""), "platform-tools", "adb.exe"),
        r"D:\Work\Android\platform-tools\adb.exe",
        "adb",
    ):
        if not cand:
            continue
        if cand == "adb" or os.path.isfile(cand):
            return cand
    return "adb"

ADB = _find_adb()

# Start positions (lat, lon) — San Diego, MyNavvy's charted area.
PRESETS = {
    "Bay entrance (Pt Loma)": (32.6720, -117.2350),
    "San Diego Bay (mid)":    (32.7000, -117.1700),
    "Shelter Island basin":   (32.7120, -117.2320),
    "Harbor Island":          (32.7270, -117.2100),
    "Coronado Bridge":        (32.6925, -117.1600),
    "Offshore (3 nm W)":      (32.6720, -117.3000),
}
DEFAULT_PRESET = "Bay entrance (Pt Loma)"

# --- Boat dynamics -----------------------------------------------------------

KN_TO_MPS = 0.514444
MPS_PER_LAT_DEG = 111320.0

RUDDER_MAX = 90.0        # degrees; 90 = rudder perpendicular to the keel (hard over)
RUDDER_RATE = 120.0      # deg/s the rudder swings toward its commanded angle
MAX_ROT = 28.0           # deg/s rate-of-turn at full rudder AND the reference speed
STEERAGE_REF_KN = 5.0    # speed for full rudder authority; ROT scales with SOG below it
STEERAGE_MAX_FACTOR = 1.5  # cap on the speed multiplier (you turn faster the faster you go)
RUDDER_KEY_RATE = 60.0   # deg/s the commanded rudder ramps while an arrow key is held
ACCEL_KN_S = 0.6         # how fast speed eases toward the ordered speed
MAX_FWD_KN = 9.0         # full-ahead speed (100% throttle)
MAX_REV_KN = 4.0         # full-astern speed (100% astern)
SPEED_MAX = MAX_FWD_KN
SPEED_MIN = -MAX_REV_KN


def _wrap360(d):
    return (d % 360.0 + 360.0) % 360.0


class Boat:
    """Kinematic boat. Heading = through-water course; SOG/COG include current."""

    def __init__(self, lat, lon):
        self.lat = lat
        self.lon = lon
        self.heading = 0.0        # deg, through water
        self.speed = 0.0          # kn, through water
        self.ordered_speed = 0.0  # kn
        self.rudder = 0.0         # deg (- port / + stbd)
        self.rudder_target = 0.0
        self.rot = 0.0            # deg/s rate of turn (derived)
        self.current_set = 0.0    # deg, direction current flows TOWARD
        self.current_drift = 0.0  # kn
        # Derived (over ground), refreshed each step.
        self.sog = 0.0
        self.cog = 0.0

    def teleport(self, lat, lon):
        self.lat, self.lon = lat, lon

    def step(self, dt):
        # Wheel eases toward its ordered angle (keys/slider set rudder_target).
        d = max(-RUDDER_RATE * dt, min(RUDDER_RATE * dt, self.rudder_target - self.rudder))
        self.rudder = max(-RUDDER_MAX, min(RUDDER_MAX, self.rudder + d))

        # Rate of turn needs water flowing over the rudder: no way through the water
        # -> no steerage -> heading (and thus COG) does not change. The turn rate scales
        # with speed up to a reference, so the boat turns faster the faster it goes; when
        # making sternway the factor goes negative so the helm answers the other way.
        speed_factor = max(-STEERAGE_MAX_FACTOR,
                           min(STEERAGE_MAX_FACTOR, self.speed / STEERAGE_REF_KN))
        self.rot = (self.rudder / RUDDER_MAX) * MAX_ROT * speed_factor
        self.heading = _wrap360(self.heading + self.rot * dt)

        # Speed eases toward the ordered speed.
        s = max(-ACCEL_KN_S * dt, min(ACCEL_KN_S * dt, self.ordered_speed - self.speed))
        self.speed = max(SPEED_MIN, min(SPEED_MAX, self.speed + s))

        # Over-ground velocity = boat-through-water + current (east/north, knots).
        hr = math.radians(self.heading)
        cr = math.radians(self.current_set)
        ve = self.speed * math.sin(hr) + self.current_drift * math.sin(cr)
        vn = self.speed * math.cos(hr) + self.current_drift * math.cos(cr)
        self.sog = math.hypot(ve, vn)
        self.cog = _wrap360(math.degrees(math.atan2(ve, vn))) if self.sog > 1e-4 else self.heading

        # Advance position.
        north_m = vn * KN_TO_MPS * dt
        east_m = ve * KN_TO_MPS * dt
        self.lat += north_m / MPS_PER_LAT_DEG
        self.lon += east_m / (MPS_PER_LAT_DEG * math.cos(math.radians(self.lat)))


# --- Transmit to the app -----------------------------------------------------

_NO_WINDOW = 0x08000000 if os.name == "nt" else 0  # CREATE_NO_WINDOW


class Transmitter:
    """Pushes fixes to MyNavvy over adb, off the GUI thread.

    MyNavvy (v0.47+) routes position through its always-on `WatchService`, which registers
    a DEBUG-only `SIM_FIX` receiver. Each fix is one *trusted* broadcast: the service bypasses
    its spike/accuracy filters for it (so place-boat teleports land instantly). In sim builds
    on the emulator the app also disables the real GPS entirely, so there is no competing fix
    to fight — a single broadcast is all it takes.

    The service DERIVES COG itself from successive positions, so the `cog` we send is advisory
    only; a smooth position stream is what produces a good course. `sog` still drives the speed
    readout. Requires a sim-enabled build (`SIM_ENABLED=true`); delivered/field APKs compile the
    receiver out and ignore these broadcasts.
    """

    def __init__(self, adb=ADB, serial=None):
        self.adb = adb
        self.serial = serial
        self.last_ok = None
        self.last_err = ""

    def _base(self):
        cmd = [self.adb]
        if self.serial:
            cmd += ["-s", self.serial]
        return cmd

    def devices(self):
        try:
            out = subprocess.run(self._base() + ["devices"], capture_output=True,
                                 text=True, creationflags=_NO_WINDOW, timeout=10).stdout
        except Exception as e:
            self.last_err = str(e)
            return []
        devs = []
        for ln in out.splitlines()[1:]:
            ln = ln.strip()
            if ln and "\tdevice" in ln:
                devs.append(ln.split("\t")[0])
        return devs

    def send(self, lat, lon, sog, cog):
        cmd = self._base() + [
            "shell", "am", "broadcast", "-a", ACTION, "-p", PACKAGE,
            "--ed", "lat", f"{lat:.6f}",
            "--ed", "lon", f"{lon:.6f}",
            "--ef", "sog", f"{sog:.2f}",
            "--ef", "cog", f"{cog:.1f}",
        ]
        try:
            r = subprocess.run(cmd, capture_output=True, text=True,
                               creationflags=_NO_WINDOW, timeout=8)
            self.last_ok = ("Broadcast completed" in r.stdout) or (r.returncode == 0)
            if not self.last_ok:
                self.last_err = (r.stderr or r.stdout).strip()[:120]
        except Exception as e:
            self.last_ok = False
            self.last_err = str(e)[:120]
        return self.last_ok


# --- Headless demo (pipeline self-test) --------------------------------------

def run_demo(seconds, tx):
    devs = tx.devices()
    if not devs:
        print("No adb device. Start the emulator (or plug the tablet) first.")
        return 1
    tx.serial = tx.serial or devs[0]
    print(f"Driving {tx.serial}: {seconds}s S-turn at 6 kn from {DEFAULT_PRESET}")
    lat, lon = PRESETS[DEFAULT_PRESET]
    b = Boat(lat, lon)
    b.heading = 0.0
    b.ordered_speed = 6.0
    dt = 0.1
    t = 0.0
    last_tx = -1.0
    while t < seconds:
        # S-turn: rudder right for first third, left for the middle, centre at end.
        b.rudder_target = 30.0 if t < seconds / 3 else (-30.0 if t < 2 * seconds / 3 else 0.0)
        b.step(dt)
        if t - last_tx >= 1.0:
            tx.send(b.lat, b.lon, b.sog, b.cog)
            print(f"  t={t:4.1f}s  {b.lat:.5f},{b.lon:.5f}  SOG {b.sog:4.1f}  COG {b.cog:5.1f}  "
                  f"{'ok' if tx.last_ok else 'ERR ' + tx.last_err}")
            last_tx = t
        time.sleep(dt)
        t += dt
    print("Demo done.")
    return 0


# --- Interactive GUI ---------------------------------------------------------

TILE_SERVERS = {
    "OpenStreetMap":    ("https://a.tile.openstreetmap.org/{z}/{x}/{y}.png", 19),
    "Google road":      ("https://mt0.google.com/vt/lyrs=m&hl=en&x={x}&y={y}&z={z}&s=Ga", 22),
    "Google satellite": ("https://mt0.google.com/vt/lyrs=s&hl=en&x={x}&y={y}&z={z}&s=Ga", 22),
}


def _offset(lat, lon, brg_deg, dist_m):
    """Point dist_m metres from (lat,lon) along bearing brg_deg. For the heading vector."""
    R = 6371000.0
    br = math.radians(brg_deg); dr = dist_m / R
    la = math.radians(lat); lo = math.radians(lon)
    la2 = math.asin(math.sin(la) * math.cos(dr) + math.cos(la) * math.sin(dr) * math.cos(br))
    lo2 = lo + math.atan2(math.sin(br) * math.sin(dr) * math.cos(la),
                          math.cos(dr) - math.sin(la) * math.sin(la2))
    return math.degrees(la2), math.degrees(lo2)


class LandMask:
    """Land / water / depth lookup from MyNavvy's routing grid (San Diego focus box).

    Encoding (see charts-pipeline/make_routing_grid.py): 0 = land, 1..253 = charted
    depth (v-1)/253*50 m, 254 = deep water, 255 = navigable/uncharted. North-up.
    """

    def __init__(self):
        self.ok = False
        self.err = ""
        try:
            import json as _json
            from PIL import Image
            base = os.path.dirname(os.path.abspath(__file__))
            outdir = os.path.join(base, "..", "charts-pipeline", "out")
            with open(os.path.join(outdir, "routing_grid.json")) as f:
                m = _json.load(f)
            self.w0, self.e0 = m["west"], m["east"]
            self.s0, self.n0 = m["south"], m["north"]
            self.W, self.H = m["width"], m["height"]
            self.dmax = m.get("depthMaxM", 50)
            self._px = Image.open(os.path.join(outdir, "routing_grid.png")).convert("L").load()
            self.ok = True
        except Exception as e:
            self.err = str(e)

    def classify(self, lat, lon):
        """Return (status, depth_m|None): 'land' | 'water' | 'outside'."""
        if not self.ok or not (self.s0 <= lat <= self.n0 and self.w0 <= lon <= self.e0):
            return ("outside", None)
        x = int((lon - self.w0) / (self.e0 - self.w0) * (self.W - 1))
        y = int((self.n0 - lat) / (self.n0 - self.s0) * (self.H - 1))
        x = max(0, min(self.W - 1, x)); y = max(0, min(self.H - 1, y))
        v = self._px[x, y]
        if v == 0:
            return ("land", None)
        if v >= 254:
            return ("water", None)          # deep / uncharted
        return ("water", (v - 1) / 253.0 * self.dmax)


def run_gui(tx, auto_close_ms=None):
    import tkinter as tk
    from tkinter import ttk, messagebox
    try:
        from tkintermapview import TkinterMapView
    except ImportError:
        print("tkintermapview is not installed for this Python.\n"
              "Install it with:  py -3.14 -m pip install --user tkintermapview\n"
              "and run boatsim with that same interpreter (boatsim.bat uses py -3.14).")
        try:
            r = tk.Tk(); r.withdraw()
            messagebox.showerror("Missing dependency",
                "tkintermapview is not installed for this Python.\n\n"
                "Run:  py -3.14 -m pip install --user tkintermapview")
            r.destroy()
        except Exception:
            pass
        return

    BG = "#0f1418"; PANEL = "#161d23"; FG = "#e6eef2"
    ACC = "#6fc6e8"; DIM = "#7f929c"; OK = "#6fdc8c"; BAD = "#ff5a5a"; TRK = "#6fc6e8"

    root = tk.Tk()
    root.title("MyNavvy Boat Simulator")
    root.configure(bg=BG)
    root.minsize(1040, 680)

    lat, lon = PRESETS[DEFAULT_PRESET]
    boat = Boat(lat, lon)
    boat.heading = 0.0

    state = {
        "running": True,          # transmitting?
        "tx_hz": 1.0,
        "keys": set(),
        "track": [(boat.lat, boat.lon)],
        "last_track_t": 0.0,
        "last_tx_t": 0.0,
        "last_map_t": 0.0,
        "serial": tx.serial,
        "recenter": True,         # snap the map to the boat on next refresh
        "key_steer": False,       # an arrow key is currently steering
    }
    mapobj = {"marker": None, "head": None, "track": None}

    # ---- styling ----
    style = ttk.Style()
    try:
        style.theme_use("clam")
    except tk.TclError:
        pass
    style.configure("TCombobox", fieldbackground=PANEL, background=PANEL, foreground=FG)
    style.configure("Horizontal.TScale", background=PANEL)

    def label(parent, text, fg=FG, font=("Consolas", 11), **kw):
        return tk.Label(parent, text=text, fg=fg, bg=kw.pop("bg", PANEL), font=font, **kw)

    # ---- lever control: a symmetric -max..+max canvas with tick marks, a live readout,
    #      drag-to-set and double-click-to-centre. Used for both the helm and throttle. ----
    class LeverControl(tk.Canvas):
        def __init__(self, parent, vmax, ticks, labels, fmt, on_change,
                     accent="#ffd24d", width=340, height=64):
            super().__init__(parent, width=width, height=height, bg=PANEL,
                             highlightthickness=0, takefocus=0)
            self.vmax = vmax          # scale runs -vmax .. +vmax
            self.ticks = ticks        # tick magnitudes (drawn both sides)
            self.labels = labels      # subset of ticks that get a number
            self.fmt = fmt            # value -> readout string
            self.on_change = on_change
            self.accent = accent
            self.value = 0.0          # value drawn by the thumb
            self.dragging = False
            self._pw = width
            self.axis_y = 34
            self.margin = 20
            self.bind("<Configure>", lambda e: (setattr(self, "_pw", e.width), self._redraw()))
            self.bind("<ButtonPress-1>", self._press)
            self.bind("<B1-Motion>", self._press)
            self.bind("<ButtonRelease-1>", self._release)
            self.bind("<Double-Button-1>", self._center)
            self._redraw()

        def _x(self, v):
            return self.margin + (v + self.vmax) / (2 * self.vmax) * (self._pw - 2 * self.margin)

        def _v(self, x):
            f = (x - self.margin) / max(1, (self._pw - 2 * self.margin))
            return max(-self.vmax, min(self.vmax, f * 2 * self.vmax - self.vmax))

        def set_value(self, v):
            """Reflect the actual value (called each tick when not being dragged)."""
            if not self.dragging:
                self.value = v
                self._redraw()

        def _press(self, e):
            self.dragging = True
            self.value = self._v(e.x)
            self._redraw()
            self.on_change(self.value)

        def _release(self, e):
            self.dragging = False
            root.focus_set()

        def _center(self, e):
            self.dragging = False
            self.on_change(0.0)   # thumb eases back to centre via set_value()

        def center(self):
            self.dragging = False
            self.on_change(0.0)

        def _redraw(self):
            self.delete("all")
            y = self.axis_y
            self.create_line(self._x(-self.vmax), y, self._x(self.vmax), y, fill=DIM, width=2)
            self.create_line(self._x(0), y - 10, self._x(0), y + 10, fill=FG, width=2)
            self.create_text(self._x(0), y + 18, text="0", fill=DIM, font=("Consolas", 7))
            for t in self.ticks:
                for s in (-1, 1):
                    x = self._x(s * t)
                    major = t in self.labels
                    h = 9 if major else 5
                    self.create_line(x, y - h, x, y + h, fill=(FG if major else DIM), width=1)
                    if major:
                        self.create_text(x, y + 18, text=str(t), fill=DIM, font=("Consolas", 7))
            x = self._x(self.value)
            self.create_polygon(x, y - 12, x - 6, y - 22, x + 6, y - 22, fill=self.accent, outline=BG)
            self.create_line(x, y - 12, x, y + 12, fill=self.accent, width=2)
            self.create_text(self._pw - 6, 12, anchor="e", fill=self.accent,
                             font=("Consolas", 12, "bold"), text=self.fmt(self.value))

    # ---- layout: left map, right instruments, bottom controls ----
    main = tk.Frame(root, bg=BG); main.pack(fill="both", expand=True, padx=8, pady=8)

    map_widget = TkinterMapView(main, width=560, height=460, corner_radius=0)
    map_widget.grid(row=0, column=0, rowspan=2, sticky="nsew", padx=(0, 8))
    map_widget.set_tile_server(*TILE_SERVERS["OpenStreetMap"][:1],
                               max_zoom=TILE_SERVERS["OpenStreetMap"][1])
    map_widget.set_position(boat.lat, boat.lon)
    map_widget.set_zoom(16)  # ~300 m across the scale bar at San Diego latitude

    def place_boat(coords):
        la, lo = coords
        boat.teleport(la, lo)
        state["track"] = [(la, lo)]
        state["recenter"] = True
    map_widget.add_right_click_menu_command("Set boat position here", place_boat, pass_coords=True)

    # zoom +/- buttons, overlaid top-right of the map
    def zoom_by(d):
        try:
            map_widget.set_zoom(max(2, min(19, int(round(map_widget.zoom)) + d)))
        except Exception:
            pass
    zbox = tk.Frame(map_widget, bg=PANEL)
    zbox.place(relx=1.0, rely=0.0, anchor="ne", x=-10, y=10)
    tk.Button(zbox, text="+", command=lambda: zoom_by(1), width=2, font=("Consolas", 15, "bold"),
              bg="#22303a", fg=FG, relief="flat", activebackground=ACC, activeforeground=BG).pack(pady=(0, 3))
    tk.Button(zbox, text="−", command=lambda: zoom_by(-1), width=2, font=("Consolas", 15, "bold"),
              bg="#22303a", fg=FG, relief="flat", activebackground=ACC, activeforeground=BG).pack()

    # scale bar, overlaid bottom-left of the map
    scale_cv = tk.Canvas(map_widget, width=300, height=30, bg="#0d1216", highlightthickness=0)
    scale_cv.place(relx=0.0, rely=1.0, anchor="sw", x=8, y=-8)

    NICE_M = [10, 20, 30, 50, 75, 100, 150, 200, 300, 500, 750,
              1000, 1500, 2000, 3000, 5000, 10000]

    def draw_scale():
        try:
            clat = map_widget.get_position()[0]
            z = map_widget.zoom
        except Exception:
            return
        mpp = 156543.03392 * math.cos(math.radians(clat)) / (2 ** z)  # metres / pixel
        if mpp <= 0:
            return
        max_px = 200.0
        chosen = NICE_M[0]
        for d in NICE_M:
            if d / mpp <= max_px:
                chosen = d
            else:
                break
        px = chosen / mpp
        txt = f"{chosen} m" if chosen < 1000 else f"{chosen / 1000:g} km"
        nm = chosen / 1852.0
        scale_cv.delete("all")
        y, x0 = 20, 10
        scale_cv.create_line(x0, y, x0 + px, y, fill=FG, width=3)
        scale_cv.create_line(x0, y - 5, x0, y + 5, fill=FG, width=2)
        scale_cv.create_line(x0 + px, y - 5, x0 + px, y + 5, fill=FG, width=2)
        scale_cv.create_text(x0, y - 8, anchor="sw", fill=FG, font=("Consolas", 9),
                             text=f"{txt}  ({nm:.2f} nm)")

    inst = tk.Frame(main, bg=PANEL, padx=14, pady=10); inst.grid(row=0, column=1, sticky="nsew")
    main.grid_columnconfigure(0, weight=1); main.grid_rowconfigure(0, weight=1)

    # big instrument readouts
    def big(row, name, unit):
        label(inst, name, fg=DIM, font=("Consolas", 10)).grid(row=row, column=0, sticky="w")
        v = label(inst, "--", fg=FG, font=("Consolas", 30, "bold"))
        v.grid(row=row + 1, column=0, sticky="w")
        label(inst, unit, fg=DIM, font=("Consolas", 9)).grid(row=row + 1, column=1, sticky="sw", padx=(6, 0))
        return v

    v_sog = big(0, "SOG", "kn")
    v_cog = big(2, "COG", "°")
    v_pos = big(4, "POSITION", "")
    v_pos.configure(font=("Consolas", 15, "bold"))
    v_hdg = big(6, "HEADING / ROT", "")
    v_hdg.configure(font=("Consolas", 15, "bold"))

    # ---- bottom control strip ----
    ctl = tk.Frame(root, bg=PANEL, padx=10, pady=8); ctl.pack(fill="x", padx=8, pady=(0, 8))

    # Throttle is a percentage of full ahead (9 kn) / full astern (4 kn).
    def pct_to_kn(pct):
        return pct / 100.0 * (MAX_FWD_KN if pct >= 0 else MAX_REV_KN)
    def kn_to_pct(kn):
        return kn / (MAX_FWD_KN if kn >= 0 else MAX_REV_KN) * 100.0
    def thr_fmt(pct):
        if abs(pct) < 0.5:
            return "STOP"
        d = "AHEAD" if pct > 0 else "ASTERN"
        return f"{abs(pct):.0f}% {d}  {abs(pct_to_kn(pct)):.1f} kn"
    def helm_fmt(deg):
        side = "PORT" if deg < -0.5 else ("STBD" if deg > 0.5 else "MID")
        return f"{abs(deg):.0f}° {side}"

    # throttle
    label(ctl, "THROTTLE  ·  drag to set, double-click = stop", fg=DIM,
          font=("Consolas", 9)).grid(row=0, column=0, sticky="w")
    throttle = LeverControl(ctl, vmax=100, ticks=[5, 10, 25, 50, 75, 100],
                            labels={25, 50, 75, 100}, fmt=thr_fmt,
                            on_change=lambda pct: setattr(boat, "ordered_speed", pct_to_kn(pct)),
                            accent="#6fdc8c")
    throttle.grid(row=1, column=0, sticky="we", padx=(0, 16))

    # rudder / helm (drag to set, double-click to centre, ticks 5..90°)
    label(ctl, "HELM  ·  drag to set, double-click = centre", fg=DIM,
          font=("Consolas", 9)).grid(row=0, column=1, sticky="w")
    helm = LeverControl(ctl, vmax=RUDDER_MAX, ticks=[5, 10, 15, 30, 45, 60, 75, 90],
                        labels={15, 30, 45, 60, 75, 90}, fmt=helm_fmt,
                        on_change=lambda ang: setattr(boat, "rudder_target", ang),
                        accent="#ffd24d")
    helm.grid(row=1, column=1, sticky="we", padx=(0, 16))

    # current
    cur = tk.Frame(ctl, bg=PANEL); cur.grid(row=0, column=2, rowspan=2, sticky="w")
    label(cur, "CURRENT", fg=DIM, font=("Consolas", 9)).grid(row=0, column=0, columnspan=2, sticky="w")
    label(cur, "set °", fg=DIM, font=("Consolas", 9)).grid(row=1, column=0, sticky="e")
    set_var = tk.StringVar(value="0")
    tk.Entry(cur, textvariable=set_var, width=5, bg=BG, fg=FG, insertbackground=FG,
             relief="flat").grid(row=1, column=1, padx=4)
    label(cur, "drift kn", fg=DIM, font=("Consolas", 9)).grid(row=2, column=0, sticky="e")
    drift_var = tk.StringVar(value="0.0")
    tk.Entry(cur, textvariable=drift_var, width=5, bg=BG, fg=FG, insertbackground=FG,
             relief="flat").grid(row=2, column=1, padx=4)

    def apply_current(*_):
        try: boat.current_set = _wrap360(float(set_var.get()))
        except ValueError: pass
        try: boat.current_drift = max(0.0, float(drift_var.get()))
        except ValueError: pass
    set_var.trace_add("write", apply_current)
    drift_var.trace_add("write", apply_current)

    ctl.grid_columnconfigure(0, weight=1); ctl.grid_columnconfigure(1, weight=1)

    # ---- top status + presets + map controls ----
    top = tk.Frame(root, bg=BG); top.pack(fill="x", padx=8, pady=(8, 0))
    status = label(top, "", fg=DIM, bg=BG, font=("Consolas", 10)); status.pack(side="left")

    preset_var = tk.StringVar(value=DEFAULT_PRESET)
    def on_preset(*_):
        la, lo = PRESETS[preset_var.get()]
        boat.teleport(la, lo)
        boat.speed = 0.0; boat.ordered_speed = 0.0; boat.heading = 0.0
        boat.rudder = boat.rudder_target = 0.0
        throttle.set_value(0.0); helm.set_value(0.0)
        state["track"] = [(la, lo)]
        state["recenter"] = True

    # map tile source
    tile_var = tk.StringVar(value="OpenStreetMap")
    def on_tiles(*_):
        url, mz = TILE_SERVERS[tile_var.get()]
        map_widget.set_tile_server(url, max_zoom=mz)
    ttk.Combobox(top, textvariable=tile_var, values=list(TILE_SERVERS), state="readonly",
                 width=15).pack(side="right", padx=(6, 0))
    tile_var.trace_add("write", on_tiles)
    label(top, "Map:", fg=DIM, bg=BG, font=("Consolas", 10)).pack(side="right", padx=(12, 4))

    # follow toggle
    follow_var = tk.BooleanVar(value=True)
    tk.Checkbutton(top, text="Follow boat", variable=follow_var, bg=BG, fg=DIM,
                   selectcolor=PANEL, activebackground=BG, activeforeground=FG,
                   font=("Consolas", 10), highlightthickness=0, bd=0).pack(side="right", padx=(12, 0))

    ttk.Combobox(top, textvariable=preset_var, values=list(PRESETS), state="readonly",
                 width=24).pack(side="right", padx=(6, 0))
    preset_var.trace_add("write", on_preset)
    label(top, "Start:", fg=DIM, bg=BG, font=("Consolas", 10)).pack(side="right", padx=(12, 4))

    # ---- buttons ----
    btns = tk.Frame(root, bg=BG); btns.pack(fill="x", padx=8, pady=(6, 0))
    def mkbtn(text, cmd):
        return tk.Button(btns, text=text, command=cmd, bg="#22303a", fg=FG, relief="flat",
                         activebackground=ACC, activeforeground=BG, font=("Consolas", 10), padx=10)
    def toggle_tx():
        state["running"] = not state["running"]
        tx_btn.configure(text="■ Stop TX" if state["running"] else "▶ Start TX")
    tx_btn = mkbtn("■ Stop TX", toggle_tx); tx_btn.pack(side="left", padx=(0, 6))
    mkbtn("All stop (Space)", lambda: (setattr(boat, "ordered_speed", 0.0), throttle.set_value(0.0))).pack(side="left", padx=6)
    mkbtn("Centre helm (R)", lambda: helm.center()).pack(side="left", padx=6)
    mkbtn("Reset track", lambda: state.__setitem__("track", [(boat.lat, boat.lon)])).pack(side="left", padx=6)
    label(btns, "  ↑↓ throttle   ←→ helm (hold)   0-9 set kn   Space stop   R centre   ·   right-click map = place boat",
          fg=DIM, bg=BG, font=("Consolas", 9)).pack(side="left", padx=10)

    # ---- keyboard (bind_all so keys work whatever holds focus, except text fields) ----
    def _typing():
        return isinstance(root.focus_get(), (tk.Entry, ttk.Entry, tk.Spinbox))
    def key_down(e):
        if _typing():
            return
        k = e.keysym
        state["keys"].add(k)
        if k == "space":
            boat.ordered_speed = 0.0
        elif k in ("r", "R"):
            boat.rudder_target = 0.0
        elif len(k) == 1 and k.isdigit():
            boat.ordered_speed = float(k)
    def key_up(e):
        state["keys"].discard(e.keysym)
    root.bind_all("<KeyPress>", key_down)
    root.bind_all("<KeyRelease>", key_up)
    root.focus_set()

    # ---- physics + render loop ----
    last = [time.perf_counter()]

    def refresh_map():
        blat, blon = boat.lat, boat.lon
        # boat marker (a pin at the position)
        if mapobj["marker"] is None:
            mapobj["marker"] = map_widget.set_marker(
                blat, blon, text="BOAT", text_color="#ffd24d",
                marker_color_circle="#8a6d00", marker_color_outside="#ffd24d")
        else:
            mapobj["marker"].set_position(blat, blon)

        # heading vector: a fixed on-screen length (~80 px) whatever the zoom, so it
        # doesn't shoot off the map when you zoom in.
        try:
            mpp = 156543.03392 * math.cos(math.radians(blat)) / (2 ** map_widget.zoom)
        except Exception:
            mpp = 2.0
        alat, alon = _offset(blat, blon, boat.heading, max(15.0, 80.0 * mpp))
        if mapobj["head"] is None:
            mapobj["head"] = map_widget.set_path([(blat, blon), (alat, alon)],
                                                 color="#ffd24d", width=3)
        else:
            mapobj["head"].set_position_list([(blat, blon), (alat, alon)])

        # track (last stretch of the path travelled)
        pts = state["track"][-800:]
        if len(pts) >= 2:
            if mapobj["track"] is None:
                mapobj["track"] = map_widget.set_path(pts, color=TRK, width=2)
            else:
                mapobj["track"].set_position_list(pts)
        elif mapobj["track"] is not None:
            mapobj["track"].delete(); mapobj["track"] = None

        # keep the boat centred while following (or after a teleport)
        if follow_var.get() or state["recenter"]:
            map_widget.set_position(blat, blon)
            state["recenter"] = False

        draw_scale()

    def fmt_pos(la, lo):
        def dm(v, pos, neg):
            h = pos if v >= 0 else neg
            v = abs(v); d = int(v); m = (v - d) * 60
            return f"{d:02d}°{m:06.3f}'{h}"
        return dm(la, "N", "S") + "  " + dm(lo, "E", "W")

    def tick():
        now = time.perf_counter()
        dt = min(0.2, now - last[0]); last[0] = now

        # Held-arrow steering ramps the commanded rudder while held and springs the helm
        # back to centre on release (edge-triggered, so a rudder set with the helm widget
        # stays put instead of being zeroed every tick).
        keys = state["keys"]
        turning = "Left" in keys or "Right" in keys
        if "Left" in keys:
            boat.rudder_target = max(-RUDDER_MAX, boat.rudder_target - RUDDER_KEY_RATE * dt)
        if "Right" in keys:
            boat.rudder_target = min(RUDDER_MAX, boat.rudder_target + RUDDER_KEY_RATE * dt)
        if not turning and state["key_steer"]:
            boat.rudder_target = 0.0        # arrows just released -> centre the helm
        state["key_steer"] = turning
        if "Up" in keys:
            boat.ordered_speed = min(SPEED_MAX, boat.ordered_speed + 2.0 * dt)
        if "Down" in keys:
            boat.ordered_speed = max(SPEED_MIN, boat.ordered_speed - 2.0 * dt)

        boat.step(dt)

        # track sampling
        if now - state["last_track_t"] > 0.4:
            state["track"].append((boat.lat, boat.lon))
            if len(state["track"]) > 3000:
                state["track"] = state["track"][-3000:]
            state["last_track_t"] = now

        # Reflect state on the levers (they ignore this while being dragged). The throttle
        # shows the ORDERED setting (it's a lever, it stays put); the helm shows the actual
        # rudder (it eases quickly). Both no-op mid-drag.
        throttle.set_value(kn_to_pct(boat.ordered_speed))
        helm.set_value(boat.rudder)

        # readouts
        v_sog.configure(text=f"{boat.sog:.1f}")
        v_cog.configure(text=f"{boat.cog:03.0f}")
        v_pos.configure(text=fmt_pos(boat.lat, boat.lon))
        v_hdg.configure(text=f"{boat.heading:03.0f}°   {boat.rot:+.1f}°/s")

        # map visuals at ~5 Hz (tiles + paths are heavier than the readouts)
        if now - state["last_map_t"] >= 0.2:
            state["last_map_t"] = now
            refresh_map()

        # transmit at tx_hz
        if state["running"] and now - state["last_tx_t"] >= 1.0 / state["tx_hz"]:
            state["last_tx_t"] = now
            threading.Thread(target=tx.send, args=(boat.lat, boat.lon, boat.sog, boat.cog),
                             daemon=True).start()

        # status
        ser = tx.serial or "?"
        if not state["running"]:
            status.configure(text=f"● TX paused   — {ser}", fg=DIM)
        elif tx.last_ok:
            status.configure(text=f"● streaming → {PACKAGE} @ {state['tx_hz']:g} Hz   — {ser}", fg=OK)
        elif tx.last_ok is False:
            status.configure(text=f"● send error: {tx.last_err}   — {ser}", fg=BAD)
        else:
            status.configure(text=f"● connecting… — {ser}", fg=DIM)

        root.after(50, tick)

    # ---- map interaction: pan + double-click to place ----
    mask = LandMask()

    # Dragging the map turns off Follow so the pan sticks (otherwise the boat-
    # centring in refresh_map snaps the view straight back).
    map_widget.canvas.bind("<B1-Motion>", lambda e: follow_var.get() and follow_var.set(False), add="+")

    def on_double(e):
        try:
            lat, lon = map_widget.convert_canvas_coords_to_decimal_coords(e.x, e.y)
        except Exception:
            return
        status, depth = mask.classify(lat, lon)
        pos = fmt_pos(lat, lon)
        if status == "land":
            # Charted land: refuse to place the boat there, just sound an error chime.
            try:
                import winsound
                winsound.MessageBeep(winsound.MB_ICONHAND)
            except Exception:
                root.bell()
            return
        if status == "outside":
            ok = messagebox.askyesno(
                "Move boat here?",
                f"{pos}\n\nOutside the San Diego chart area — can't verify water.\n"
                f"Move the boat here?")
        else:
            d = f"charted depth ~{depth:.1f} m" if depth is not None else "deep water"
            ok = messagebox.askyesno(
                "Move boat here?",
                f"{pos}\n\nWater — {d}.\nMove the boat here?")
        if ok:
            place_boat((lat, lon))
        root.focus_set()

    map_widget.canvas.bind("<Double-Button-1>", on_double, add="+")

    # pick a device if none set
    if not tx.serial:
        devs = tx.devices()
        tx.serial = devs[0] if devs else None

    root.after(100, tick)
    if auto_close_ms:
        root.after(int(auto_close_ms), root.destroy)
    root.mainloop()


# --- entry point -------------------------------------------------------------

def main():
    ap = argparse.ArgumentParser(description="MyNavvy boat simulator / helm")
    ap.add_argument("--demo", type=float, metavar="SECONDS",
                    help="run headless and drive an S-turn for N seconds (pipeline test)")
    ap.add_argument("--list", action="store_true", help="list adb devices and exit")
    ap.add_argument("--smoke", type=float, metavar="SECONDS",
                    help="launch the GUI and auto-close after N seconds (self-test)")
    ap.add_argument("--serial", help="target a specific adb device serial")
    ap.add_argument("--adb", default=ADB, help="path to adb")
    args = ap.parse_args()

    tx = Transmitter(adb=args.adb, serial=args.serial)

    if args.list:
        devs = tx.devices()
        print("adb:", tx.adb)
        print("devices:", devs or "(none)")
        return 0
    if args.demo:
        return run_demo(args.demo, tx)
    if args.smoke:
        run_gui(tx, auto_close_ms=args.smoke * 1000)
        return 0
    run_gui(tx)
    return 0


if __name__ == "__main__":
    sys.exit(main())
