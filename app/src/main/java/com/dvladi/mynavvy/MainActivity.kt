package com.dvladi.mynavvy

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Point
import android.graphics.RectF
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.InputDevice
import android.view.MotionEvent
import android.content.res.Configuration
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.updateLayoutParams
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.runtime.mutableStateOf
import com.dvladi.mynavvy.databinding.ActivityMainBinding
import com.dvladi.mynavvy.game.NavData
import com.dvladi.mynavvy.screens.NavHudScreen
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.location.CompassEngine
import org.maplibre.android.location.CompassListener
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.LocationComponentOptions
import org.maplibre.android.location.OnCameraTrackingChangedListener
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * MyNavvy — Phase 0/1/2.
 *
 * Offline NOAA chart + live GPS, plus route planning (long-press waypoints, distance/bearing/ETA)
 * and track recording with GPX export. Location is decoupled from style loading.
 */
class MainActivity : AppCompatActivity(), WatchService.Fixes {

    private lateinit var binding: ActivityMainBinding
    private var map: MapLibreMap? = null
    private var loadedStyle: Style? = null
    private var depthLabelsOn = true   // depth contours + sounding numbers (toolbar-toggled, chart + nav)
    private var tileServer: MbTilesServer? = null
    /** Optional OSM land basemap (roads/towns/inland water), served on its own port. Absent is fine. */
    private var basemapServer: MbTilesServer? = null

    // Always-on GPS/anchor/track service — the Activity binds to observe its clean fixes.
    private var watch: WatchService? = null
    private var watchBound = false
    private val watchConn = object : android.content.ServiceConnection {
        override fun onServiceConnected(name: android.content.ComponentName?, service: android.os.IBinder?) {
            watch = (service as? WatchService.LocalBinder)?.service
            watch?.setCallback(this@MainActivity)
            watchBound = true
        }
        override fun onServiceDisconnected(name: android.content.ComponentName?) { watch = null; watchBound = false }
    }
    private var locationManager: LocationManager? = null
    private var lastLocation: Location? = null
    /** Course over ground, computed by MyNavvy from successive fixes (a phone fix carries no
     *  reliable course of its own). Held across near-stationary fixes; null until first motion. */
    private var cogDeg: Double? = null

    // --- GPS glitch filter state (see onLocationChanged) ---
    /** elapsedRealtime (ms) of the last ACCEPTED fix, for the implied-speed check. */
    private var lastFixMs: Long = 0L
    /** When the current run of rejected (glitchy) fixes began; 0 = not currently rejecting. Used only
     *  as a freeze backstop: after a prolonged rejection we resync rather than stay stuck forever. */
    private var rejectSinceMs: Long = 0L

    /** Drives the location puck's direction pointer from our computed COG instead of the phone
     *  magnetometer (which reads where the phone faces, not the boat's course — and is noise on the
     *  emulator). Keeps the puck look of COMPASS render mode while pointing along COG. */
    private val cogCompass = object : CompassEngine {
        private val listeners = ArrayList<CompassListener>()
        private var heading = 0f
        override fun addCompassListener(l: CompassListener) { listeners.add(l) }
        override fun removeCompassListener(l: CompassListener) { listeners.remove(l) }
        override fun getLastHeading(): Float = heading
        override fun getLastAccuracySensorStatus(): Int =
            android.hardware.SensorManager.SENSOR_STATUS_ACCURACY_HIGH
        fun setHeading(deg: Float) {
            heading = deg
            for (l in listeners) l.onCompassChanged(deg)
        }
    }

    private var routeManager: RouteManager? = null
    private var cruisingSpeedKn = 6.0
    // Recorded-track history overlay (thin dashed day-tracks). Recording itself is always on in
    // WatchService — the old manual ● record button is gone; this only controls what's DRAWN.
    private var trackHistory: TrackHistory.Overlay? = null
    private var historyTracksN = 5

    private val weatherRepo by lazy { WeatherRepository(this) }
    private var weatherOverlay: WeatherOverlay? = null
    // Where/when the wind grid was last fetched — so we refetch when the view pans/zooms away from it.
    private var lastWxFetchCenter: LatLng? = null
    private var lastWxFetchZoom = 0.0
    private var lastWxFetchMs = 0L
    private var weather: WeatherRepository.Weather? = null
    private var tide: WeatherRepository.Tide? = null
    private var selectedHour = 0

    private val boat = BoatModel()
    private var routingGrid: RoutingGrid? = null

    // Custom vector boat marker — replaces MapLibre's location puck, which doesn't render reliably
    // against this style (see BoatMarker). The LocationComponent stays active for camera boat-follow.
    private var boatMarker: BoatMarker? = null

    // --- Anchor watch ---
    private var anchorWatch: AnchorWatch? = null
    private var markStore: MarkStore? = null // saved POI pins (⚑) — persisted in STATE_PREFS
    private var pendingShare: LatLng? = null // a location shared into the app, applied once chartReady
    private var mapSplit: Pair<String, String>? = null // active Chart/Nav + gauge split (else null)
    private var secondMap: MapLibreMap? = null   // the Nav pane's own map, used only for Chart+Nav
    private var boatMarker2: BoatMarker? = null
    private var secondMapInit = false
    private var lastTouchedMap: MapLibreMap? = null  // which map the zoom buttons drive in a two-map split
    private var secondMapZoom: Double? = null        // user-set zoom for the Nav map (else follows NAV2_RANGE_NM)
    private var secondMapGestureAt = 0L              // pan/gesture time; the follow camera holds off after it

    // --- Trip log accumulator (fed from onFix, persisted, shown on the Trip screen) ---
    private var tripStartMs = 0L             // when the current trip began (first fix after a reset)
    private var tripDistNm = 0.0             // cumulative distance travelled this trip
    private var tripMovingMs = 0L            // time underway (SOG > TRIP_MOVING_KN) — ~ engine hours
    private var tripMaxSogKn = 0.0
    private var tripSogSumKn = 0.0           // Σ SOG over underway fixes → a robust moving average
    private var tripSogCount = 0L
    private var tripLastMs = 0L              // previous fix, for the segment distance/time delta
    private var tripLastLat = Double.NaN
    private var tripLastLon = Double.NaN
    private var anchorMode = false          // on-chart position/swing watch active (panel shown)
    private var stateRestored = false       // one-shot: restore persisted state after the first style load
    private var alarmRadiusM = 50.0 / BoatProfile.M_TO_FT  // watch radius (metres) — default 50 ft
    private var alarmEnabled = true
    private var anchorMaxDistM = 0.0         // furthest the boat has moved from the drop this session
    private var unitsFt = false              // display depth / radius in feet
    private var defaultRangeNm = 1.0         // chart range (screen width, nm) at launch / on center-on-boat
    private var alarmActive = false
    private var alarmRingtone: android.media.Ringtone? = null
    private var vibrator: android.os.Vibrator? = null

    // --- Navigation mode (course-up tilted chart + steering data bars) ---
    private var navMode = false
    /** Immutable snapshot the Simrad nav-HUD Compose overlay (@id/navHud) observes; set by refreshNavUi. */
    private val navHudState = mutableStateOf(NavData())

    // --- Auto-recenter (boat-follow) ---
    /** After the user pans/zooms away from the boat, snap back to a boat-centred view this many ms
     *  later (keeping the user's zoom). Runtime-settable so it can be exposed in Configuration. */
    private var recenterTimeoutMs = 10_000L
    private val recenterHandler = Handler(Looper.getMainLooper())
    private val recenterRunnable = Runnable { recenterOnBoat() }
    private var trackingListenerAdded = false
    private var navActiveWp = 0

    // Ticks the compact Wx panel's tide "now" marker forward while the panel is open.
    private val wxHandler = Handler(Looper.getMainLooper())
    private val wxTicker = object : Runnable {
        override fun run() {
            if (binding.weatherPanel.visibility == View.VISIBLE) {
                binding.tideGraph.setNow(System.currentTimeMillis()) // advance the red "now" marker
                wxHandler.postDelayed(this, 30_000)
            }
        }
    }

    /** Held true until the chart has drawn its first frame — the splash waits on this. */
    private var chartReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        // Keep the cold-start splash up until the chart is genuinely ready, not on a timer.
        val splash = installSplashScreen()
        splash.setKeepOnScreenCondition { !chartReady }
        MapLibre.getInstance(this)
        // Every tile source in this app is a localhost MBTiles server (charts + basemap), which is
        // reachable regardless of internet. MapLibre's default connectivity gate, however, refuses
        // ALL tile fetches (even to 127.0.0.1) when the device reports offline — so any never-yet-
        // cached tile stays blank in airplane mode. Force "connected" so offline devices still pull
        // fresh charts/basemap tiles from our own loopback servers. Network calls that genuinely
        // need the internet (weather, data downloads) do their own reachability handling elsewhere.
        MapLibre.setConnected(true)
        super.onCreate(savedInstanceState)

        // Debug-only: `adb shell am start -n com.dvladi.mynavvy/.MainActivity --ez crash true`
        // forces an uncaught crash to validate remote crash reporting end-to-end.
        if (BuildConfig.DEBUG && intent?.getBooleanExtra("crash", false) == true) {
            throw RuntimeException("MyNavvy test crash (adb intent trigger)")
        }
        // Debug-only: `... --ez snapshot true` sends an on-demand log snapshot.
        if (BuildConfig.DEBUG && intent?.getBooleanExtra("snapshot", false) == true) {
            Diagnostics.sendLogSnapshot("MyNavvy on-demand snapshot (adb intent trigger)")
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Simrad nav HUD overlay: transparent Compose Canvas over the live map, observes navHudState.
        // (Keep the DEFAULT composition strategy — see the note in HelmFragment.onCreateView.)
        binding.navHud.setContent { NavHudScreen(navHudState.value) }
        depthLabelsOn = getSharedPreferences(STATE_PREFS, MODE_PRIVATE).getBoolean("depth_labels", true)

        routingGrid = RoutingGrid.load(
            File(getExternalFilesDir(null), "routing_grid.png"),
            File(getExternalFilesDir(null), "routing_grid.json")
        )

        wireControls()

        // Long-press the HUD readout to send a diagnostics snapshot (recent logs)
        // to the remote server — useful for reporting non-crash misbehaviour.
        binding.hud.setOnLongClickListener {
            Diagnostics.sendLogSnapshot("Manual diagnostics snapshot (HUD long-press)")
            Toast.makeText(this, "Diagnostics sent", Toast.LENGTH_SHORT).show()
            true
        }

        binding.mapView.onCreate(savedInstanceState)
        binding.mapView2.onCreate(savedInstanceState)   // second map (Chart+Nav); styled lazily
        binding.mapView.getMapAsync { m ->
            map = m
            setupMap(m)
        }
        // Safety net: never let the splash outstay the map (e.g. a stalled style load).
        binding.root.postDelayed({ chartReady = true }, 3000)
        wireNav()
        applyOrientationChrome(resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE)

        // First run on a fresh install has no charts/routing grid — offer to fetch them.
        maybeFetchData()

        // A location shared INTO MyNavvy (system share / a geo: link) — parse + act once the chart is up.
        handleShareIntent(intent)

        // The debug boat simulator (SIM_FIX broadcast) now lives in WatchService, which owns the
        // whole GPS pipeline — sim fixes go through the same filter / COG / anchor / track path.
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareIntent(intent)
    }

    // --- Navigation (top MENU) ----------------------------------------------
    //
    // The chart is the persistent base layer (a MapView is expensive to recreate, and you always
    // want it "there"); the other screens are fragments overlaid in screenHost. The MENU floats
    // above everything so it's reachable from any screen. Selecting Chart just hides the overlay,
    // so the map keeps its camera and never reloads.

    private fun wireNav() {
        binding.btnMenu.setOnClickListener { toggleMenu() }
        binding.menuChart.setOnClickListener { closeMenu(); selectChart() }
        binding.menuNav.setOnClickListener {
            closeMenu()
            if (mapSplit != null || binding.screenHost.visibility == View.VISIBLE) selectChart()
            enterNavMode()
        }
        binding.menuHelm.setOnClickListener {
            closeMenu()
            showScreen("helm") { HelmFragment() }
        }
        binding.menuWeather.setOnClickListener {
            closeMenu()
            showScreen("weather") { WeatherFragment() }
        }
        binding.menuRoute.setOnClickListener {
            closeMenu()
            showScreen("route") { RouteFragment() }
        }
        binding.menuTrip.setOnClickListener {
            closeMenu()
            showScreen("trip") { TripFragment() }
        }
        binding.menuWind.setOnClickListener {
            closeMenu()
            showScreen("wind") { WindFragment() }
        }
        buildSplitMenu()
        // The Split view tile swaps the main menu for the split-view submenu; ← returns.
        binding.menuSplit.setOnClickListener {
            binding.menuPanel.visibility = View.GONE
            binding.splitMenuPanel.visibility = View.VISIBLE
        }
        binding.splitMenuBack.setOnClickListener {
            binding.splitMenuPanel.visibility = View.GONE
            binding.menuPanel.visibility = View.VISIBLE
        }
        // (Anchor moved out of the menu — it's a ⚓ button on the chart toolbar, see wireControls.)
        binding.menuTracks.setOnClickListener { closeMenu(); showTracksDialog() }
        binding.menuConfig.setOnClickListener { closeMenu(); showConfigDialog() }
        binding.menuAbout.setOnClickListener { closeMenu(); showAboutDialog() }
        selectChart()
    }

    private fun menuOpen() = binding.menuPanel.visibility == View.VISIBLE ||
        binding.splitMenuPanel.visibility == View.VISIBLE

    private fun toggleMenu() {
        if (menuOpen()) closeMenu() else binding.menuPanel.visibility = View.VISIBLE
    }

    private fun closeMenu() {
        binding.menuPanel.visibility = View.GONE
        binding.splitMenuPanel.visibility = View.GONE
    }

    private fun selectChart() {
        if (mapSplit != null) endMapSplit()
        if (navMode) exitNavMode()
        binding.screenHost.visibility = View.GONE
        supportFragmentManager.findFragmentById(R.id.screenHost)?.let {
            supportFragmentManager.beginTransaction().remove(it).commitAllowingStateLoss()
        }
        flashChrome() // back on the chart — surface its tools
    }

    private fun showScreen(tag: String, factory: () -> androidx.fragment.app.Fragment) {
        if (mapSplit != null) endMapSplit()
        if (navMode) exitNavMode()
        // Leaving the chart: retire its toolbar / scale bar / weather panel so they don't linger
        // behind — or bleed onto — the covering screen.
        retireChartUi()
        supportFragmentManager.beginTransaction()
            .replace(R.id.screenHost, factory(), tag)
            .commitAllowingStateLoss()
        binding.screenHost.visibility = View.VISIBLE
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        when {
            // Back out of the split submenu to the main menu, then out of the menu entirely.
            binding.splitMenuPanel.visibility == View.VISIBLE -> {
                binding.splitMenuPanel.visibility = View.GONE
                binding.menuPanel.visibility = View.VISIBLE
            }
            binding.menuPanel.visibility == View.VISIBLE -> closeMenu()
            mapSplit != null -> selectChart()   // ends the split (incl. any nav pane) + clears padding
            navMode -> exitNavMode()
            binding.screenHost.visibility == View.VISIBLE -> selectChart()
            else -> super.onBackPressed()
        }
    }

    // --- Split view (screen combinations) -----------------------------------
    // A split shows TWO screens: stacked in portrait, side-by-side in landscape (the joined-icon
    // pairs in the menu). At most ONE pane may be a map (Chart or Nav) — there's a single MapView, so
    // the map pane is left transparent (the full-screen map shows through) and the camera is padded so
    // the boat sits in that half; the other pane is an opaque gauge. Gauge+gauge splits just tile.

    private data class ScreenDef(val key: String, val label: String, val iconRes: Int, val color: Int)

    private val screenDefs = linkedMapOf(
        "chart" to ScreenDef("chart", "Chart", R.drawable.ic_menu_chart, 0xFF3E7BB6.toInt()),
        "nav" to ScreenDef("nav", "Nav", R.drawable.ic_menu_nav, 0xFF7E6BD6.toInt()),
        "helm" to ScreenDef("helm", "Helm", R.drawable.ic_menu_helm, 0xFFC9922F.toInt()),
        "wind" to ScreenDef("wind", "Wind", R.drawable.ic_menu_wind, 0xFF4E8CA0.toInt()),
        "trip" to ScreenDef("trip", "Trip", R.drawable.ic_menu_trip, 0xFF5AA576.toInt()),
    )
    /** Offered pairs. Order = pane A (top/left) then B (bottom/right). At most one map screen each. */
    private val splitCombos = listOf(
        "chart" to "nav",
        "chart" to "helm", "chart" to "wind", "nav" to "helm", "nav" to "wind",
        "helm" to "wind", "wind" to "trip",
    )

    private fun isMapScreen(key: String) = key == "chart" || key == "nav"

    private fun buildSplitMenu() {
        val list = binding.splitSection
        list.removeAllViews()
        for ((a, b) in splitCombos) list.addView(splitRow(a, b))
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun splitRow(a: String, b: String): View {
        val da = screenDefs.getValue(a); val db = screenDefs.getValue(b)
        return LinearLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(dp(4), dp(4), dp(4), dp(4)) }
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.bg_menu_tile)
            setPadding(dp(12), dp(9), dp(12), dp(9))
            isClickable = true; isFocusable = true
            addView(twinIcon(da, db))
            addView(TextView(this@MainActivity).apply {
                text = "${da.label} + ${db.label}"
                setTextColor(Color.parseColor("#C7D2DC")); textSize = 13f
                setPadding(dp(12), 0, 0, 0)
            })
            setOnClickListener { openSplit(a, b) }
        }
    }

    /** Two rounded, coloured tiles joined side-by-side, each holding a screen's icon (the combo icon). */
    private fun twinIcon(a: ScreenDef, b: ScreenDef): View {
        fun half(def: ScreenDef, leftEnd: Boolean): View {
            val r = dp(4).toFloat()
            val radii = if (leftEnd) floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r)
            else floatArrayOf(0f, 0f, r, r, r, r, 0f, 0f)
            return android.widget.ImageView(this).apply {
                setImageResource(def.iconRes)
                setColorFilter(Color.WHITE)
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(def.color); cornerRadii = radii
                }
                val pad = dp(3); setPadding(pad, pad, pad, pad)
                layoutParams = LinearLayout.LayoutParams(dp(22), dp(26)).apply {
                    marginEnd = if (leftEnd) dp(1) else 0
                }
            }
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(half(a, true)); addView(half(b, false))
        }
    }

    private fun openSplit(a: String, b: String) {
        closeMenu()
        if (isMapScreen(a) || isMapScreen(b)) showMapSplit(a, b)
        else showScreen("split-$a-$b") { SplitScreenFragment.of(a, b) }
    }

    private fun bothMapsActive() =
        mapSplit?.let { isMapScreen(it.first) && isMapScreen(it.second) } == true
    /** The nav HUD is shown either in full nav mode OR as the Nav pane of a Chart+Nav split. */
    private fun navHudActive() = navMode || bothMapsActive()

    /** Chart/Nav + gauge: full-screen map (padded to the map half) under a transparent map pane, with
     *  the opaque gauge in the other pane. Chart+Nav is special — pane A stays the north-up primary
     *  map, pane B is a SECOND course-up map with the nav HUD over it. */
    private fun showMapSplit(a: String, b: String) {
        // A chart pane keeps its toolbar + wind overlay; other panes retire the chart chrome entirely.
        retireChartUi(clearWeather = a != "chart")
        binding.screenHost.setBackgroundColor(Color.TRANSPARENT)
        binding.screenHost.isClickable = false
        supportFragmentManager.beginTransaction()
            .replace(R.id.screenHost, SplitScreenFragment.of(a, b), "split-$a-$b")
            .commitAllowingStateLoss()
        binding.screenHost.visibility = View.VISIBLE
        mapSplit = a to b
        lastTouchedMap = null                              // zoom buttons default to the pane-B map
        if (isMapScreen(a) && isMapScreen(b)) {           // Chart + Nav (two live maps)
            if (navMode) exitNavMode()                    // primary map stays the north-up chart
            ensureSecondMap()
            binding.navHud.visibility = View.VISIBLE       // HUD over the second (Nav) map
            refreshNavUi()
        } else {
            val wantNav = a == "nav" || b == "nav"
            if (wantNav) enterNavMode() else if (navMode) exitNavMode()
        }
        binding.screenHost.post {
            applyMapSplitRegion()
            if (a == "chart") flashChrome()             // surface the chart toolbar in the chart pane
        }
    }

    /** Pad the primary map + size the nav HUD / second map to the pane halves (A = top/left, B =
     *  bottom/right), plus place each map pane's scale bar and bound the chart's data sidebar. */
    private fun applyMapSplitRegion() {
        val (a, b) = mapSplit ?: return
        updateZoomControlsMargins()   // sidebar leaves the right edge → buttons hug it
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val w = binding.contentArea.width; val h = binding.contentArea.height
        if (w == 0 || h == 0) return
        val halfW = w / 2; val halfH = h / 2
        val regionA = if (landscape) intArrayOf(0, 0, halfW, h) else intArrayOf(0, 0, w, halfH)
        val regionB = if (landscape) intArrayOf(halfW, 0, halfW, h) else intArrayOf(0, halfH, w, halfH)

        if (isMapScreen(a) && isMapScreen(b)) {
            // Chart (pane A) = primary map padded into region A; Nav (pane B) = the second map + HUD.
            if (landscape) map?.setPadding(0, 0, halfW, 0) else map?.setPadding(0, 0, 0, halfH)
            binding.mapView2.visibility = View.VISIBLE
            sizeTo(binding.mapView2, regionB)
            sizeTo(binding.navHud, regionB)
            secondMap?.let { binding.mapView2.post { updateSecondMapCamera() } }
            placeScaleBar(binding.scaleBar, regionA, navPane = false)
            map?.let { updateScaleBarOn(binding.scaleBar, it) }
            placeScaleBar(binding.scaleBar2, regionB, navPane = true)
            secondMap?.let { updateScaleBarOn(binding.scaleBar2, it) }
            fitSidebarToPane(regionA)                       // chart data sidebar bounded to the chart pane
        } else {
            val mapIsA = isMapScreen(a)                      // the single map is always pane A
            val region = if (mapIsA) regionA else regionB
            if (landscape) map?.setPadding(if (mapIsA) 0 else halfW, 0, if (mapIsA) halfW else 0, 0)
            else map?.setPadding(0, if (mapIsA) 0 else halfH, 0, if (mapIsA) halfH else 0)
            if (navMode) sizeTo(binding.navHud, region)
            binding.mapView2.visibility = View.GONE
            binding.scaleBar2.visibility = View.GONE
            placeScaleBar(binding.scaleBar, region, navPane = (a == "nav" || b == "nav"))
            map?.let { updateScaleBarOn(binding.scaleBar, it) }
            if (a == "chart") fitSidebarToPane(region) else binding.hudScroll.visibility = View.GONE
        }
    }

    private fun sizeTo(v: View, region: IntArray) = v.updateLayoutParams<FrameLayout.LayoutParams> {
        width = region[2]; height = region[3]; leftMargin = region[0]; topMargin = region[1]
    }

    /** Position a scale bar at the bottom-left of a pane [region]; a nav pane lifts it above the SOG
     *  box. Showing (and auto-hiding) goes through the common flashChrome() policy. */
    private fun placeScaleBar(bar: View, region: IntArray, navPane: Boolean) {
        val regionBottom = region[1] + region[3]
        bar.updateLayoutParams<FrameLayout.LayoutParams> {
            marginStart = region[0] + dp(8)
            bottomMargin = (binding.contentArea.height - regionBottom) +
                dp(if (navPane) NAV_SCALE_BOTTOM_DP else 10)
        }
        flashChrome()
    }

    private fun updateScaleBarOn(bar: ScaleBarView, m: MapLibreMap) {
        val lat = m.cameraPosition.target?.latitude ?: return
        bar.update(m.projection.getMetersPerPixelAtLatitude(lat))
    }

    /** Bound the right data sidebar to a chart pane [region] (top|end anchored). Content-sized —
     *  the panel ends after the tide tile, same as the full-screen chart — but capped so the
     *  zoom/center buttons always fit BELOW it inside the pane (a clipped sidebar scrolls). */
    private fun fitSidebarToPane(region: IntArray) {
        val contentH = binding.hudScroll.getChildAt(0)?.let {
            it.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
            it.measuredHeight + binding.hudScroll.paddingTop + binding.hudScroll.paddingBottom
        } ?: region[3]
        // Room the buttons need under the sidebar: their stack + the 8dp gap + 10dp pane inset.
        val buttonsH = binding.zoomControls.height.takeIf { it > 0 } ?: dp(52 * 3 + 6 * 2)
        val reserved = buttonsH + dp(8 + 10)
        binding.hudScroll.updateLayoutParams<FrameLayout.LayoutParams> {
            height = minOf(contentH, region[3] - reserved)
            topMargin = region[1]
            marginEnd = binding.contentArea.width - (region[0] + region[2])
        }
        binding.hudScroll.visibility = View.VISIBLE
    }

    /** The map the zoom buttons drive: the last-touched map, else (no touch yet) the map under the
     *  buttons — pane B's second map in Chart+Nav, otherwise the primary map. */
    private fun zoomTargetMap(): MapLibreMap? =
        lastTouchedMap ?: if (bothMapsActive()) secondMap else map

    /** Zoom the target map by [delta] levels. The Nav map keeps its zoom in [secondMapZoom] so the
     *  per-fix follow camera doesn't snap it back; the primary map zooms directly. */
    private fun zoomBy(delta: Double) {
        val target = zoomTargetMap() ?: return
        if (target === secondMap) {
            val base = secondMapZoom ?: target.cameraPosition.zoom
            secondMapZoom = (base + delta).coerceIn(3.0, 20.0)
            updateSecondMapCamera()
        } else {
            target.animateCamera(
                if (delta > 0) CameraUpdateFactory.zoomIn() else CameraUpdateFactory.zoomOut(), 200)
        }
    }

    /** Tear down a map split: restore the opaque full-screen screenHost, hide the second map / scale
     *  bars / HUD, clear padding + restore full-screen sizes and the sidebar. */
    private fun endMapSplit() {
        mapSplit = null
        lastTouchedMap = null
        secondMapZoom = null
        binding.screenHost.setBackgroundColor(Color.parseColor("#0b0f13"))
        binding.screenHost.isClickable = true
        map?.setPadding(0, 0, 0, 0)
        binding.mapView2.visibility = View.GONE
        if (!navMode) binding.navHud.visibility = View.GONE
        binding.scaleBar.visibility = View.GONE
        binding.scaleBar2.visibility = View.GONE
        resetScaleBar(binding.scaleBar)
        sizeTo(binding.mapView2, intArrayOf(0, 0,
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        sizeTo(binding.navHud, intArrayOf(0, 0,
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        binding.hudScroll.updateLayoutParams<FrameLayout.LayoutParams> { topMargin = 0; marginEnd = 0 }
        applyOrientationChrome(resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE)
    }

    private fun resetScaleBar(bar: View) {
        bar.updateLayoutParams<FrameLayout.LayoutParams> {
            marginStart = dp(8); bottomMargin = dp(10)
        }
    }

    // --- Second map (Chart+Nav's course-up Nav pane) ------------------------

    /** Lazily bring up the second MapView with the same chart style + a boat marker, then follow. */
    private fun ensureSecondMap() {
        if (secondMapInit) { updateSecondMapCamera(); return }
        secondMapInit = true
        binding.mapView2.getMapAsync { m2 ->
            secondMap = m2
            m2.uiSettings.isLogoEnabled = false
            m2.uiSettings.isAttributionEnabled = false
            m2.uiSettings.setAllGesturesEnabled(true)
            // Touching / gesturing the Nav map makes the zoom buttons target it.
            m2.addOnMapClickListener { lastTouchedMap = m2; flashChrome(); false }
            m2.addOnMoveListener(object : MapLibreMap.OnMoveListener {
                override fun onMoveBegin(d: org.maplibre.android.gestures.MoveGestureDetector) {
                    lastTouchedMap = m2; secondMapGestureAt = android.os.SystemClock.elapsedRealtime()
                    flashChrome()
                }
                override fun onMove(d: org.maplibre.android.gestures.MoveGestureDetector) {}
                override fun onMoveEnd(d: org.maplibre.android.gestures.MoveGestureDetector) {
                    secondMapGestureAt = android.os.SystemClock.elapsedRealtime()
                }
            })
            m2.addOnScaleListener(object : MapLibreMap.OnScaleListener {
                override fun onScaleBegin(d: org.maplibre.android.gestures.StandardScaleGestureDetector) {
                    lastTouchedMap = m2; secondMapGestureAt = android.os.SystemClock.elapsedRealtime()
                    flashChrome()
                }
                override fun onScale(d: org.maplibre.android.gestures.StandardScaleGestureDetector) {}
                override fun onScaleEnd(d: org.maplibre.android.gestures.StandardScaleGestureDetector) {
                    secondMapZoom = m2.cameraPosition.zoom   // keep a pinch-zoom against the follow camera
                }
            })
            m2.addOnCameraMoveListener { updateScaleBarOn(binding.scaleBar2, m2) }
            val srv = tileServer
            val builder = when {
                srv != null -> chartStyleJson(srv, basemapServer)?.let { Style.Builder().fromJson(it) }
                    ?: Style.Builder().fromUri("asset://style.json")
                else -> Style.Builder().fromUri("asset://empty_style.json")
            }
            m2.setStyle(builder) { style ->
                boatMarker2 = BoatMarker(style)
                updateSecondMapCamera()
            }
        }
    }

    /** Drive the second map course-up on the boat (north-up + flat on the emulator to dodge the GL
     *  crash, exactly like the primary nav camera). */
    private fun updateSecondMapCamera() {
        val m2 = secondMap ?: return
        val loc = lastLocation ?: return
        val bearing = if (isEmulator()) 0.0 else (cogDeg ?: 0.0)
        val pos = CameraPosition.Builder()
            .target(LatLng(loc.latitude, loc.longitude))
            .bearing(bearing)
            .tilt(navTiltDeg())
            .zoom(secondMapZoom ?: zoomForRangeNm(NAV2_RANGE_NM, loc.latitude))
            .build()
        m2.moveCamera(CameraUpdateFactory.newCameraPosition(pos))
        boatMarker2?.update(loc.latitude, loc.longitude, cogDeg, metersPerPixelNow(m2))
        updateScaleBarOn(binding.scaleBar2, m2)
    }

    // --- About / credits ----------------------------------------------------

    /** App version + the map-data, weather and font attributions. Reaching the OSM/OpenMapTiles/
     *  MapLibre credits here (☰ → About, two taps) keeps them accessible per OSM's attribution
     *  guidance while leaving the chart itself clean. */
    private fun showAboutDialog() {
        val v = layoutInflater.inflate(R.layout.dialog_about, null)
        v.findViewById<android.widget.TextView>(R.id.tvAboutVersion).text =
            "Version ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})"
        v.findViewById<android.widget.TextView>(R.id.tvAboutBody).text = buildString {
            append("A personal offline chartplotter.\n\n")
            append("— Charts —\n")
            append("NOAA ENC® — U.S. Office of Coast Survey (public domain)\n\n")
            append("— Land map —\n")
            append("© OpenStreetMap contributors — map data under ODbL\n")
            append("© OpenMapTiles — vector tile schema (CC BY 4.0)\n")
            append("Rendered with MapLibre GL (BSD-3-Clause)\n\n")
            append("— Weather & tides —\n")
            append("Wind forecast: Open-Meteo (CC BY 4.0)\n")
            append("Tide predictions: NOAA CO-OPS (public domain)\n\n")
            append("— Typography —\n")
            append("Noto Sans — Google (SIL Open Font License 1.1)")
        }
        val dlg = androidx.appcompat.app.AlertDialog.Builder(this).setView(v).create()
        v.findViewById<android.widget.Button>(R.id.btnAboutClose).setOnClickListener { dlg.dismiss() }
        dlg.show()
    }

    // --- Configuration dialog (mode + boat + data) --------------------------

    private fun showConfigDialog() {
        val v = layoutInflater.inflate(R.layout.dialog_config, null)
        val dlg = androidx.appcompat.app.AlertDialog.Builder(this).setView(v).create()

        val sail = v.findViewById<android.widget.Button>(R.id.btnModeSail)
        val motor = v.findViewById<android.widget.Button>(R.id.btnModeMotor)
        val mtrSail = v.findViewById<android.widget.Button>(R.id.btnModeMotorSail)
        // Selected = black (max contrast on the light button face), unselected = blue.
        fun paintMode() {
            sail.setTextColor(if (boat.mode == BoatMode.SAIL) Color.BLACK else Color.parseColor("#6fc6e8"))
            motor.setTextColor(if (boat.mode == BoatMode.MOTOR) Color.BLACK else Color.parseColor("#6fc6e8"))
            mtrSail.setTextColor(if (boat.mode == BoatMode.MOTORSAIL) Color.BLACK else Color.parseColor("#6fc6e8"))
        }
        sail.setOnClickListener { boat.mode = BoatMode.SAIL; paintMode() }
        motor.setOnClickListener { boat.mode = BoatMode.MOTOR; paintMode() }
        mtrSail.setOnClickListener { boat.mode = BoatMode.MOTORSAIL; paintMode() }
        paintMode()

        // Units: Metric / Imperial — affects depth, clearance and tide (vertical measures) plus the
        // close-in scale bar. Persisted to the boat profile; applied live.
        val profile = BoatProfile.load(this)
        val unitMetric = v.findViewById<android.widget.Button>(R.id.btnUnitsMetric)
        val unitImperial = v.findViewById<android.widget.Button>(R.id.btnUnitsImperial)
        fun paintUnits() {
            unitMetric.setTextColor(if (!unitsFt) Color.BLACK else Color.parseColor("#6fc6e8"))
            unitImperial.setTextColor(if (unitsFt) Color.BLACK else Color.parseColor("#6fc6e8"))
        }
        fun setUnits(ft: Boolean) {
            if (unitsFt == ft) return
            unitsFt = ft
            profile.units = if (ft) "ft" else "m"
            BoatProfile.save(this, profile)
            binding.scaleBar.setUseFeet(ft)
            map?.let { updateScaleBar(it) }
            updateHud()
            binding.tideGraph.useFeet = ft; binding.tideGraph.invalidate() // live-refresh Wx-panel graph
            if (anchorMode) refreshAnchorUi()
            paintUnits()
        }
        unitMetric.setOnClickListener { setUnits(false) }
        unitImperial.setOnClickListener { setUnits(true) }
        paintUnits()

        // Default range: chart width (nm) the map opens at, and what center-on-boat snaps back to.
        // Steps through preset ranges; persisted, and applied live to the current view.
        val ranges = listOf(0.25, 0.5, 0.75, 1.0, 1.5, 2.0, 3.0, 5.0, 8.0, 12.0)
        val tvRange = v.findViewById<android.widget.TextView>(R.id.tvRangeValue)
        fun fmtRange(nm: Double) =
            if (nm < 1.0) String.format(Locale.US, "%.2f nm", nm) else String.format(Locale.US, "%.1f nm", nm)
        fun stepRange(dir: Int) {
            val idx = ranges.indexOfFirst { it >= profile.defaultRangeNm - 1e-6 }
                .let { if (it < 0) ranges.lastIndex else it }
            profile.defaultRangeNm = ranges[(idx + dir).coerceIn(0, ranges.lastIndex)]
            defaultRangeNm = profile.defaultRangeNm
            BoatProfile.save(this, profile)
            tvRange.text = fmtRange(defaultRangeNm)
            map?.let { m -> m.animateCamera(CameraUpdateFactory.zoomTo(
                zoomForRangeNm(defaultRangeNm, m.cameraPosition.target?.latitude ?: SAN_DIEGO.latitude)), 300) }
        }
        tvRange.text = fmtRange(profile.defaultRangeNm)
        v.findViewById<android.widget.Button>(R.id.btnRangeDown).setOnClickListener { stepRange(-1) }
        v.findViewById<android.widget.Button>(R.id.btnRangeUp).setOnClickListener { stepRange(+1) }

        // History tracks: how many recorded day-tracks are drawn on the chart. Applied live.
        val histSteps = listOf(0, 1, 2, 3, 5, 7, 14, 30)
        val tvHist = v.findViewById<android.widget.TextView>(R.id.tvHistValue)
        fun fmtHist(n: Int) = if (n == 0) "Off" else n.toString()
        fun stepHist(dir: Int) {
            val idx = histSteps.indexOfFirst { it >= historyTracksN }
                .let { if (it < 0) histSteps.lastIndex else it }
            historyTracksN = histSteps[(idx + dir).coerceIn(0, histSteps.lastIndex)]
            getSharedPreferences(STATE_PREFS, MODE_PRIVATE).edit()
                .putInt("history_tracks", historyTracksN).apply()
            tvHist.text = fmtHist(historyTracksN)
            reloadTrackHistory()
        }
        tvHist.text = fmtHist(historyTracksN)
        v.findViewById<android.widget.Button>(R.id.btnHistDown).setOnClickListener { stepHist(-1) }
        v.findViewById<android.widget.Button>(R.id.btnHistUp).setOnClickListener { stepHist(+1) }

        v.findViewById<android.widget.Button>(R.id.btnCfgBoat).setOnClickListener {
            dlg.dismiss(); startActivity(android.content.Intent(this, BoatConfigActivity::class.java))
        }
        v.findViewById<android.widget.Button>(R.id.btnCfgOffline).setOnClickListener {
            dlg.dismiss(); fetchOfflineData()
        }
        v.findViewById<android.widget.Button>(R.id.btnCfgLandMap).setOnClickListener {
            dlg.dismiss(); prefetchBasemapHere()
        }
        v.findViewById<android.widget.Button>(R.id.btnCfgReload).setOnClickListener {
            dlg.dismiss(); reloadCharts()
        }
        dlg.show()
    }

    // --- First-run data download (charts + routing grid) ---------------------

    /**
     * On a fresh install the charts are missing and we block on downloading them. On an existing
     * install we still ask the manifest whether the published charts have CHANGED (sha mismatch),
     * because otherwise a re-tiled chart set would never reach the boat — the app would sit on
     * stale tiles forever. The update case is offered, not forced.
     */
    private fun maybeFetchData() {
        val firstRun = DataAssets.dataMissing(this)
        if (firstRun) {
            // No charts, no app. A chartplotter with no chart is worse than useless — it looks
            // like it's working. Block until the data is on the device.
            binding.dataOverlay.visibility = View.VISIBLE
            binding.tvDataStatus.text = "Checking for chart data…"
            binding.btnDataAction.visibility = View.GONE
            binding.btnDataSkip.text = "Exit"
            binding.btnDataSkip.setOnClickListener { finishAndRemoveTask() }
        } else {
            binding.btnDataSkip.text = "Not now"
            binding.btnDataSkip.setOnClickListener { binding.dataOverlay.visibility = View.GONE }
        }

        DataAssets.checkPending(this) { pending, total, err ->
            when {
                err != null -> {
                    // Already have usable charts? Stay quiet — no nagging offshore.
                    if (!firstRun) return@checkPending
                    binding.tvDataStatus.text =
                        "MyNavvy can't start without its charts.\n\nCouldn't reach the chart server:\n$err"
                    showDataAction("Retry") { maybeFetchData() }
                }
                pending.isEmpty() && firstRun -> {
                    binding.tvDataStatus.text =
                        "MyNavvy can't start without its charts.\n\nThe chart server published nothing to download."
                    showDataAction("Retry") { maybeFetchData() }
                }
                pending.isEmpty() -> binding.dataOverlay.visibility = View.GONE
                !firstRun -> {
                    // Optional update on an already-usable install: offer it via the top banner so the
                    // chart stays live and tappable. Tapping Download hands off to the full overlay
                    // (progress bar) which is fine once the user has opted in.
                    val mb = total / 1048576.0
                    binding.dataOverlay.visibility = View.GONE
                    binding.tvUpdateBanner.text = String.format(
                        Locale.US, "Updated charts available · %d file(s), %.0f MB", pending.size, mb
                    )
                    binding.updateBanner.visibility = View.VISIBLE
                    binding.btnUpdateDismiss.setOnClickListener { binding.updateBanner.visibility = View.GONE }
                    binding.btnUpdateNow.setOnClickListener {
                        binding.updateBanner.visibility = View.GONE
                        binding.dataOverlay.visibility = View.VISIBLE
                        binding.btnDataSkip.text = "Not now"
                        binding.btnDataSkip.setOnClickListener { binding.dataOverlay.visibility = View.GONE }
                        startDataDownload(pending, total)
                    }
                }
                else -> {
                    // First run: no charts, no app — block on the full overlay until they download.
                    val mb = total / 1048576.0
                    binding.dataOverlay.visibility = View.VISIBLE
                    binding.tvDataStatus.text = String.format(
                        Locale.US,
                        "MyNavvy needs its offline charts before it can be used.\n\n%d file(s), %.0f MB\n\nDownload over Wi-Fi now?",
                        pending.size, mb
                    )
                    showDataAction("Download") { startDataDownload(pending, total) }
                }
            }
        }
    }

    private fun showDataAction(label: String, onClick: () -> Unit) {
        binding.btnDataAction.visibility = View.VISIBLE
        binding.btnDataAction.isEnabled = true
        binding.btnDataAction.text = label
        binding.btnDataAction.setOnClickListener { onClick() }
    }

    private fun startDataDownload(pending: List<DataAssets.Asset>, total: Long) {
        val firstRun = DataAssets.dataMissing(this)
        binding.btnDataAction.isEnabled = false
        binding.btnDataSkip.visibility = View.GONE
        binding.progressData.visibility = View.VISIBLE
        binding.progressData.progress = 0

        DataAssets.download(this, pending,
            onProgress = { name, done, tot ->
                binding.progressData.progress = if (tot > 0) ((done * 100) / tot).toInt() else 0
                binding.tvDataStatus.text = String.format(
                    Locale.US, "Downloading %s\n%.0f / %.0f MB",
                    name, done / 1048576.0, tot / 1048576.0
                )
            },
            onDone = { ok, msg ->
                if (ok) {
                    Toast.makeText(this, "Charts installed", Toast.LENGTH_SHORT).show()
                    recreate() // re-run onCreate: charts + routing grid now load
                } else {
                    binding.progressData.visibility = View.INVISIBLE
                    binding.btnDataSkip.visibility = View.VISIBLE
                    binding.tvDataStatus.text = if (firstRun)
                        "MyNavvy can't start without its charts.\n\nDownload failed:\n$msg"
                    else "Download failed:\n$msg"
                    showDataAction("Retry") { startDataDownload(pending, total) }
                }
            })
    }

    /**
     * Load the bundled style, but overwrite the chart source's zoom range with what the local
     * MBTiles really contains. Hardcoding it in style.json means a chart file and an APK can
     * disagree — and a style claiming z16 over z14 tiles renders a blank blue screen.
     */
    private fun chartStyleJson(server: MbTilesServer, basemap: MbTilesServer?): String? = try {
        val raw = assets.open("style.json").bufferedReader().use { it.readText() }
        val root = org.json.JSONObject(raw)
        val sources = root.getJSONObject("sources")
        sources.getJSONObject("charts").apply {
            put("minzoom", server.minZoom)
            put("maxzoom", server.maxZoom)
        }
        if (basemap != null && sources.has("basemap")) {
            // Patch the OSM basemap's zoom range from the file itself (same reason as charts).
            sources.getJSONObject("basemap").apply {
                put("minzoom", basemap.minZoom)
                put("maxzoom", basemap.maxZoom)
            }
        } else {
            // No basemap file on disk: strip its source + every layer that references it, so
            // MapLibre never hammers the dead :8124 port. Charts render exactly as before.
            sources.remove("basemap")
            val layers = root.getJSONArray("layers")
            val kept = org.json.JSONArray()
            for (i in 0 until layers.length()) {
                val l = layers.getJSONObject(i)
                if (l.optString("source") != "basemap") kept.put(l)
            }
            root.put("layers", kept)
        }
        root.toString()
    } catch (t: Throwable) {
        Log.w("MyNavvy", "style patch failed, using asset as-is: ${t.message}")
        null
    }

    private fun setupMap(m: MapLibreMap) {
        // MapLibre is BSD-licensed (no attribution obligation, unlike Mapbox) and the charts are NOAA
        // ENC / US public domain, so the logo + "(i)" attribution button aren't required — hide both
        // for a clean chartplotter face (and so the bottom-left "(i)" no longer eats taps there).
        m.uiSettings.isLogoEnabled = false
        m.uiSettings.isAttributionEnabled = false

        val charts = File(getExternalFilesDir(null), "charts.mbtiles")
        val builder: Style.Builder
        if (charts.exists()) {
            startTileServer(charts)
            // Optional OSM land basemap — only if its file was downloaded. Never blocks charts.
            val basemap = File(getExternalFilesDir(null), "basemap.mbtiles")
            if (basemap.exists()) startBasemapServer(basemap)
            // Take the zoom range from the MBTiles itself, never from a hardcoded literal.
            val patched = tileServer?.let { chartStyleJson(it, basemapServer) }
            builder = if (patched != null) Style.Builder().fromJson(patched)
            else Style.Builder().fromUri("asset://style.json")
        } else {
            // No charts: the data overlay is covering the screen anyway (no charts, no app).
            builder = Style.Builder().fromUri("asset://empty_style.json")
        }

        defaultRangeNm = BoatProfile.load(this).defaultRangeNm
        m.cameraPosition = CameraPosition.Builder().target(SAN_DIEGO)
            .zoom(zoomForRangeNm(defaultRangeNm, SAN_DIEGO.latitude)).build()
        m.setStyle(builder) { style ->
            loadedStyle = style
            activateLocationComponent(style)
            // History overlay first, so day-tracks draw BENEATH the live trail/route/track layers.
            if (trackHistory == null) trackHistory = TrackHistory.Overlay(style)
            historyTracksN = getSharedPreferences(STATE_PREFS, MODE_PRIVATE).getInt("history_tracks", 5)
            reloadTrackHistory()
            if (routeManager == null) routeManager = RouteManager(style)
            if (weatherOverlay == null) weatherOverlay = WeatherOverlay(style)
            // Added last so the anchor swing/rode/marker sit on top of route + weather layers.
            if (anchorWatch == null) anchorWatch = AnchorWatch(style)
            if (markStore == null) markStore = MarkStore(style)
            markStore?.loadJson(getSharedPreferences(STATE_PREFS, MODE_PRIVATE).getString("marks", "") ?: "")
            // The boat marker sits on top of everything — it must never be hidden by another layer.
            boatMarker = BoatMarker(style)
            lastLocation?.let {
                boatMarker?.update(it.latitude, it.longitude,
                    if (it.hasBearing()) it.bearing.toDouble() else cogDeg, metersPerPixelNow(m))
            }
            applySafetyShading(style)
            applyDepthLabels()   // honour the toolbar depth toggle on (re)load
            // Long-press = the add/share menu (add waypoint · add mark · share location); on a feature
            // it manages that feature instead. A single tap manages a tapped feature, else reads depth.
            m.addOnMapLongClickListener { ll ->
                // Editing the route/marks is a CHART action only — not while navigating.
                if (navMode) return@addOnMapLongClickListener true
                binding.infoCard.visibility = View.GONE
                val feat = findFeatureAt(m, ll)
                if (feat != null) showFeatureMenu(feat) else showLocationMenu(ll)
                flashChrome()
                true
            }
            m.addOnMapClickListener { ll ->
                lastTouchedMap = m
                when {
                    menuOpen() -> closeMenu()
                    else -> {
                        // A tap on a saved mark / route waypoint manages it; otherwise show the depth.
                        val feat = findFeatureAt(m, ll)
                        if (feat != null) showFeatureMenu(feat) else showDepthOverlay(ll)
                    }
                }
                flashChrome()
                true
            }
            binding.infoCard.setOnClickListener { binding.infoCard.visibility = View.GONE }
            if (!stateRestored) { stateRestored = true; restoreState() }
            chartReady = true // style is up — release the splash
            applyPendingShare() // a location shared in before the chart was ready
        }

        // A user pan/drag reveals the (auto-hiding) bottom bar. Uses the gesture move listener, NOT
        // camera-move: the camera is in TRACKING mode and moves on every GPS fix, which would keep
        // the bar pinned open under way.
        // The auto-recenter countdown is held while a gesture is in progress (no snap-back under the
        // finger) and (re)started on every gesture end — so the timeout runs from the LAST pan/zoom,
        // not from the first one that dismissed boat-follow.
        m.addOnMoveListener(object : MapLibreMap.OnMoveListener {
            override fun onMoveBegin(d: org.maplibre.android.gestures.MoveGestureDetector) { lastTouchedMap = m; flashChrome(); holdRecenter() }
            override fun onMove(d: org.maplibre.android.gestures.MoveGestureDetector) = holdRecenter()
            override fun onMoveEnd(d: org.maplibre.android.gestures.MoveGestureDetector) { flashChrome(); scheduleRecenterIfUnfollowed() }
        })
        m.addOnScaleListener(object : MapLibreMap.OnScaleListener {   // pinch-zoom counts as a touch
            override fun onScaleBegin(d: org.maplibre.android.gestures.StandardScaleGestureDetector) { lastTouchedMap = m; holdRecenter(); flashChrome() }
            override fun onScale(d: org.maplibre.android.gestures.StandardScaleGestureDetector) = holdRecenter()
            override fun onScaleEnd(d: org.maplibre.android.gestures.StandardScaleGestureDetector) = scheduleRecenterIfUnfollowed()
        })

        // Live scale bar, recomputed as the camera zooms/pans.
        m.addOnCameraMoveListener { updateScaleBar(m) }
        m.addOnCameraIdleListener {
            updateScaleBar(m)
            updateHud() // charted depth is queried from the rendered chart, so re-read once settled
            // Rebuild the wind barbs at the new zoom so they keep a steady on-screen size, and refetch
            // the grid over the new view if it moved enough (so barbs cover wherever you pan/zoom).
            if (binding.weatherPanel.visibility == View.VISIBLE) {
                weatherOverlay?.reproject(metersPerPixelNow(m))
                maybeRefetchWind(m)
            }
            boatMarker?.reproject(metersPerPixelNow(m)) // keep the heading wedge a steady size on zoom
        }
        updateScaleBar(m)

        // Populate wind for the HUD without opening the Wx panel or painting arrows.
        if (weather == null) fetchWeather(silent = true)

        ensureLocationUpdates()
    }

    /**
     * Mouse-wheel / trackpad zoom, anchored at the pointer. Intercepted at the ACTIVITY level:
     * MapView is a ViewGroup and the scroll event is swallowed by its render-surface child before a
     * MapView-level OnGenericMotionListener would ever run (which is why the earlier listener did
     * nothing and the wheel just panned). Activity.dispatchGenericMotionEvent runs BEFORE the whole
     * view tree, so consuming the wheel here pre-empts MapLibre's own gesture handling entirely.
     */
    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_SCROLL) {
            // Accept a scroll from ANY source (not just SOURCE_CLASS_POINTER): some hosts/emulators
            // report the wheel from an unexpected source, and a VSCROLL axis is unambiguously a wheel.
            var notches = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
            if (notches == 0f) notches = event.getAxisValue(MotionEvent.AXIS_SCROLL)
            val m = map
            if (notches != 0f && m != null) {
                // rawX/rawY are screen coords; offset by the MapView's on-screen origin to anchor
                // the zoom under the cursor. Fall back to the map centre if the pointer is elsewhere.
                val loc = IntArray(2)
                binding.mapView.getLocationOnScreen(loc)
                val x = (event.rawX - loc[0]).toInt()
                val y = (event.rawY - loc[1]).toInt()
                val anchor = if (x in 0..binding.mapView.width && y in 0..binding.mapView.height)
                    Point(x, y) else Point(binding.mapView.width / 2, binding.mapView.height / 2)
                // moveCamera (instant), NOT animateCamera: rapid wheel notches would otherwise stack
                // overlapping multi-frame zoom animations, and that render churn segfaults the
                // emulator's software GL translator (mbgl MapRenderer::render → glDrawElements). One
                // render per notch is both safer and the conventional feel for a scroll wheel.
                m.moveCamera(CameraUpdateFactory.zoomBy(notches.toDouble() * ZOOM_PER_WHEEL_NOTCH, anchor))
                flashChrome()
                return true
            }
        }
        return super.dispatchGenericMotionEvent(event)
    }

    /** Feed the scale bar the map's metres-per-pixel at the current camera latitude. */
    private fun updateScaleBar(m: MapLibreMap) {
        val lat = m.cameraPosition.target?.latitude ?: return
        binding.scaleBar.update(m.projection.getMetersPerPixelAtLatitude(lat))
    }

    /** Ground resolution at the current map centre — sizes the wind barbs in metres per screen pixel. */
    private fun metersPerPixelNow(m: MapLibreMap): Double {
        val lat = m.cameraPosition.target?.latitude ?: 32.7
        return m.projection.getMetersPerPixelAtLatitude(lat)
    }

    /** MapLibre zoom level that makes the chart span [rangeNm] across the map's width at [lat].
     *  Web-Mercator, 512-px tiles (MapLibre's world size). Independent of the current camera. */
    private fun zoomForRangeNm(rangeNm: Double, lat: Double): Double {
        val w = binding.mapView.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val targetMpp = rangeNm * 1852.0 / w                     // metres per pixel we want
        val earthCirc = 2.0 * Math.PI * 6378137.0
        val z = Math.log(earthCirc * Math.cos(Math.toRadians(lat)) / (512.0 * targetMpp)) / Math.log(2.0)
        return z.coerceIn(3.0, 19.0)
    }

    /**
     * Repaint the depth bands as a pure BATHYMETRIC blue gradient: deep water dark navy, lightening
     * to pale blue as it shoals. Water shallower than this boat's safety depth (draft + under-keel
     * margin) is the palest blue — still unmistakably WATER, never a yellow that reads as land. The
     * hard "can my keel clear it" hazard lives in the depth/UKC HUD (red), not in the chart fill.
     * Green stays reserved for ground that dries; unknown depth stays deep/background.
     */
    /** Toolbar ≋ toggle: show/hide the depth contours + sounding numbers. The chart and nav screens
     *  share this style, so the toggle affects both. Depth SHADING (the blue ramp) always stays. */
    private fun toggleDepthLabels() {
        depthLabelsOn = !depthLabelsOn
        getSharedPreferences(STATE_PREFS, MODE_PRIVATE).edit()
            .putBoolean("depth_labels", depthLabelsOn).apply()
        applyDepthLabels()
        flashChrome()
    }

    private fun applyDepthLabels() {
        val style = loadedStyle ?: return
        val vis = if (depthLabelsOn) "visible" else "none"
        for (layer in style.layers) {
            if (layer.id.startsWith("SOUNDG") || layer.id.startsWith("DEPCNT"))
                layer.setProperties(PropertyFactory.visibility(vis))
        }
    }

    private fun applySafetyShading(style: Style) {
        val safety = (boat.draftM + boat.ukcMarginM).coerceIn(0.5, 9.5)
        // Blue everywhere there is water: palest = shallower than safety, darkening with depth. Any
        // charted water (even 0 m at MLLW) reads as water; only true drying ground (DRVAL1<0) is green.
        val fill = PropertyFactory.fillColor(
            Expression.step(
                Expression.coalesce(
                    // to-number has no default form in the Java DSL; coalesce a sentinel instead
                    Expression.toNumber(Expression.get("DRVAL1")),
                    Expression.literal(-999.0)
                ),
                Expression.color(Color.parseColor("#1b5e91")),                         // unknown == deep/background: no seam over open sea
                Expression.stop(-100, Expression.color(Color.parseColor("#7cc47f"))),  // dries (uncovers at LW)
                Expression.stop(0, Expression.color(Color.parseColor("#cfe6f5"))),     // < safety: shallow, palest blue
                Expression.stop(safety, Expression.color(Color.parseColor("#8fc0e2"))),      // just safe
                Expression.stop(safety * 2.0, Expression.color(Color.parseColor("#4a90c2"))),
                Expression.stop(safety * 4.0, Expression.color(Color.parseColor("#1b5e91")))  // deep
            )
        )
        // One DEPARE + one DRGARE (dredged channel) fill per ENC usage band. Both get the SAME
        // depth ramp so the ship channel reads as continuous water (see charts-pipeline/make_style.py).
        for (b in 1..6) {
            style.getLayerAs<FillLayer>("DEPARE-b$b")?.setProperties(fill)
            style.getLayerAs<FillLayer>("DRGARE-b$b")?.setProperties(fill)
        }
    }

    private fun startTileServer(charts: File) {
        try {
            tileServer = MbTilesServer(TILE_PORT, charts).apply { start() }
        } catch (e: Exception) {
            Diagnostics.capture(e)
            Toast.makeText(this, "Chart server failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Start the optional OSM land basemap server. Unlike the chart server this is NON-fatal: the
     * basemap is a convenience overlay, so a failure here must never toast or block the chart map —
     * we just log and carry on with charts-only (chartStyleJson strips the basemap layers when
     * basemapServer stays null).
     */
    private fun startBasemapServer(basemap: File) {
        try {
            // Read-through cache: tiles outside the seed region are fetched from our own tile server
            // when online and persisted into basemap_cache.mbtiles, so they're there offline next time.
            val cache = File(getExternalFilesDir(null), "basemap_cache.mbtiles")
            basemapServer = MbTilesServer(
                BASEMAP_PORT, basemap,
                cacheFile = cache,
                remoteTilesUrl = BASEMAP_REMOTE,
                isOnline = { isNetworkAvailable() }
            ).apply { start() }
        } catch (e: Exception) {
            Log.w("MyNavvy", "basemap server failed (charts unaffected): ${e.message}")
            basemapServer = null
        }
    }

    /** Real internet reachability (loopback tile servers don't need it; the remote basemap does). */
    private fun isNetworkAvailable(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager ?: return false
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /**
     * Prefetch the OSM land basemap for the current viewport (all zooms up to the basemap's max) from
     * the remote tile server into the offline cache — the deliberate "top up this passage before I
     * lose signal" companion to the automatic pan-to-cache read-through. Bounded by [PREFETCH_MAX_TILES].
     */
    private fun prefetchBasemapHere() {
        val server = basemapServer
        val m = map
        if (server == null || m == null || !server.canPrefetch) {
            Toast.makeText(this, "Land basemap not available here", Toast.LENGTH_SHORT).show(); return
        }
        if (!isNetworkAvailable()) {
            Toast.makeText(this, "Go online to download the land map", Toast.LENGTH_SHORT).show(); return
        }
        val b = m.projection.visibleRegion.latLngBounds
        val ne = b.northEast; val sw = b.southWest
        val west = sw.longitude; val east = ne.longitude; val north = ne.latitude; val south = sw.latitude
        val zFrom = m.cameraPosition.zoom.toInt().coerceIn(server.minZoom, server.maxZoom)
        // Enumerate XYZ tiles covering the view for zFrom..maxZoom, capped so a zoomed-out view can't
        // ask for millions of tiles.
        val jobs = ArrayList<IntArray>()
        for (z in zFrom..server.maxZoom) {
            val n = 1 shl z
            val xMin = lonToTileX(west, z).coerceIn(0, n - 1)
            val xMax = lonToTileX(east, z).coerceIn(0, n - 1)
            val yMin = latToTileY(north, z).coerceIn(0, n - 1) // north = smaller y in XYZ
            val yMax = latToTileY(south, z).coerceIn(0, n - 1)
            for (x in xMin..xMax) for (y in yMin..yMax) {
                jobs.add(intArrayOf(z, x, y))
                if (jobs.size >= PREFETCH_MAX_TILES) break
            }
            if (jobs.size >= PREFETCH_MAX_TILES) break
        }
        val capped = jobs.size >= PREFETCH_MAX_TILES
        Toast.makeText(this, "Downloading land map: ${jobs.size} tiles…", Toast.LENGTH_SHORT).show()
        Thread {
            var ok = 0
            for (j in jobs) if (server.cacheRemoteTile(j[0], j[1], j[2])) ok++
            runOnUiThread {
                val note = if (capped) " (view capped at $PREFETCH_MAX_TILES — zoom in for more)" else ""
                Toast.makeText(this, "Land map cached: $ok/${jobs.size} tiles$note", Toast.LENGTH_LONG).show()
                loadedStyle?.let { map?.triggerRepaint() } // nudge MapLibre to show freshly-cached tiles
            }
        }.apply { isDaemon = true }.start()
    }

    private fun lonToTileX(lon: Double, z: Int): Int =
        Math.floor((lon + 180.0) / 360.0 * (1 shl z)).toInt()

    private fun latToTileY(lat: Double, z: Int): Int {
        val r = Math.toRadians(lat)
        return Math.floor((1.0 - Math.log(Math.tan(r) + 1.0 / Math.cos(r)) / Math.PI) / 2.0 * (1 shl z)).toInt()
    }

    // --- Phase 2 controls ---------------------------------------------------

    private fun wireControls() {
        binding.btnUndo.setOnClickListener { routeManager?.undoWaypoint(); flashChrome() }
        binding.btnClear.setOnClickListener { routeManager?.clearRoute(); flashChrome() }

        binding.btnWx.setOnClickListener {
            flashChrome()
            val show = binding.weatherPanel.visibility != View.VISIBLE
            binding.weatherPanel.visibility = if (show) View.VISIBLE else View.GONE
            repositionBottomOverlays()
            if (show) {
                if (weather == null) fetchWeather() else applyHour()
                startWxTicker()
            } else {
                weatherOverlay?.clear()
                stopWxTicker()
            }
        }
        binding.seekTime.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                selectedHour = progress; applyHour()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        // Auto-route the current waypoints (propulsion mode is set in the Configuration dialog).
        binding.btnRoute.setOnClickListener { computeRoute(); flashChrome() }
        binding.btnZoomIn.setOnClickListener { zoomBy(+1.0); flashChrome() }
        binding.btnZoomOut.setOnClickListener { zoomBy(-1.0); flashChrome() }
        binding.btnCenterBoat.setOnClickListener { flashChrome(); centerOnBoat() }
        binding.btnAnchor.setOnClickListener { flashChrome(); showAnchorDialog() }
        updateAnchorButton()   // initial enabled/greyed state
        binding.btnDepth.setOnClickListener { toggleDepthLabels() }
        binding.btnMark.setOnClickListener { flashChrome(); dropMarkAtBoat() }

        // Touching the toolbar keeps it awake; then show it once on launch.
        binding.leftToolbar.setOnTouchListener { _, _ -> flashChrome(); false }
        flashChrome()
    }

    // --- Marks + route waypoints: tap / long-press interaction --------------
    // Long-press empty water opens an add-menu (waypoint · mark · share). Tap or long-press a saved
    // mark or route waypoint to manage it (remove · rename · share). MarkStore + RouteManager own the
    // map layers + hit-test id properties; findFeatureAt dispatches by whichever layer the tap hit.
    // The ⚑ toolbar button separately drops a quick mark at the current fix. Marks persist in
    // STATE_PREFS under "marks".

    /** ⚑ button: save a mark at the current boat position (needs a GPS fix). */
    private fun dropMarkAtBoat() {
        val loc = lastLocation ?: run {
            Toast.makeText(this, "No GPS fix yet — can't mark position", Toast.LENGTH_SHORT).show(); return
        }
        val name = markStore?.add(loc.latitude, loc.longitude, System.currentTimeMillis()) ?: return
        saveMarks()
        Toast.makeText(this, "Saved $name", Toast.LENGTH_SHORT).show()
    }

    private enum class FeatureKind { MARK, WAYPOINT }
    private data class TappedFeature(val kind: FeatureKind, val id: Long, val ll: LatLng, val name: String)

    /** Shrink a context-menu dialog to ~half the screen width so the short item list isn't stretched. */
    private fun compact(dlg: androidx.appcompat.app.AlertDialog): androidx.appcompat.app.AlertDialog {
        dlg.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.5f).toInt(),
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
        return dlg
    }

    /** Hit-test the mark + waypoint layers around a tapped point (finger-sized box); marks win ties. */
    private fun findFeatureAt(m: MapLibreMap, ll: LatLng): TappedFeature? {
        val pt = m.projection.toScreenLocation(ll)
        val r = 24f * resources.displayMetrics.density   // ~24dp touch tolerance
        val box = RectF(pt.x - r, pt.y - r, pt.x + r, pt.y + r)
        markStore?.let { ms ->
            m.queryRenderedFeatures(box, MarkStore.LYR)
                .firstOrNull { it.hasNonNullValueForProperty(MarkStore.PROP_ID) }
                ?.getNumberProperty(MarkStore.PROP_ID)?.toLong()
                ?.let { id -> ms.byId(id)?.let {
                    return TappedFeature(FeatureKind.MARK, id, LatLng(it.lat, it.lon), it.name) } }
        }
        routeManager?.let { rm ->
            m.queryRenderedFeatures(box, RouteManager.LYR_WP)
                .firstOrNull { it.hasNonNullValueForProperty(RouteManager.PROP_WID) }
                ?.getNumberProperty(RouteManager.PROP_WID)?.toLong()
                ?.let { id -> rm.waypointById(id)?.let {
                    return TappedFeature(FeatureKind.WAYPOINT, id, it.ll, it.name ?: "Waypoint") } }
        }
        return null
    }

    /** Long-press on empty water: add a waypoint / mark (named) here, or share these coordinates. */
    private fun showLocationMenu(ll: LatLng) {
        compact(androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Location")
            .setItems(arrayOf("Add waypoint", "Add mark", "Share location")) { _, which ->
                when (which) {
                    0 -> { routeManager?.addWaypoint(ll); flashChrome() }
                    1 -> {
                        val t = System.currentTimeMillis()               // the new mark's id == this ts
                        val name = markStore?.add(ll.latitude, ll.longitude, t) ?: return@setItems
                        saveMarks()
                        renameFeatureDialog(TappedFeature(FeatureKind.MARK, t, ll, name)) // "ask for name"
                    }
                    2 -> shareLocation(ll.latitude, ll.longitude)
                }
            }
            .setNegativeButton("Cancel", null)
            .show())
    }

    /** Tap/long-press on an existing mark or waypoint: remove, rename, or share it. */
    private fun showFeatureMenu(f: TappedFeature) {
        compact(androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(f.name)
            .setItems(arrayOf("Remove", "Rename", "Share")) { _, which ->
                when (which) {
                    0 -> {
                        when (f.kind) {
                            FeatureKind.MARK -> { markStore?.remove(f.id); saveMarks() }
                            FeatureKind.WAYPOINT -> routeManager?.removeWaypoint(f.id)
                        }
                    }
                    1 -> renameFeatureDialog(f)
                    2 -> shareLocation(f.ll.latitude, f.ll.longitude)
                }
            }
            .setNegativeButton("Close", null)
            .show())
    }

    private fun renameFeatureDialog(f: TappedFeature) {
        val input = EditText(this).apply { setText(f.name); setSelection(text.length); setSingleLine() }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(input)
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(if (f.kind == FeatureKind.MARK) "Name mark" else "Name waypoint").setView(box)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString()
                when (f.kind) {
                    FeatureKind.MARK -> { markStore?.rename(f.id, name); saveMarks() }
                    FeatureKind.WAYPOINT -> routeManager?.renameWaypoint(f.id, name)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Share a position via the system chooser as Google-Maps-parseable coordinates + a maps link. */
    private fun shareLocation(lat: Double, lon: Double) {
        val coords = String.format(Locale.US, "%.6f, %.6f", lat, lon)
        val url = String.format(Locale.US, "https://www.google.com/maps?q=%.6f,%.6f", lat, lon)
        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(android.content.Intent.EXTRA_SUBJECT, "Location")
            putExtra(android.content.Intent.EXTRA_TEXT, "$coords\n$url")
        }
        startActivity(android.content.Intent.createChooser(send, "Share location"))
    }

    // --- Receive a shared location (MyNavvy is registered as a share / geo: target) ------------------

    /** Parse a location out of an incoming SEND (text) or VIEW (geo:) intent; centre + open the add
     *  menu there once the chart is up. Unparseable shares (e.g. a shortened maps link) just toast. */
    private fun handleShareIntent(intent: android.content.Intent?) {
        val action = intent?.action
        if (action != android.content.Intent.ACTION_SEND && action != android.content.Intent.ACTION_VIEW) return
        val loc = parseSharedLocation(intent)
        if (loc == null) {
            Toast.makeText(this, "No coordinates in the shared location", Toast.LENGTH_LONG).show(); return
        }
        pendingShare = loc
        if (chartReady) applyPendingShare()
    }

    private fun applyPendingShare() {
        val loc = pendingShare ?: return
        pendingShare = null
        map?.animateCamera(CameraUpdateFactory.newLatLng(loc), 600)
        showLocationMenu(loc)
    }

    private fun parseSharedLocation(intent: android.content.Intent): LatLng? {
        intent.data?.let { parseCoords(it.toString())?.let { ll -> return ll } }
        intent.getStringExtra(android.content.Intent.EXTRA_TEXT)?.let { return parseCoords(it) }
        return null
    }

    /** Pull a lat,lon out of a geo: URI, a Google-Maps URL (q=/ll=/@), or bare "lat, lon" text. */
    private fun parseCoords(text: String): LatLng? {
        val patterns = listOf(
            Regex("""[?&](?:q|ll|daddr|destination)=(-?\d{1,2}(?:\.\d+)?),(-?\d{1,3}(?:\.\d+)?)"""),
            Regex("""@(-?\d{1,2}(?:\.\d+)?),(-?\d{1,3}(?:\.\d+)?)"""),
            Regex("""geo:(-?\d{1,2}(?:\.\d+)?),(-?\d{1,3}(?:\.\d+)?)"""),
            Regex("""(-?\d{1,2}\.\d+)\s*,\s*(-?\d{1,3}\.\d+)""")   // bare decimal "lat, lon"
        )
        for (p in patterns) {
            val mch = p.find(text) ?: continue
            val lat = mch.groupValues[1].toDoubleOrNull() ?: continue
            val lon = mch.groupValues[2].toDoubleOrNull() ?: continue
            if (lat != 0.0 && lat in -90.0..90.0 && lon in -180.0..180.0) return LatLng(lat, lon)
        }
        return null
    }

    private fun saveMarks() {
        val json = markStore?.toJson() ?: return
        getSharedPreferences(STATE_PREFS, MODE_PRIVATE).edit().putString("marks", json).apply()
    }

    // --- Anchor watch --------------------------------------------------------
    // Anchor is a background watch, controlled by a dialog off the chart toolbar's ⚓ button (NOT a
    // full-screen mode). `anchorMode` == "anchor down / watching" (mirrors anchorWatch.isSet()); the
    // chart stays interactive while anchored. AnchorWatch draws the swing-circle map layers; the drag
    // alarm + banner live in refreshAnchorUi (driven from onFix while anchored).

    /** ⚓ dialog: set the radius and lower the anchor; or, if already down, change radius / raise it. */
    private fun showAnchorDialog() {
        val set = anchorWatch?.isSet() == true
        val radiusVal = TextView(this).apply {
            textSize = 20f; setTextColor(Color.parseColor("#111111")); gravity = Gravity.CENTER
            typeface = android.graphics.Typeface.MONOSPACE; minWidth = dp(110)
            text = fmtLen(alarmRadiusM)
        }
        val minus = Button(this).apply { text = "–"; setOnClickListener { stepRadius(-1); radiusVal.text = fmtLen(alarmRadiusM) } }
        val plus  = Button(this).apply { text = "+"; setOnClickListener { stepRadius(+1); radiusVal.text = fmtLen(alarmRadiusM) } }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(14), dp(20), 0)
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
                addView(TextView(this@MainActivity).apply {
                    text = "Radius"; setTextColor(Color.parseColor("#555555")); setPadding(0, 0, dp(10), 0) })
                addView(minus); addView(radiusVal); addView(plus)
            })
        }
        if (set) {   // live status when re-opening on a set watch
            val a = anchorWatch?.anchorPos(); val loc = lastLocation
            if (a != null && loc != null) {
                val distM = GeoUtils.distanceNm(a, LatLng(loc.latitude, loc.longitude)) * 1852.0
                content.addView(TextView(this).apply {
                    text = String.format(Locale.US, "%s from drop · max %s", fmtLen(distM), fmtLen(anchorMaxDistM))
                    setTextColor(if (distM > alarmRadiusM) Color.parseColor("#C62828") else Color.parseColor("#2E7D32"))
                    setPadding(0, dp(12), 0, 0); gravity = Gravity.CENTER
                })
            }
        }
        val b = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(if (set) "Anchor watch" else "Anchor").setView(content)
        if (set) b.setPositiveButton("Raise anchor") { _, _ -> raiseAnchor() }.setNegativeButton("Close", null)
        else     b.setPositiveButton("Lower anchor") { _, _ -> lowerAnchor() }.setNegativeButton("Cancel", null)
        b.show()
    }

    /** Drop the anchor at the current fix and arm the watch. */
    private fun lowerAnchor() {
        val loc = lastLocation
        if (loc == null) { Toast.makeText(this, "Waiting for GPS fix…", Toast.LENGTH_SHORT).show(); return }
        val aw = anchorWatch ?: return
        anchorMode = true
        alarmEnabled = true
        anchorMaxDistM = 0.0
        aw.drop(LatLng(loc.latitude, loc.longitude), alarmRadiusM / 1852.0)
        startAnchorService()  // keep watching even if the app is minimised
        zoomToWatchCircle()   // frame the circle we just drew
        refreshAnchorUi()
        updateAnchorButton()  // now anchored → keep the ⚓ reachable regardless of SOG
    }

    /** Frame the chart so the watch circle (radius R around the boat / drop) is clearly visible —
     *  fits ~2× the radius each way, centred on the current fix. No-op without GPS or a map. */
    private fun zoomToWatchCircle() {
        val loc = lastLocation ?: return
        val m = map ?: return
        val center = LatLng(loc.latitude, loc.longitude)
        val dNm = (alarmRadiusM * 2.2) / 1852.0
        val bounds = LatLngBounds.Builder()
            .include(GeoUtils.destination(center, 0.0, dNm))
            .include(GeoUtils.destination(center, 90.0, dNm))
            .include(GeoUtils.destination(center, 180.0, dNm))
            .include(GeoUtils.destination(center, 270.0, dNm))
            .build()
        val pad = (48 * resources.displayMetrics.density).toInt()
        m.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, pad), 500)
    }

    /** Raise the anchor and disarm the watch. */
    private fun raiseAnchor() {
        anchorMode = false
        stopAlarm()
        anchorWatch?.raise()
        stopAnchorService()
        anchorMaxDistM = 0.0
        binding.tvAnchorBanner.visibility = View.GONE
        flashChrome()
        updateAnchorButton()
    }

    /** Safety: you can't drop anchor while making way — grey out the ⚓ button above [ANCHOR_MAX_SOG_KN].
     *  Exception: if the anchor is already down it stays reachable, so the watch can be raised / adjusted
     *  even while dragging. Driven from every fix (SOG changes) and on lower/raise. */
    private fun updateAnchorButton() {
        val sogKn = lastLocation?.let { it.speed * 1.94384 } ?: 0.0
        val allow = anchorWatch?.isSet() == true || sogKn <= ANCHOR_MAX_SOG_KN
        binding.btnAnchor.isEnabled = allow
        binding.btnAnchor.alpha = if (allow) 1f else 0.4f
    }

    // --- Persisted state (survives quit / process kill) ---------------------------------------
    /** Save the safety-relevant state so a kill/restart resumes it — above all the anchor watch
     *  (drop point + radius + alarm), plus the last position so the boat shows at once on relaunch. */
    private fun saveState() {
        val ap = anchorWatch?.anchorPos()
        val anchored = anchorMode && anchorWatch?.isSet() == true && ap != null
        getSharedPreferences(STATE_PREFS, MODE_PRIVATE).edit().apply {
            putBoolean("anchor_set", anchored)
            if (anchored) {
                putLong("anchor_lat", ap!!.latitude.toRawBits())
                putLong("anchor_lon", ap.longitude.toRawBits())
            }
            putLong("anchor_radius", alarmRadiusM.toRawBits())
            putBoolean("anchor_alarm", alarmEnabled)
            lastLocation?.let {
                putLong("last_lat", it.latitude.toRawBits())
                putLong("last_lon", it.longitude.toRawBits())
            }
            putLong("trip_start", tripStartMs)
            putLong("trip_dist", tripDistNm.toRawBits())
            putLong("trip_moving", tripMovingMs)
            putLong("trip_maxsog", tripMaxSogKn.toRawBits())
            putLong("trip_sogsum", tripSogSumKn.toRawBits())
            putLong("trip_sogcount", tripSogCount)
            apply()
        }
    }

    /** Restore what [saveState] persisted. Runs once after the first style load (map + anchorWatch
     *  ready): seeds the last position so the boat puck appears immediately, and re-arms the anchor
     *  watch at its saved drop — a foreground kill while anchored no longer loses the watch. */
    private fun restoreState() {
        val sp = getSharedPreferences(STATE_PREFS, MODE_PRIVATE)
        if (sp.contains("anchor_radius")) alarmRadiusM = Double.fromBits(sp.getLong("anchor_radius", alarmRadiusM.toRawBits()))
        alarmEnabled = sp.getBoolean("anchor_alarm", alarmEnabled)
        // Trip totals survive a kill so a day's run isn't lost when the app is backgrounded out.
        tripStartMs = sp.getLong("trip_start", 0L)
        tripDistNm = Double.fromBits(sp.getLong("trip_dist", 0L))
        tripMovingMs = sp.getLong("trip_moving", 0L)
        tripMaxSogKn = Double.fromBits(sp.getLong("trip_maxsog", 0L))
        tripSogSumKn = Double.fromBits(sp.getLong("trip_sogsum", 0L))
        tripSogCount = sp.getLong("trip_sogcount", 0L)
        if (lastLocation == null && sp.contains("last_lat")) {
            lastLocation = Location("saved").apply {
                latitude = Double.fromBits(sp.getLong("last_lat", 0L))
                longitude = Double.fromBits(sp.getLong("last_lon", 0L))
            }
            map?.locationComponent?.takeIf { it.isLocationComponentActivated }
                ?.let { @Suppress("MissingPermission") it.forceLocationUpdate(lastLocation) }
        }
        if (sp.getBoolean("anchor_set", false) && sp.contains("anchor_lat")) {
            val a = LatLng(Double.fromBits(sp.getLong("anchor_lat", 0L)), Double.fromBits(sp.getLong("anchor_lon", 0L)))
            anchorMode = true
            anchorWatch?.drop(a, alarmRadiusM / 1852.0)
            startAnchorService()
            refreshAnchorUi()
        }
    }

    // --- Anchor control (handled by the always-on WatchService) -------------------------------
    /** Arm the watch's anchor at the current drop point + radius + alarm state. */
    private fun startAnchorService() {
        val ap = anchorWatch?.anchorPos() ?: return
        ensureNotificationPermission()
        val i = android.content.Intent(this, WatchService::class.java).apply {
            action = WatchService.ACTION_SET_ANCHOR
            putExtra(WatchService.EX_LAT, ap.latitude)
            putExtra(WatchService.EX_LON, ap.longitude)
            putExtra(WatchService.EX_RADIUS, alarmRadiusM)
            putExtra(WatchService.EX_ALARM, alarmEnabled)
        }
        try { ContextCompat.startForegroundService(this, i) }
        catch (t: Throwable) { Log.w("MyNavvy", "anchor arm failed: ${t.message}") }
    }

    /** Push a radius / alarm-toggle change to the running watch (no-op if not anchored). */
    private fun updateAnchorService() {
        if (anchorWatch?.isSet() != true) return
        val i = android.content.Intent(this, WatchService::class.java).apply {
            action = WatchService.ACTION_UPDATE_ANCHOR
            putExtra(WatchService.EX_RADIUS, alarmRadiusM)
            putExtra(WatchService.EX_ALARM, alarmEnabled)
        }
        try { ContextCompat.startForegroundService(this, i) } catch (_: Throwable) {}
    }

    /** Disarm the anchor watch (the service keeps running for GPS + track). */
    private fun stopAnchorService() {
        val i = android.content.Intent(this, WatchService::class.java).apply { action = WatchService.ACTION_CLEAR_ANCHOR }
        try { ContextCompat.startForegroundService(this, i) } catch (_: Throwable) {}
    }

    private fun ensureNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
        }
    }

    /** Step the watch radius in the active unit — round 5 m or 10 ft — kept internally in metres. */
    private fun stepRadius(dir: Int) {
        alarmRadiusM = if (unitsFt) {
            val ft = Math.round(alarmRadiusM * BoatProfile.M_TO_FT / 10.0) * 10.0 + dir * 10.0
            ft.coerceIn(30.0, 1500.0) / BoatProfile.M_TO_FT
        } else {
            val m = Math.round(alarmRadiusM / 5.0) * 5.0 + dir * 5.0
            m.coerceIn(10.0, 500.0)
        }
        anchorWatch?.setRadiusNm(alarmRadiusM / 1852.0)
        updateAnchorService()
        refreshAnchorUi()
    }

    /** While anchored: update the swing-circle map colour + the drag/grounding alarm and its banner
     *  from the live fix. (The radius/status readouts now live in the ⚓ dialog.) */
    private fun refreshAnchorUi() {
        if (!anchorMode) return
        val aw = anchorWatch ?: return
        val a = aw.anchorPos()
        val loc = lastLocation
        if (a == null || loc == null) { binding.tvAnchorBanner.visibility = View.GONE; return }

        val boatLL = LatLng(loc.latitude, loc.longitude)
        aw.updateBoat(boatLL)
        val distM = GeoUtils.distanceNm(a, boatLL) * 1852.0
        if (distM > anchorMaxDistM) anchorMaxDistM = distM

        val depthNow = chartedDepthMin(loc.latitude, loc.longitude).minM?.plus(tideNowM() ?: 0.0)
        val ukcM = depthNow?.minus(boat.draftM)
        val dragging = distM > alarmRadiusM
        val grounding = ukcM != null && ukcM <= 0.0
        val alarm = dragging || grounding

        aw.setDragging(alarm)
        // WatchService owns the drag (radius) alarm — foreground AND background — so here we only
        // sound the foreground-only grounding alarm, to avoid two overlapping alarms while visible.
        if (grounding && alarmEnabled) startAlarm() else stopAlarm()
        binding.tvAnchorBanner.visibility = if (alarm && alarmEnabled) View.VISIBLE else View.GONE
        binding.tvAnchorBanner.text = if (grounding) "⚠ SHOALING — CHECK DEPTH" else "⚠ OUTSIDE RADIUS"
    }

    private fun fmtLen(m: Double): String =
        if (unitsFt) String.format(Locale.US, "%.0f ft", m * BoatProfile.M_TO_FT)
        else String.format(Locale.US, "%.0f m", m)

    /** Vertical unit label (depth / clearance / tide). Speed + distance are always kn / nm. */
    private val depthUnit get() = if (unitsFt) "ft" else "m"

    /** A metres value rendered in the configured vertical unit (metres or feet), no suffix. */
    private fun fmtDepthVal(m: Double): String =
        String.format(Locale.US, "%.1f", if (unitsFt) m * BoatProfile.M_TO_FT else m)

    private fun startAlarm() {
        if (alarmActive) return
        alarmActive = true
        try {
            val uri = android.media.RingtoneManager.getActualDefaultRingtoneUri(
                this, android.media.RingtoneManager.TYPE_ALARM)
                ?: android.media.RingtoneManager.getActualDefaultRingtoneUri(
                    this, android.media.RingtoneManager.TYPE_NOTIFICATION)
            if (uri != null) {
                alarmRingtone = android.media.RingtoneManager.getRingtone(this, uri)?.apply {
                    audioAttributes = android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_ALARM)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                    if (android.os.Build.VERSION.SDK_INT >= 28) isLooping = true
                    play()
                }
            }
        } catch (t: Throwable) { Log.w("MyNavvy", "alarm sound failed: ${t.message}") }
        startVibrate()
    }

    private fun stopAlarm() {
        alarmActive = false
        try { alarmRingtone?.stop() } catch (_: Throwable) {}
        alarmRingtone = null
        vibrator?.cancel()
    }

    private fun startVibrate() {
        val v = vibrator ?: run {
            val nv = if (android.os.Build.VERSION.SDK_INT >= 31) {
                (getSystemService(VIBRATOR_MANAGER_SERVICE) as android.os.VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION") (getSystemService(VIBRATOR_SERVICE) as android.os.Vibrator)
            }
            vibrator = nv; nv
        }
        val pattern = longArrayOf(0, 600, 500)
        try {
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                v.vibrate(android.os.VibrationEffect.createWaveform(pattern, 0))
            } else {
                @Suppress("DEPRECATION") v.vibrate(pattern, 0)
            }
        } catch (_: Throwable) {}
    }

    // --- Navigation mode -----------------------------------------------------
    // Like anchor, an on-chart mode reusing the one MapView: it tilts the camera into a course-up
    // 3D perspective and frames the chart with Simrad-style data bars (STEER · active WP · DEPTH on
    // top with a heading tape; SOG · route remaining · COG on the bottom). The HUD sidebar + chart
    // bar are hidden while it's on; the steering data is fed from onLocationChanged.

    /** True on the Android emulator — its guest GLES encoder SIGSEGVs in glDrawElements when MapLibre
     *  renders a TILTED map (any GPU backend), so nav mode stays flat (course-up) there. Real GPUs are
     *  fine, so devices get the full 3D tilt. */
    private fun isEmulator(): Boolean =
        Build.HARDWARE.contains("goldfish") || Build.HARDWARE.contains("ranchu") ||
        Build.FINGERPRINT.startsWith("generic") || Build.FINGERPRINT.contains("emulator", true) ||
        Build.MODEL.contains("Emulator", true) || Build.MODEL.contains("Android SDK built for", true) ||
        Build.PRODUCT.contains("sdk") || (Build.BRAND.startsWith("generic") && Build.DEVICE.startsWith("generic"))

    /** Nav-mode camera pitch: the full tilt on real hardware, flat on the emulator (GL-crash guard). */
    private fun navTiltDeg(): Double = if (isEmulator()) 0.0 else NAV_TILT

    /** Nav-mode follow: course-up on real hardware; north-up on the emulator, whose GL driver SIGSEGVs
     *  under the course-up camera rotation (paired with the flat tilt above so the emulator is usable). */
    private fun navCameraMode(): Int = if (isEmulator()) CameraMode.TRACKING else CameraMode.TRACKING_GPS

    /** In nav mode, push the top-left overlays (☰ menu + depth-tap info card) below the STEER panel
     *  (~84dp tall) so they don't cover the HUD; restore the 8dp top margin outside nav mode. */
    private fun liftTopLeftOverlays(lift: Boolean) {
        val top = ((if (lift) 92f else 8f) * resources.displayMetrics.density).toInt()
        binding.btnMenu.updateLayoutParams<FrameLayout.LayoutParams> { topMargin = top }
        binding.infoCard.updateLayoutParams<FrameLayout.LayoutParams> { topMargin = top }
    }

    private fun enterNavMode() {
        if (navMode) return
        navMode = true
        navActiveWp = 0
        binding.hudScroll.visibility = View.GONE
        retireChartUi()
        binding.navHud.visibility = View.VISIBLE
        liftTopLeftOverlays(true)   // ☰ menu + depth card drop below the STEER panel
        updateZoomControlsMargins() // sidebar gone → zoom/center buttons hug the right edge
        // Keep the chart's scale bar on the nav screen too, lifted clear of the bottom-left SOG panel
        // (the panel is 252·u ≈ 84 dp tall). Full nav only — a nav split pane manages its own chrome.
        // Shows via the common flash → same auto-hide as everywhere else.
        if (mapSplit == null) {
            binding.scaleBar.updateLayoutParams<FrameLayout.LayoutParams> {
                bottomMargin = (NAV_SCALE_BOTTOM_DP * resources.displayMetrics.density).toInt()
            }
            map?.let { updateScaleBar(it) }
            flashChrome()
        }
        // Course-up + tilt for a forward-looking perspective (raster tiles, so it's a tilted plane,
        // not extruded terrain). The location component drives target + bearing from the GPS course;
        // tracking transitions preserve tilt, so an explicit tiltTo actually pitches the camera
        // (tiltWhileTracking alone doesn't fire without a live location-engine stream).
        map?.let { m ->
            m.locationComponent.let { lc ->
                if (lc.isLocationComponentActivated) {
                    lc.cameraMode = navCameraMode()
                    lc.tiltWhileTracking(navTiltDeg())
                    @Suppress("MissingPermission")
                    lastLocation?.let { lc.forceLocationUpdate(it) }
                }
            }
            m.animateCamera(CameraUpdateFactory.tiltTo(navTiltDeg()), 500)
        }
        refreshNavUi()
    }

    private fun exitNavMode() {
        if (!navMode) return
        navMode = false
        binding.navHud.visibility = View.GONE
        binding.hudScroll.visibility = View.VISIBLE
        liftTopLeftOverlays(false)   // restore ☰ menu + depth card to the top-left
        updateZoomControlsMargins()  // sidebar back → zoom/center buttons clear it again
        // Drop the scale bar back to its chart position (flashChrome re-shows it there).
        binding.scaleBar.updateLayoutParams<FrameLayout.LayoutParams> {
            bottomMargin = (10 * resources.displayMetrics.density).toInt()
        }
        map?.let { m ->
            m.locationComponent.let { lc ->
                if (lc.isLocationComponentActivated) {
                    lc.tiltWhileTracking(0.0)
                    lc.cameraMode = CameraMode.TRACKING // back to north-up follow
                }
            }
            m.animateCamera(CameraUpdateFactory.tiltTo(0.0), 400)
        }
        flashChrome()
    }

    /** Recompute the Simrad nav-HUD snapshot from the live fix + active route waypoint. */
    private fun refreshNavUi() {
        if (!navHudActive()) return
        val loc = lastLocation
        val cog = if (loc?.hasBearing() == true) loc.bearing.toDouble() else null
        val sog = loc?.let { it.speed * 1.94384 }

        // Depth under the boat, tide-corrected, coloured by under-keel clearance.
        var depthDisp: Double? = null; var depthColor = Color.WHITE; var depthDry = false
        if (loc != null) {
            val actualM = chartedDepthMin(loc.latitude, loc.longitude).minM?.plus(tideNowM() ?: 0.0)
            depthColor = ukcColor(actualM?.minus(boat.draftM))
            when {
                actualM == null -> {}
                actualM <= 0.0 -> depthDry = true
                else -> depthDisp = if (unitsFt) actualM * BoatProfile.M_TO_FT else actualM
            }
        }

        val wps = routeManager?.waypoints() ?: emptyList()
        if (loc == null || wps.isEmpty()) {
            navHudState.value = NavData(
                depthDisp = depthDisp, depthColor = depthColor, depthDry = depthDry,
                showDepth = !bothMapsActive(), unitsFt = unitsFt,
                sogKn = sog, cogDeg = cog, hasFix = loc != null, hasRoute = false)
            return
        }

        val boatLL = LatLng(loc.latitude, loc.longitude)
        navActiveWp = navActiveWp.coerceIn(0, wps.size - 1)
        // Advance the active waypoint as we arrive at each one.
        while (navActiveWp < wps.size - 1 &&
            GeoUtils.distanceNm(boatLL, wps[navActiveWp]) < WP_ARRIVE_NM) navActiveWp++

        val target = wps[navActiveWp]
        val distNm = GeoUtils.distanceNm(boatLL, target)
        val brg = GeoUtils.bearingDeg(boatLL, target)
        // STEER: signed shortest turn from current course onto the bearing to the waypoint.
        val steer = cog?.let { ((brg - it + 540.0) % 360.0) - 180.0 }
        val ttg = if (sog != null && sog > 0.4) distNm / sog else null

        navHudState.value = NavData(
            steerDeg = steer, steerOnCourse = steer != null && Math.abs(steer) <= 5.0,
            depthDisp = depthDisp, depthColor = depthColor, depthDry = depthDry,
            showDepth = !bothMapsActive(), unitsFt = unitsFt,
            sogKn = sog, cogDeg = cog,
            dtwNm = distNm, wptName = "WP${navActiveWp + 1}", ttgHours = ttg,
            hasRoute = true, hasFix = true)
    }

    /**
     * Force a fresh download of every chart asset, ignoring the local sha markers. The markers say
     * "we already have this", which is exactly wrong when the file on disk is truncated, corrupt,
     * or was hand-copied — the one case where the automatic update can never help you.
     */
    private fun reloadCharts() {
        closeMenu()
        binding.dataOverlay.visibility = View.VISIBLE
        binding.progressData.visibility = View.INVISIBLE
        binding.btnDataAction.visibility = View.GONE
        binding.btnDataSkip.visibility = View.VISIBLE
        binding.btnDataSkip.text = "Cancel"
        binding.btnDataSkip.setOnClickListener { binding.dataOverlay.visibility = View.GONE }
        binding.tvDataStatus.text = "On this device:\n${DataAssets.localSummary(this)}\n\nChecking the server…"

        DataAssets.checkPending(this, force = true) { pending, total, err ->
            if (err != null || pending.isEmpty()) {
                binding.tvDataStatus.text = "Couldn't reach the chart server:\n${err ?: "no assets published"}"
                showDataAction("Retry") { reloadCharts() }
                return@checkPending
            }
            val mb = total / 1048576.0
            binding.tvDataStatus.text = String.format(
                Locale.US,
                "Re-download all chart data?\n\n%d file(s), %.0f MB\n\nUse Wi-Fi — this replaces what's on the device.",
                pending.size, mb
            )
            showDataAction("Re-download") { startDataDownload(pending, total) }
        }
    }

    // --- Offline readiness ---------------------------------------------------

    /** Hours of usable data left in a series, or null if we have none. */
    private fun hoursLeft(lastMs: Long?): Double? =
        lastMs?.let { (it - System.currentTimeMillis()) / 3_600_000.0 }

    /**
     * Deliberate pre-departure download: grabs weather + tide for a padded area and reports how
     * many hours of cover we have. There is no internet at sea, so this is the moment that decides
     * whether the passage has data or not.
     */
    private fun fetchOfflineData() {
        val m = map ?: return
        val b = m.projection.visibleRegion.latLngBounds
        val ne = b.northEast; val sw = b.southWest
        val pad = 0.35 // ~21 nm of slack around the current view
        val bounds = LatLngBounds.from(
            ne.latitude + pad, ne.longitude + pad, sw.latitude - pad, sw.longitude - pad
        )
        Toast.makeText(this, "Downloading offline weather + tide…", Toast.LENGTH_SHORT).show()
        weatherRepo.fetch(bounds) { w, t, err ->
            weather = w; tide = t
            if (w != null) {
                selectedHour = w.nearestHourIndex(System.currentTimeMillis())
                binding.seekTime.max = (w.hourCount() - 1).coerceAtLeast(0)
                binding.seekTime.progress = selectedHour
            }
            t?.let { binding.tideGraph.setData(it.timesMs, it.heightsFt) }
            applyHour()

            val wh = hoursLeft(w?.timesUtcMs?.lastOrNull())
            val th = hoursLeft(t?.timesMs?.lastOrNull())
            val msg = when {
                w == null && t == null -> err ?: "No data available"
                (wh ?: 0.0) < MIN_OFFLINE_HOURS || (th ?: 0.0) < MIN_OFFLINE_HOURS ->
                    String.format(
                        Locale.US, "⚠ Only wind %.0f h / tide %.0f h — less than %.0f h of cover",
                        wh ?: 0.0, th ?: 0.0, MIN_OFFLINE_HOURS
                    )
                else -> String.format(
                    Locale.US, "Offline ready · wind %.0f h, tide %.0f h", wh!!, th!!
                )
            }
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        }
    }

    /** Re-read the boat profile whenever we come back — draft/margin change what the router allows. */
    private fun applyBoatProfile() {
        val p = BoatProfile.load(this)
        boat.applyProfile(p)
        cruisingSpeedKn = p.cruiseKn
        unitsFt = p.units == "ft"
        binding.scaleBar.setUseFeet(unitsFt)
        // Safety-depth shading follows the boat, so repaint when the draft changes.
        loadedStyle?.let { applySafetyShading(it) }
        if (anchorMode) refreshAnchorUi()
    }

    // --- Phase 4 weather routing --------------------------------------------

    private fun computeRoute() {
        val rm = routeManager ?: return
        val dest = rm.lastWaypoint() ?: run {
            Toast.makeText(this, "Long-press a destination waypoint first", Toast.LENGTH_SHORT).show(); return
        }
        val start = if (rm.waypointCount() >= 2) rm.firstWaypoint()!!
        else lastLocation?.let { LatLng(it.latitude, it.longitude) } ?: run {
            Toast.makeText(this, "No GPS fix — drop a start waypoint too", Toast.LENGTH_SHORT).show(); return
        }
        boat.motorKn = cruisingSpeedKn
        if (routingGrid == null) {
            Toast.makeText(this, "No depth grid — routing without land avoidance", Toast.LENGTH_SHORT).show()
        }
        Toast.makeText(this, "Routing… (${boat.modeLabel()})", Toast.LENGTH_SHORT).show()

        val latN = maxOf(start.latitude, dest.latitude) + 0.15
        val latS = minOf(start.latitude, dest.latitude) - 0.15
        val lonE = maxOf(start.longitude, dest.longitude) + 0.15
        val lonW = minOf(start.longitude, dest.longitude) - 0.15
        val bounds = LatLngBounds.from(latN, lonE, latS, lonW)

        weatherRepo.fetch(bounds) { w, _, _ ->
            Thread {
                val res = WeatherRouter(w, boat, routingGrid).route(start, dest, System.currentTimeMillis())
                runOnUiThread {
                    rm.setComputedRoute(res.route)
                    val straight = GeoUtils.distanceNm(start, dest)
                    val routeNm = pathDistanceNm(res.route)
                    Toast.makeText(this, String.format(
                        Locale.US, "%s: %.1f nm · %s%s   (rhumb %.1f nm)",
                        boat.modeLabel(), routeNm, GeoUtils.formatHours(res.etaHours),
                        if (res.reached) "" else " ⚠partial", straight
                    ), Toast.LENGTH_LONG).show()
                }
            }.start()
        }
    }

    private fun pathDistanceNm(pts: List<LatLng>): Double {
        var d = 0.0
        for (i in 1 until pts.size) d += GeoUtils.distanceNm(pts[i - 1], pts[i])
        return d
    }

    // --- Phase 3 weather/tides ----------------------------------------------

    /** [silent] = background fetch to populate the HUD; don't nag with a toast on failure. */
    private fun fetchWeather(silent: Boolean = false) {
        val m = map ?: return
        lastWxFetchCenter = m.cameraPosition.target
        lastWxFetchZoom = m.cameraPosition.zoom
        lastWxFetchMs = System.currentTimeMillis()
        if (!silent) binding.tvWx.text = "Wx: fetching…"
        // Grid the wind over a bounds PADDED ~40% beyond the view, so the barbs extend past the screen
        // edges — camera drift/animation between this async call and its response can't leave the view bare.
        val vb = m.projection.visibleRegion.latLngBounds
        val padLat = (vb.latitudeNorth - vb.latitudeSouth) * 0.4
        val padLon = (vb.longitudeEast - vb.longitudeWest) * 0.4
        val bounds = LatLngBounds.Builder()
            .include(LatLng(vb.latitudeNorth + padLat, vb.longitudeEast + padLon))
            .include(LatLng(vb.latitudeSouth - padLat, vb.longitudeWest - padLon))
            .build()
        weatherRepo.fetch(bounds) { w, t, err ->
            weather = w; tide = t
            if (!silent) err?.let { Toast.makeText(this, it, Toast.LENGTH_SHORT).show() }
            // Only (re)select the current hour on an explicit open — a pan-driven silent refetch must
            // not clobber the forecast hour the user scrubbed to.
            if (w != null && !silent) {
                selectedHour = w.nearestHourIndex(System.currentTimeMillis())
                binding.seekTime.max = (w.hourCount() - 1).coerceAtLeast(0)
                binding.seekTime.progress = selectedHour
            }
            t?.let { binding.tideGraph.setData(it.timesMs, it.heightsFt) }
            applyHour()
        }
    }

    /** On camera-idle with the Wx panel open: refetch the wind grid over the new view if it panned or
     *  zoomed materially since the last fetch (throttled), so barbs always cover what's on screen. */
    private fun maybeRefetchWind(m: MapLibreMap) {
        val c = m.cameraPosition.target ?: return
        val last = lastWxFetchCenter
        val b = m.projection.visibleRegion.latLngBounds
        val spanLat = b.latitudeNorth - b.latitudeSouth
        val spanLon = b.longitudeEast - b.longitudeWest
        val moved = last == null ||
            Math.abs(c.latitude - last.latitude) > spanLat * 0.4 ||
            Math.abs(c.longitude - last.longitude) > spanLon * 0.4 ||
            Math.abs(m.cameraPosition.zoom - lastWxFetchZoom) > 1.2
        if (moved && System.currentTimeMillis() - lastWxFetchMs > 1500L) fetchWeather(silent = true)
    }

    private fun applyHour() {
        binding.tideGraph.useFeet = unitsFt   // Wx-panel tide graph in the active vertical unit
        val w = weather ?: return
        val m = map ?: return
        if (w.hourCount() == 0) return
        val h = selectedHour.coerceIn(0, w.hourCount() - 1)
        // Only paint the wind field when the Wx panel is open — the HUD wants the data, not the barbs.
        if (binding.weatherPanel.visibility == View.VISIBLE)
            weatherOverlay?.setWind(w.windAt(h), metersPerPixelNow(m))
        val tMs = w.timesUtcMs[h]
        binding.tideGraph.setMarker(tMs)                          // amber: selected forecast hour
        binding.tideGraph.setNow(System.currentTimeMillis())     // red: current time

        val center = m.cameraPosition.target
        val cw = center?.let { w.nearest(it.latitude, it.longitude, h) }
        val tideFt = tide?.heightAt(tMs)
        val whenStr = SimpleDateFormat("EEE HH:mm", Locale.US).format(Date(tMs))
        val windStr = if (cw != null)
            String.format(Locale.US, "%.0f kn %03.0f° G%.0f", cw.speedKn, cw.dirDeg, cw.gustKn)
        else "--"
        val tideStr = tideFt?.let {
            String.format(Locale.US, "%.1f %s", if (unitsFt) it else it * 0.3048, depthUnit)
        } ?: "--"
        val wh = hoursLeft(w.timesUtcMs.lastOrNull())
        val cover = wh?.let { String.format(Locale.US, " · %.0fh left", it) } ?: ""
        binding.tvWx.text = "$whenStr · Wind $windStr · Tide $tideStr$cover"

        // Never let stale data masquerade as live.
        binding.tvWndSrc.text = if (weatherRepo.lastServedFromCache) "forecast · cached" else "forecast"
        updateHud()
    }

    private fun startWxTicker() {
        wxHandler.removeCallbacks(wxTicker)
        wxHandler.postDelayed(wxTicker, 30_000)
    }

    private fun stopWxTicker() = wxHandler.removeCallbacks(wxTicker)

    // --- Nav legend (top-left HUD) ------------------------------------------

    /** Right-hand data sidebar: DEPTH / SOG / COG / POSITION / WIND / TIME / TIDE. */
    private fun updateHud() {
        binding.tvTime.text = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        updateTide()
        val loc = lastLocation
        if (loc == null) {
            binding.tvDepth.text = "--"; binding.tvSog.text = "--"; binding.tvCog.text = "--"
            binding.tvLat.text = "waiting"; binding.tvLon.text = "for GPS"; binding.tvWnd.text = "--"
            binding.tvTempAir.text = "Air --"; binding.tvTempSea.text = "Sea --"
            return
        }
        binding.tvCog.text = if (loc.hasBearing()) String.format(Locale.US, "%03.0f°", loc.bearing) else "---"
        binding.tvSog.text = String.format(Locale.US, "%.1f", loc.speed * 1.94384f)
        binding.tvLat.text = GeoUtils.formatLat(loc.latitude)
        binding.tvLon.text = GeoUtils.formatLon(loc.longitude)
        binding.tvWnd.text = windAtBoat(loc.latitude, loc.longitude)
        updateTemp(loc.latitude, loc.longitude)
        updateDepth(loc.latitude, loc.longitude)
    }

    /** Air + sea-surface temperature at the boat, in °C (metric) or °F (imperial). */
    private fun updateTemp(lat: Double, lon: Double) {
        val w = weather
        val h = if (w != null && w.hourCount() > 0) selectedHour.coerceIn(0, w.hourCount() - 1) else 0
        val air = w?.airTempCAt(lat, lon, h)
        val sea = w?.seaTempCAt(lat, lon, h)
        binding.tvTempAir.text = air?.let { "Air ${fmtTemp(it)}" } ?: "Air --"
        binding.tvTempSea.text = sea?.let { "Sea ${fmtTemp(it)}" } ?: "Sea --"
    }

    /** Temperature in the configured unit (°C metric / °F imperial), from a Celsius value. */
    private fun fmtTemp(c: Double): String =
        if (unitsFt) String.format(Locale.US, "%.0f°F", c * 9.0 / 5.0 + 32.0)
        else String.format(Locale.US, "%.0f°C", c)

    /** Single-tap readout: position + charted depth + tide-corrected depth at the tapped point. */
    private fun showDepthOverlay(ll: LatLng) {
        val fix = chartedDepthMin(ll.latitude, ll.longitude)
        val tideM = tideNowM()
        // One value per line: lat, lon, charted depth, tide-corrected "now" depth, dismiss hint.
        val sb = StringBuilder()
        sb.append(GeoUtils.formatLat(ll.latitude)).append("\n")
        sb.append(GeoUtils.formatLon(ll.longitude))
        if (fix.minM == null) {
            sb.append("\nNo charted data")
        } else {
            sb.append("\nCharted ").append(fmtDepthVal(fix.minM)).append(" ").append(depthUnit)
            if (tideM != null) {
                sb.append("\nNow ").append(fmtDepthVal(fix.minM + tideM)).append(" ").append(depthUnit)
            }
        }
        sb.append("\ntap to dismiss")
        binding.infoCard.text = sb.toString()
        binding.infoCard.visibility = View.VISIBLE
    }

    /** DEPTH = charted (MLLW) + predicted tide. Coloured by clearance under this boat's keel. */
    private fun updateDepth(lat: Double, lon: Double) {
        val fix = chartedDepthMin(lat, lon)
        val tideM = tideNowM()
        val actualM = fix.minM?.plus(tideM ?: 0.0)
        val ukcM = actualM?.minus(boat.draftM)

        binding.tvDepth.text = when {
            actualM == null -> "--"
            actualM <= 0.0 -> "DRY"
            else -> fmtDepthVal(actualM)
        }
        binding.tvDepthSrc.text = buildString {
            append(depthUnit).append(" · ").append(fix.src)
            if (tideM != null) append(" +tide") else if (fix.minM != null) append(" (no tide)")
        }
        binding.tvUkc.text = ukcM?.let {
            val u = if (unitsFt) it / 0.3048 else it
            String.format(Locale.US, "UKC %+.1f %s", u, depthUnit)
        } ?: "UKC --"

        val c = ukcColor(ukcM)
        binding.tvDepth.setTextColor(c)
        binding.tvUkc.setTextColor(c)
    }

    /**
     * Charted depth beneath the boat. Preferred source is the rendered chart itself (DRGARE first —
     * a dredged channel overrides the surrounding depth area — then DEPARE), which gives the S-57
     * DRVAL1..DRVAL2 range. Falls back to the coarse routing grid when the boat is off-screen or
     * the chart has no depth area there.
     */
    /** Shallowest charted depth at a point (S-57 DRVAL1, metres below MLLW), and where it came from. */
    private data class DepthFix(val minM: Double?, val src: String)

    private fun chartedDepthMin(lat: Double, lon: Double): DepthFix {
        val m = map ?: return DepthFix(null, "no map")
        try {
            val pt = m.projection.toScreenLocation(LatLng(lat, lon))
            val feats = m.queryRenderedFeatures(pt, *DEPTH_LAYERS)
            // Bands overlap: the overview band generalises the harbour away. Always take the
            // LARGEST-SCALE (highest INTU) survey available here, the way an ECDIS does.
            val f = feats
                .filter { it.hasNonNullValueForProperty("DRVAL1") }
                .maxByOrNull { it.getNumberProperty("INTU")?.toInt() ?: 0 }
            val d1 = f?.getNumberProperty("DRVAL1")?.toDouble()
            if (d1 != null) return DepthFix(d1, "chart")
        } catch (_: Exception) { /* fall through to the grid */ }

        val d = routingGrid?.depthAt(lat, lon) ?: return DepthFix(null, "no data")
        return DepthFix(d, "grid")
    }

    /** Predicted tide height right now, in metres. NOAA CO-OPS gives feet above MLLW —
     *  the SAME datum the chart soundings use (ENC DSPM_SDAT = 12), so it simply adds. */
    private fun tideNowM(): Double? =
        tide?.heightAt(System.currentTimeMillis())?.let { it * 0.3048 }

    /** TIDE tile: level now, direction of travel, and the level 15 min either side of now. */
    private fun updateTide() {
        val t = tide
        val now = System.currentTimeMillis()
        val h = t?.heightAt(now)
        // NOAA CO-OPS gives feet; convert to metres for the metric unit.
        val toU = if (unitsFt) 1.0 else 0.3048
        binding.tideMini.compact = true
        if (h == null) {
            binding.tvTide.text = "--"
            binding.tvTideSrc.text = "$depthUnit MLLW"
            binding.tideMini.setData(LongArray(0), DoubleArray(0))
            return
        }
        val before = t.heightAt(now - TIDE_WINDOW_MS)
        val after = t.heightAt(now + TIDE_WINDOW_MS)

        val arrow = when {
            before == null || after == null -> ""
            after > before + 0.01 -> " ↑"
            after < before - 0.01 -> " ↓"
            else -> " →"   // slack: at the top or bottom of the curve
        }
        binding.tvTide.text = String.format(Locale.US, "%.1f", h * toU)
        binding.tvTideSrc.text = "$depthUnit MLLW$arrow"

        // Mini tide curve (replaces the ±15m text): a ~12 h window around now, red now-marker on it.
        val lo = now - 3 * 3600_000L
        val hi = now + 9 * 3600_000L
        val idx = t.timesMs.indices.filter { t.timesMs[it] in lo..hi }
        if (idx.size >= 2) {
            binding.tideMini.setData(
                LongArray(idx.size) { t.timesMs[idx[it]] },
                DoubleArray(idx.size) { t.heightsFt[idx[it]] }
            )
            binding.tideMini.setNow(now)
        } else {
            binding.tideMini.setData(LongArray(0), DoubleArray(0))
        }
    }

    /**
     * Under-keel clearance colouring. White while there's comfortable water; reddens as the margin
     * closes; hard red once the keel is at or below the bottom. Thresholds in FEET, as asked.
     */
    private fun ukcColor(ukcM: Double?): Int {
        if (ukcM == null) return Color.WHITE
        val ft = ukcM / 0.3048
        if (ft <= 0.0) return Color.parseColor("#FF1744")   // aground / no clearance
        if (ft >= UKC_SAFE_FT) return Color.WHITE
        // Caution band: bright amber → red as clearance shoals. Deliberately a saturated, high-contrast
        // ramp (not the old white→pink one) so the reading stays legible on a sunlit deck.
        val t = 1.0 - ft / UKC_SAFE_FT                       // 0 at 6 ft -> 1 at 0 ft
        val g = (196 + (23 - 196) * t).toInt()               // amber(255,196,0) -> red(255,23,68)
        val b = (0 + (68 - 0) * t).toInt()
        return Color.rgb(255, g, b)
    }

    /** Forecast wind at the boat: direction it blows FROM, plus speed. */
    private fun windAtBoat(lat: Double, lon: Double): String {
        val w = weather ?: return "--"
        if (w.hourCount() == 0) return "--"
        val h = selectedHour.coerceIn(0, w.hourCount() - 1)
        val p = w.nearest(lat, lon, h) ?: return "--"
        return String.format(Locale.US, "%03.0f° %.0f kn", p.dirDeg, p.speedKn)
    }

    // --- Orientation chrome -------------------------------------------------
    // The activity keeps configChanges=orientation (so the MapView is NOT torn down on rotation),
    // which means the layout is never re-inflated and layout-land/ would never load. So the few
    // views that must differ in landscape are adjusted here in code instead, on every rotation.
    override fun onConfigurationChanged(newConfig: Configuration) {
        // MapLibre's GL renderer can SIGSEGV in glDrawElements when the map surface is resized while
        // the camera is TILTED (nav mode). Guard the rotation two ways: (1) flatten the tilt so the
        // geometry that resizes is the safe top-down plane, and (2) PAUSE the map's render loop across
        // the surface resize so nothing draws mid-resize. Both are undone once the resize has settled.
        if (navMode) map?.let { m ->
            m.locationComponent.let { lc -> if (lc.isLocationComponentActivated) lc.tiltWhileTracking(0.0) }
            m.moveCamera(CameraUpdateFactory.tiltTo(0.0))
        }
        binding.mapView.onPause()   // stop rendering during the resize
        if (bothMapsActive()) binding.mapView2.onPause()
        binding.mapView.postDelayed({
            binding.mapView.onResume()
            if (bothMapsActive()) binding.mapView2.onResume()
            if (navMode) map?.let { m ->
                m.locationComponent.let { lc -> if (lc.isLocationComponentActivated) lc.tiltWhileTracking(navTiltDeg()) }
                m.animateCamera(CameraUpdateFactory.tiltTo(navTiltDeg()), 300)
            }
        }, 500)
        super.onConfigurationChanged(newConfig)
        applyOrientationChrome(newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE)
        // Re-pad the map + re-size the nav HUD for the new orientation's pane split.
        if (mapSplit != null) binding.screenHost.post { applyMapSplitRegion() }
    }

    private fun applyOrientationChrome(landscape: Boolean) {
        // HUD sidebar: content-sized in BOTH orientations (the panel ends after the tide tile, same
        // as portrait) so the zoom buttons can sit beneath it at the right edge. A ScrollView caps
        // at the screen height and scrolls internally if a short screen clips the stacked tiles.
        binding.hudScroll.updateLayoutParams<FrameLayout.LayoutParams> {
            height = FrameLayout.LayoutParams.WRAP_CONTENT
        }
        // Weather panel + zoom buttons clear the 116dp sidebar in landscape so nothing hides under it.
        binding.weatherPanel.updateLayoutParams<FrameLayout.LayoutParams> {
            marginEnd = if (landscape) dp(116) else 0
        }
        updateZoomControlsMargins()
        // Keep the zoom controls / scale bar clear of whichever bottom panel is open.
        repositionBottomOverlays()
    }

    /** Zoom/center buttons: bottom-right of the pane holding their target map, close to that
     *  pane's right edge, and never overlapping the (content-sized) sidebar above them.
     *  - full-screen chart / nav → bottom-right of the screen
     *  - Chart+Nav (two maps)   → bottom-right of pane B (the nav map)
     *  - map + gauge split      → bottom-right of pane A (the gauge pane is opaque and would
     *                             cover them at the screen edge) */
    private fun updateZoomControlsMargins() {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val base = if (landscape) dp(96) else dp(120)
        val w = binding.contentArea.width; val h = binding.contentArea.height
        var endInset = dp(10)          // from the screen's right edge
        var paneBottomInset = 0        // pane bottom → screen bottom distance
        val split = mapSplit
        if (split != null && w > 0 && h > 0) {
            val halfW = w / 2; val halfH = h / 2
            val bothMaps = isMapScreen(split.first) && isMapScreen(split.second)
            // Pane B (right/bottom) for two live maps, else pane A (left/top) holds the only map.
            val region = when {
                bothMaps -> if (landscape) intArrayOf(halfW, 0, halfW, h) else intArrayOf(0, halfH, w, halfH)
                else -> if (landscape) intArrayOf(0, 0, halfW, h) else intArrayOf(0, 0, w, halfH)
            }
            endInset = (w - (region[0] + region[2])) + dp(10)
            paneBottomInset = h - (region[1] + region[3])
        }
        binding.zoomControls.updateLayoutParams<FrameLayout.LayoutParams> {
            marginEnd = endInset
            bottomMargin = paneBottomInset + base
        }
        // The sidebar sits at the same pane edge on chart screens (base + chart-pane splits);
        // post-layout, if the bottom-anchored stack would reach up into it, drop the buttons to
        // just below it — but never below their pane (an opaque gauge could sit there).
        binding.contentArea.post {
            val sidebarAboveButtons = !navMode && binding.hudScroll.visibility == View.VISIBLE &&
                (mapSplit == null || (chartPaneSplit() && !bothMapsActive()))
            if (!sidebarAboveButtons) return@post
            val ch = binding.contentArea.height
            val btnH = binding.zoomControls.height
            if (ch == 0 || btnH == 0) return@post
            val current = paneBottomInset + base
            val belowSidebar = ch - (binding.hudScroll.bottom + dp(8)) - btnH
            if (belowSidebar < current) binding.zoomControls.updateLayoutParams<FrameLayout.LayoutParams> {
                bottomMargin = maxOf(paneBottomInset + dp(10), belowSidebar)
            }
        }
    }

    /** Keep the zoom controls + scale bar ABOVE whichever bottom panel (weather or anchor) is open —
     *  never behind it or over it. Panel heights are dynamic, so measure once laid out; when neither
     *  is open they drop back to their resting positions. */
    private fun repositionBottomOverlays() {
        val landscape = resources.configuration.orientation ==
            android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val zoomRest = if (landscape) dp(96) else dp(120)
        val scaleRest = dp(10)
        val panel: View? = when {
            binding.weatherPanel.visibility == View.VISIBLE -> binding.weatherPanel
            else -> null
        }
        if (panel == null) {
            binding.zoomControls.updateLayoutParams<FrameLayout.LayoutParams> { bottomMargin = zoomRest }
            binding.scaleBar.updateLayoutParams<FrameLayout.LayoutParams> { bottomMargin = scaleRest }
            return
        }
        panel.post {
            val above = panel.height + dp(8)
            binding.zoomControls.updateLayoutParams<FrameLayout.LayoutParams> {
                bottomMargin = maxOf(zoomRest, above)
            }
            binding.scaleBar.updateLayoutParams<FrameLayout.LayoutParams> { bottomMargin = above }
        }
    }

    // --- Auto-hiding bottom bar ---------------------------------------------
    // The left icon toolbar + on-map scale bar stay out of the way: shown on any chart interaction,
    // then faded out after a few idle seconds so the plotter is uncluttered under way. The weather
    // panel is a separate manual toggle and is not part of this group.
    // --- Auto-hiding chrome — ONE policy for every screen --------------------
    // All transient chrome (left toolbar + scale bars) obeys a single policy: any map
    // interaction shows it, one shared timer fades it after CHROME_IDLE_MS. Which views
    // participate depends on the screen; the show/fade mechanics never differ.

    private val chromeHider = Runnable { hideChrome() }

    /** The plain chart is the base screen. The left toolbar belongs here (incl. while anchored —
     *  the ⚓ raise/adjust control lives on the toolbar) — not in nav mode and not under a covering
     *  fragment (Route/Weather/…). */
    private fun onChartBase(): Boolean =
        !navMode && binding.screenHost.visibility != View.VISIBLE

    /** True when a chart pane is showing in a split — its toolbar should be reachable (waypoints,
     *  marks, anchor, depth + weather toggles). */
    private fun chartPaneSplit() = mapSplit?.first == "chart"

    /** The transient chrome for the current screen: the chart toolbar on the chart base / a chart
     *  split pane, plus the screen's scale bar(s) — both in a two-map split, the primary bar on any
     *  other map screen, none under a covering gauge screen. */
    private fun transientChrome(): List<View> {
        val views = ArrayList<View>()
        if (onChartBase() || chartPaneSplit()) views.add(binding.leftToolbar)
        when {
            bothMapsActive() -> { views.add(binding.scaleBar); views.add(binding.scaleBar2) }
            mapSplit != null || navMode || onChartBase() -> views.add(binding.scaleBar)
        }
        return views
    }

    /** Show the current screen's transient chrome and restart the shared idle fade. */
    private fun flashChrome() {
        val views = transientChrome()
        if (views.isEmpty()) return
        for (v in views) {
            v.animate().cancel()
            v.alpha = 1f
            v.visibility = View.VISIBLE
        }
        binding.contentArea.removeCallbacks(chromeHider)
        binding.contentArea.postDelayed(chromeHider, CHROME_IDLE_MS)
    }

    private fun hideChrome() {
        for (v in listOf<View>(binding.leftToolbar, binding.scaleBar, binding.scaleBar2)) {
            if (v.visibility == View.VISIBLE) {
                v.animate().alpha(0f).setDuration(220)
                    .withEndAction { v.visibility = View.INVISIBLE }.start()
            }
        }
    }

    /** Hide the auto-hiding toolbar/scale bars + the Wx panel when entering a full-screen chart mode. */
    private fun retireChartUi(clearWeather: Boolean = true) {
        binding.contentArea.removeCallbacks(chromeHider)
        for (v in listOf<View>(binding.leftToolbar, binding.scaleBar, binding.scaleBar2)) {
            v.animate().cancel(); v.visibility = View.GONE
        }
        binding.weatherPanel.visibility = View.GONE
        repositionBottomOverlays()
        // A chart split keeps the wind overlay on its chart pane, so don't clear it there.
        if (clearWeather) { weatherOverlay?.clear(); stopWxTicker() }
    }

    // --- Track history (always-on recording, drawn + browsed) ----------------

    /** The WatchService's always-on track directory (one CSV per UTC day). */
    private fun trackDir() = File(getExternalFilesDir(null) ?: filesDir, "tracks")

    /** (Re)load the last [historyTracksN] day-tracks onto the chart, off the main thread. */
    private fun reloadTrackHistory() {
        val overlay = trackHistory ?: return
        val n = historyTracksN
        val dir = trackDir()
        Thread {
            val tracks = if (n <= 0) emptyList()
                else TrackHistory.listDays(dir).take(n).map { TrackHistory.loadDay(dir, it) }
            runOnUiThread { if (trackHistory === overlay) overlay.set(tracks) }
        }.start()
    }

    /** Menu → Tracks: every stored day-track with share / export / delete. */
    private fun showTracksDialog() {
        val dir = trackDir()
        val scroll = android.widget.ScrollView(this)
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#101418"))
            setPadding(dp(20), dp(16), dp(20), dp(16))
        }
        scroll.addView(list)
        val dlg = androidx.appcompat.app.AlertDialog.Builder(this).setView(scroll).create()

        fun row(text: String, sub: String, day: Long, refresh: () -> Unit): View =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.bg_menu_tile)
                setPadding(dp(12), dp(9), dp(12), dp(9))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { setMargins(0, dp(3), 0, dp(3)) }
                addView(TextView(this@MainActivity).apply {
                    this.text = text; setTextColor(Color.WHITE); textSize = 14f
                })
                addView(TextView(this@MainActivity).apply {
                    this.text = sub; setTextColor(Color.parseColor("#8FA6B4")); textSize = 11f
                })
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    fun action(label: String, onTap: () -> Unit) =
                        addView(TextView(this@MainActivity).apply {
                            this.text = label; setTextColor(Color.parseColor("#6FC6E8")); textSize = 13f
                            setPadding(0, dp(6), dp(24), 0)
                            setOnClickListener { onTap() }
                        })
                    action("Share") { shareTrackJson(day) }
                    action("Export") { exportTrackJson(day) }
                    action("Delete") {
                        androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                            .setMessage("Delete the ${TrackHistory.dayTitle(day)} track?")
                            .setPositiveButton("Delete") { _, _ ->
                                File(dir, "track-$day.csv").delete()
                                reloadTrackHistory(); refresh()
                            }
                            .setNegativeButton("Cancel", null).show()
                    }
                })
            }

        fun refill() {
            list.removeAllViews()
            list.addView(TextView(this).apply {
                text = "Tracks"; setTextColor(Color.WHITE); textSize = 19f; setPadding(0, 0, 0, dp(10))
            })
            val days = TrackHistory.listDays(dir)
            if (days.isEmpty()) {
                list.addView(TextView(this).apply {
                    text = "No recorded tracks yet"; setTextColor(Color.parseColor("#8FA6B4")); textSize = 13f
                })
                return
            }
            // Stats parse every file; tracks are small CSVs but keep the UI thread clean anyway.
            Thread {
                val rows = days.map { day ->
                    val t = TrackHistory.loadDay(dir, day)
                    Triple(day, t.points.size,
                        String.format(Locale.US, "%.1f nm", t.distanceNm()))
                }
                runOnUiThread {
                    if (!dlg.isShowing) return@runOnUiThread
                    for ((day, pts, dist) in rows) list.addView(
                        row(TrackHistory.dayTitle(day), "$dist · $pts points", day) { refill() })
                }
            }.start()
        }
        refill()
        dlg.show()
    }

    private fun shareTrackJson(day: Long) {
        Thread {
            val json = TrackHistory.toJson(TrackHistory.loadDay(trackDir(), day))
            runOnUiThread {
                val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = "application/json"
                    putExtra(android.content.Intent.EXTRA_SUBJECT, "MyNavvy track ${TrackHistory.dayTitle(day)}")
                    putExtra(android.content.Intent.EXTRA_TEXT, json)
                }
                startActivity(android.content.Intent.createChooser(send, "Share track"))
            }
        }.start()
    }

    private fun exportTrackJson(day: Long) {
        Thread {
            val json = TrackHistory.toJson(TrackHistory.loadDay(trackDir(), day))
            val msg = try {
                val out = File(File(getExternalFilesDir(null), "logs").apply { mkdirs() },
                    "mynavvy_track_${TrackHistory.dayLabel(day).replace(' ', '_')}_$day.json")
                out.writeText(json)
                "Saved ${out.path}"
            } catch (e: Exception) {
                Diagnostics.capture(e); "Export failed: ${e.message}"
            }
            runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
        }.start()
    }

    private fun saveGpx() {
        val rm = routeManager
        if (rm == null || !rm.hasExportable()) {
            Toast.makeText(this, "Nothing to save yet", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val dir = File(getExternalFilesDir(null), "logs").apply { mkdirs() }
            val name = "mynavvy_" +
                SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".gpx"
            val f = File(dir, name)
            f.writeText(rm.buildGpx())
            Toast.makeText(this, "Saved ${f.path}", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Diagnostics.capture(e)
            Toast.makeText(this, "Save failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // --- Screen data bridge -------------------------------------------------
    // The overlaid planning screens (Route / Weather) are fragments in screenHost; they read the
    // live route/weather state through these accessors rather than owning their own copies.

    fun uiRouteWaypoints(): List<LatLng> = routeManager?.waypoints() ?: emptyList()
    fun uiCruiseKn(): Double = cruisingSpeedKn
    fun uiClearRoute() { routeManager?.clearRoute() }
    fun uiExportGpx() { saveGpx() }
    fun uiWeather(): WeatherRepository.Weather? = weather
    fun uiTide(): WeatherRepository.Tide? = tide
    fun uiLastLocation(): Location? = lastLocation
    /** True when vertical measures (depth / tide) display in feet; false for metres. */
    fun uiUnitsFt(): Boolean = unitsFt
    fun uiSelectedHour(): Int = selectedHour
    fun uiFetchOfflineData() = fetchOfflineData()
    fun uiWeatherCachedAtMs(name: String): Long? = weatherRepo.cachedAtMs(name)

    /** Forecast wind at the boat for the current hour (direction it blows FROM + speed), or null. */
    fun uiWindAtBoatNow(): WeatherRepository.WindPoint? {
        val w = weather ?: return null
        val loc = lastLocation ?: return null
        return w.nearest(loc.latitude, loc.longitude, w.nearestHourIndex(System.currentTimeMillis()))
    }

    /** Tide-corrected depth (m) and under-keel clearance (m) at the boat, either possibly null. */
    fun uiDepthUkcNow(): Pair<Double?, Double?> {
        val loc = lastLocation ?: return null to null
        val actualM = chartedDepthMin(loc.latitude, loc.longitude).minM?.plus(tideNowM() ?: 0.0)
        return actualM to actualM?.minus(boat.draftM)
    }

    fun uiUkcColor(ukcM: Double?): Int = ukcColor(ukcM)

    // --- Trip log -----------------------------------------------------------

    /** Fold a fresh fix into the trip totals: distance, time underway, and top speed. Teleport-safe
     *  (segments over [TRIP_TELEPORT_NM] are ignored as a jump, not travel). */
    private fun updateTrip(location: Location) {
        val now = System.currentTimeMillis()
        val sogKn = location.speed * 1.94384   // m/s → kn (0 when the fix carries no speed)
        if (tripStartMs == 0L) tripStartMs = now
        if (!tripLastLat.isNaN()) {
            val segNm = GeoUtils.distanceNm(LatLng(tripLastLat, tripLastLon),
                LatLng(location.latitude, location.longitude))
            if (segNm < TRIP_TELEPORT_NM) {
                tripDistNm += segNm
                val dtMs = now - tripLastMs
                if (sogKn > TRIP_MOVING_KN && dtMs in 1..60_000) tripMovingMs += dtMs
            }
        }
        if (sogKn > tripMaxSogKn) tripMaxSogKn = sogKn
        if (sogKn > TRIP_MOVING_KN) { tripSogSumKn += sogKn; tripSogCount++ }
        tripLastMs = now; tripLastLat = location.latitude; tripLastLon = location.longitude
    }

    fun uiTripStats(): TripStats {
        val elapsedMs = if (tripStartMs == 0L) 0L else System.currentTimeMillis() - tripStartMs
        val avgKn = if (tripSogCount > 0L) tripSogSumKn / tripSogCount else 0.0
        return TripStats(tripStartMs, elapsedMs, tripDistNm, tripMovingMs, avgKn, tripMaxSogKn)
    }

    fun uiResetTrip() {
        tripStartMs = System.currentTimeMillis()
        tripDistNm = 0.0; tripMovingMs = 0L; tripMaxSogKn = 0.0
        tripSogSumKn = 0.0; tripSogCount = 0L
        tripLastMs = 0L; tripLastLat = Double.NaN; tripLastLon = Double.NaN
        saveState()
    }

    // --- Location -----------------------------------------------------------

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** How old a fix is (ms), preferring the monotonic elapsed-realtime clock; falls back to the wall
     *  clock, and to "fresh" (0) when a fix carries no timestamp at all. */
    private fun fixAgeMs(loc: Location, nowElapsedMs: Long): Long {
        val ern = loc.elapsedRealtimeNanos
        if (ern > 0L) return nowElapsedMs - ern / 1_000_000L
        if (loc.time > 0L) return System.currentTimeMillis() - loc.time
        return 0L
    }

    /** Ensure location (and notification) permission, then start + bind the always-on [WatchService]
     *  that owns GPS, spike filtering, the anchor watch and track recording. The Activity only
     *  observes clean fixes via [onFix]. */
    private fun ensureLocationUpdates() {
        if (!hasLocationPermission()) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), REQ_LOCATION
            )
            return
        }
        ensureNotificationPermission()
        startAndBindWatch()
    }

    private fun startAndBindWatch() {
        if (!hasLocationPermission()) return
        val i = android.content.Intent(this, WatchService::class.java)
        try { ContextCompat.startForegroundService(this, i) }
        catch (t: Throwable) { Log.w("MyNavvy", "watch service start failed: ${t.message}") }
        if (!watchBound) try { bindService(i, watchConn, Context.BIND_AUTO_CREATE) } catch (_: Throwable) {}
    }

    private fun activateLocationComponent(style: Style) {
        if (!hasLocationPermission()) return
        val m = map ?: return
        // Recolor the puck's small direction arrow so it actually reads against the chart
        // (default is a low-contrast blue chevron). Same arrow, contrast tint.
        // Blank the built-in puck (its bitmaps don't render reliably here); BoatMarker draws the boat.
        // The component stays active only to drive camera boat-follow.
        val puckOptions = LocationComponentOptions.builder(this)
            .foregroundDrawable(R.drawable.puck_hidden)
            .backgroundDrawable(R.drawable.puck_hidden)
            .bearingDrawable(R.drawable.puck_hidden)
            .elevation(0f)          // no drop shadow (it bled through as a dark ring at some zooms)
            .accuracyAlpha(0f)      // no accuracy circle
            .build()
        @Suppress("MissingPermission")
        m.locationComponent.apply {
            if (!isLocationComponentActivated) {
                activateLocationComponent(
                    LocationComponentActivationOptions.builder(this@MainActivity, style)
                        .locationComponentOptions(puckOptions)
                        .useDefaultLocationEngine(false)
                        .build()
                )
            } else {
                applyStyle(puckOptions)
            }
            isLocationComponentEnabled = true
            cameraMode = CameraMode.TRACKING
            // Keep the round puck (COMPASS render mode), but feed its pointer our computed COG via a
            // custom compass engine instead of the phone magnetometer (which spins freely at sea / on
            // the emulator). renderMode stays COMPASS; only the heading source changes.
            compassEngine = cogCompass
            renderMode = RenderMode.COMPASS
            lastLocation?.let { forceLocationUpdate(it) }
            cogDeg?.let { cogCompass.setHeading(it.toFloat()) }
            // A user pan/zoom dismisses tracking (the chart stops following the boat); schedule a
            // snap back to boat-centred after the idle timeout. Added once — the component persists.
            if (!trackingListenerAdded) {
                addOnCameraTrackingChangedListener(object : OnCameraTrackingChangedListener {
                    override fun onCameraTrackingDismissed() = scheduleRecenter()
                    override fun onCameraTrackingChanged(currentMode: Int) {}
                })
                trackingListenerAdded = true
            }
        }
    }

    /** Manual "find my boat": snap the camera to the last fix and re-engage boat-follow. Cancels any
     *  pending auto-recenter so the two don't stack. */
    private fun centerOnBoat() {
        val m = map ?: return
        recenterHandler.removeCallbacks(recenterRunnable)
        val loc = lastLocation
        if (loc == null) {
            Toast.makeText(this, "No GPS fix yet", Toast.LENGTH_SHORT).show()
            return
        }
        // Snap to the boat AND back to the configured default range (a chartplotter "home" button).
        m.moveCamera(CameraUpdateFactory.newLatLngZoom(
            LatLng(loc.latitude, loc.longitude), zoomForRangeNm(defaultRangeNm, loc.latitude)))
        m.locationComponent.takeIf { it.isLocationComponentActivated }?.let { lc ->
            @Suppress("MissingPermission")
            lc.cameraMode = if (navMode) navCameraMode() else CameraMode.TRACKING
        }
    }

    /** Mid-gesture: hold any pending auto-recenter so the chart can't snap back under the finger. */
    private fun holdRecenter() = recenterHandler.removeCallbacks(recenterRunnable)

    /** Gesture over: (re)start the auto-recenter countdown — but only if boat-follow is actually
     *  off (camera NONE). A pinch while still tracking shouldn't arm a pointless timer. */
    private fun scheduleRecenterIfUnfollowed() {
        val lc = map?.locationComponent ?: return
        if (lc.isLocationComponentActivated && lc.cameraMode == CameraMode.NONE) scheduleRecenter()
    }

    /** User moved the chart off the boat — arm the auto-return (unless a fixed framing owns the
     *  camera: the anchor watch, or a full-screen fragment). */
    private fun scheduleRecenter() {
        recenterHandler.removeCallbacks(recenterRunnable)
        // A chart split pane still auto-recenters its map; other covering screens don't.
        if (anchorMode || (binding.screenHost.visibility == View.VISIBLE && !chartPaneSplit())) return
        recenterHandler.postDelayed(recenterRunnable, recenterTimeoutMs)
    }

    /** Re-engage boat-follow, keeping the user's current zoom (TRACKING never changes zoom). Uses the
     *  course-up GPS mode while navigating, north-up otherwise. */
    private fun recenterOnBoat() {
        if (anchorMode || (binding.screenHost.visibility == View.VISIBLE && !chartPaneSplit())) return
        map?.locationComponent?.let { lc ->
            if (lc.isLocationComponentActivated) {
                @Suppress("MissingPermission")
                lc.cameraMode = if (navMode) navCameraMode() else CameraMode.TRACKING
            }
        }
    }

    /** A clean, filtered fix delivered by [WatchService] (COG already stamped on it). We do UI ONLY
     *  here — the spike/stale/precision filtering, COG derivation and persistent track recording all
     *  live in the service now, so the map and the background watch never diverge. Runs on the main
     *  thread (the service posts it there). [teleported] = a confirmed large jump → clear the trail. */
    override fun onFix(location: Location, teleported: Boolean) {
        lastLocation = location
        cogDeg = if (location.hasBearing()) location.bearing.toDouble() else null
        cogDeg?.let { cogCompass.setHeading(it.toFloat()) }
        if (teleported) routeManager?.clearTrail()

        map?.locationComponent?.let { lc ->
            if (lc.isLocationComponentActivated) {
                @Suppress("MissingPermission")
                lc.forceLocationUpdate(location)   // drives camera boat-follow (puck itself is blanked)
            }
        }
        // Our own always-renders boat marker (the built-in puck is unreliable against this style).
        map?.let { m ->
            boatMarker?.update(location.latitude, location.longitude, cogDeg, metersPerPixelNow(m))
        }
        updateTrip(location)
        updateAnchorButton()   // safety: grey the ⚓ out while making way (unless already anchored)
        // Session breadcrumb trail (visual) — the persistent 6-month track is recorded in the service.
        routeManager?.addTrailPoint(location.latitude, location.longitude)

        updateHud()
        if (anchorMode) refreshAnchorUi()
        if (navHudActive()) refreshNavUi()
        // Chart+Nav: follow the boat on the second map, but hold off for 10 s after the user pans it.
        if (bothMapsActive() &&
            android.os.SystemClock.elapsedRealtime() - secondMapGestureAt > MAP_RECENTER_MS)
            updateSecondMapCamera()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_LOCATION &&
            grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            ensureLocationUpdates()
            loadedStyle?.let { activateLocationComponent(it) }
        }
    }

    // --- MapView lifecycle passthrough -------------------------------------

    override fun onStart() { super.onStart(); binding.mapView.onStart(); binding.mapView2.onStart(); startAndBindWatch() }
    override fun onResume() {
        super.onResume()
        binding.mapView.onResume()
        binding.mapView2.onResume()
        applyBoatProfile()
        if (binding.weatherPanel.visibility == View.VISIBLE) startWxTicker()
    }
    override fun onPause() { stopWxTicker(); binding.mapView.onPause(); binding.mapView2.onPause(); super.onPause() }
    override fun onStop() { saveState(); unbindWatch(); binding.mapView.onStop(); binding.mapView2.onStop(); super.onStop() }

    /** Unbind from the watch service (it keeps running as a started foreground service). */
    private fun unbindWatch() {
        if (!watchBound) return
        watch?.setCallback(null)
        try { unbindService(watchConn) } catch (_: Throwable) {}
        watch = null; watchBound = false
    }
    override fun onLowMemory() { super.onLowMemory(); binding.mapView.onLowMemory(); binding.mapView2.onLowMemory() }
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState); binding.mapView.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        stopAlarm()
        unbindWatch()
        // Fully closing and not anchored → stop the background watch (frees GPS). Keep it running while
        // anchored so the drag alarm survives even a full app close.
        if (isFinishing && anchorWatch?.isSet() != true) {
            try { stopService(android.content.Intent(this, WatchService::class.java)) } catch (_: Throwable) {}
        }
        recenterHandler.removeCallbacksAndMessages(null)
        tileServer?.stop()
        basemapServer?.stop()
        binding.mapView.onDestroy()
        binding.mapView2.onDestroy()
        super.onDestroy()
    }

    companion object {
        /** Under-keel clearance (ft) at or above which the depth reads plain white. */
        private const val UKC_SAFE_FT = 6.0
        /** Half-width of the tide window shown in the legend. */
        private const val TIDE_WINDOW_MS = 15 * 60 * 1000L
        /** Minimum forecast/tide cover we consider seaworthy before leaving the dock. */
        private const val MIN_OFFLINE_HOURS = 24.0

        /** Zoom levels per mouse-wheel notch. */
        private const val ZOOM_PER_WHEEL_NOTCH = 0.5

        /** Idle time before the bottom control bar fades away. */
        /** Idle timeout for ALL auto-hiding chrome (toolbar + scale bars), every screen. */
        private const val CHROME_IDLE_MS = 4000L

        /** SOG (kn) above which the trip clock counts the boat as underway (≈ engine running). */
        private const val TRIP_MOVING_KN = 0.5
        /** Safety: above this SOG (kn) the ⚓ button is disabled — you can't drop anchor while moving. */
        private const val ANCHOR_MAX_SOG_KN = 1.5
        /** Chart range (nm across) the Chart+Nav second (course-up) map frames. */
        private const val NAV2_RANGE_NM = 0.75
        /** After panning the Nav map, how long the follow camera holds off before re-centering (ms). */
        private const val MAP_RECENTER_MS = 10_000L
        /** Scale-bar bottom margin (dp) in nav mode: clears the ~84 dp bottom-left SOG panel. */
        private const val NAV_SCALE_BOTTOM_DP = 92
        /** A between-fix hop beyond this (nm) is a teleport, not travelled distance — don't add it. */
        private const val TRIP_TELEPORT_NM = 1.0

        /** GPS slop added to the anchor swing radius (fix scatter + a little breathing room). */
        private const val GPS_MARGIN_M = 15.0

        /** Minimum move between fixes (m) to recompute COG; below this we hold the last course
         *  so GPS scatter at rest doesn't spin the heading. */
        private const val COG_MIN_MOVE_M = 2.0
        /** A jump larger than this between fixes (m) is a teleport, not travel: reset COG + trail
         *  (boat placed elsewhere, a preset change, or a GPS glitch) rather than drawing across it. */
        private const val TELEPORT_M = 500.0

        // --- GPS glitch filter (onLocationChanged) ---
        /** A fix must jump at least this far (m) AND imply more than [GLITCH_MAX_KN] to be a glitch.
         *  Below this it's normal travel/scatter; a large jump over a long GPS gap implies a LOW speed,
         *  so it passes — only far *and* fast fixes are dropped. */
        private const val GLITCH_MIN_M = 100.0
        /** Implied speed (knots) above which a jump is physically impossible for this boat → glitch.
         *  Well above hull/current speed; normal-motion GPS scatter stays under [GLITCH_MIN_M] anyway. */
        private const val GLITCH_MAX_KN = 15.0
        /** Freeze backstop: only after dropping fixes continuously for this long do we accept the new
         *  position (our lock must be stale). Long enough that no transient glitch survives it. */
        private const val GLITCH_RESYNC_MS = 30_000L
        /** Ignore any fix older than this (ms). Catches stale getLastKnownLocation / replayed fixes on
         *  resume that would otherwise teleport the boat; a live GPS stream is always well under it. */
        private const val MAX_FIX_AGE_MS = 20_000L
        /** Ignore fixes less precise than this (m) — coarse network/cell fixes and unsettled GPS
         *  cold-starts (common on wake) that would drop the boat far from its true spot. */
        private const val MAX_ACCURACY_M = 50.0f
        /** SharedPreferences file for state that must survive a quit/kill (anchor watch, last pos). */
        private const val STATE_PREFS = "mynavvy_state"

        /** Camera tilt (degrees) for the course-up navigation perspective. */
        private const val NAV_TILT = 60.0   // MapLibre max camera pitch (Simrad-style 3D nav view)
        /** Distance (nm) within which the active nav waypoint is treated as reached. */
        private const val WP_ARRIVE_NM = 0.05

        /** Every band's depth-area layer, dredged first (a dredged channel overrides the area). */
        private val DEPTH_LAYERS: Array<String> =
            (1..6).flatMap { listOf("DRGARE-b$it", "DEPARE-b$it") }.toTypedArray()
        private val SAN_DIEGO = LatLng(32.69, -117.20)
        private const val TILE_PORT = 8123
        private const val BASEMAP_PORT = 8124
        // Remote OSM land-basemap tile server (Unraid `martin` behind NPM — TODO: stand it up). Misses
        // outside the local seed are fetched from here and cached for offline. XYZ, gzipped MVT.
        // Until the server exists, read-through just fails softly (→ 204) and the seed still works.
        private const val BASEMAP_REMOTE = "https://basemap.darkclad.org/tiles/{z}/{x}/{y}.pbf"
        private const val PREFETCH_MAX_TILES = 3000
        private const val REQ_LOCATION = 1001
        private const val REQ_NOTIF = 1002
    }
}
