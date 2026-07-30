package com.dvladi.mynavvy

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import android.net.Network
import com.dvladi.mynavvy.nmea.ConnectionState
import com.dvladi.mynavvy.nmea.GoFreeDiscovery
import com.dvladi.mynavvy.nmea.NavUpdate
import com.dvladi.mynavvy.nmea.NmeaClient
import com.dvladi.mynavvy.nmea.NmeaMode
import com.dvladi.mynavvy.nmea.NmeaParser
import com.dvladi.mynavvy.nmea.NmeaPrefs
import com.dvladi.mynavvy.nmea.NmeaSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

/**
 * The always-on GPS / anchor / track service. Started when MyNavvy launches and kept alive as a
 * foreground service so it keeps working while the app is minimised or the screen is off. It:
 *   1. owns the GPS feed and filters out spikes (stale / imprecise / physically-impossible jumps),
 *   2. computes COG and drives the anchor drag alarm when a watch is set,
 *   3. records the boat track to [TrackStore] (retained ~6 months).
 * The Activity binds to it (see [LocalBinder]) and observes clean fixes via [Fixes]; the service is
 * the single source of truth for position, so UI and background watching never diverge.
 */
class WatchService : Service(), LocationListener {

    /** Observer of clean fixes (the Activity). [teleported] = a confirmed large jump (reset trail). */
    interface Fixes { fun onFix(location: Location, teleported: Boolean, makingWay: Boolean) }

    inner class LocalBinder : Binder() { val service get() = this@WatchService }
    private val binder = LocalBinder()
    private var callback: Fixes? = null
    // Replay the last fix to a newly-bound UI so the boat shows immediately; makingWay=false so this
    // replay never adds a trail point (it's a position seed, not fresh movement).
    fun setCallback(cb: Fixes?) { callback = cb; lastGood?.let { cb?.onFix(it, false, false) } }

    private val main = Handler(Looper.getMainLooper())
    private var lm: LocationManager? = null
    private var track: TrackStore? = null
    private var simReceiver: BroadcastReceiver? = null

    // --- NMEA-over-WiFi source (GO7 GoFree / ESP32 gateway / boatsim bench feed) ---
    // While the stream is live its RMC/GLL fixes are THE position authority: phone GPS
    // and SIM_FIX are ignored, and the source's own COG/SOG are used verbatim (position-
    // derived COG is garbage at anchor). Goes stale -> phone GPS resumes automatically.
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var nmeaReceiver: BroadcastReceiver? = null
    private var nmeaJob: Job? = null
    @Volatile private var nmeaClientState: ConnectionState = ConnectionState.Off
    @Volatile private var nmeaSourceLabel: String? = null   // last connected endpoint, for the chip/dialog
    @Volatile private var nmeaFixMs = 0L        // elapsedRealtime of the last applied NMEA fix
    private var nmeaWasLive = false             // last authority state, for transition logs + GPS resume
    // VTG course cache: fills RMC's SOG/COG gaps (some talkers only put way data in VTG).
    @Volatile private var nmeaVtgCogT: Double? = null
    @Volatile private var nmeaVtgSogKn: Double? = null
    @Volatile private var nmeaVtgMs = 0L
    // Gauge data that doesn't fit a Location (depth / STW / heading / wind), age-gated getters below.
    @Volatile private var nmeaDepthM: Double? = null
    @Volatile private var nmeaDepthMs = 0L
    @Volatile private var nmeaStwKn: Double? = null
    @Volatile private var nmeaStwMs = 0L
    @Volatile private var nmeaHdgT: Double? = null
    @Volatile private var nmeaHdgMs = 0L

    // Filter state
    private var lastGood: Location? = null
    /** The service's freshest accepted fix — the single source of truth for the boat's position.
     *  MainActivity reads this (via the bound instance) so actions like "center on boat", "lower"
     *  and "reset anchor" always use the LIVE position, not the Activity's onFix copy which freezes
     *  while the app is backgrounded / the screen is off. */
    val currentFix: Location? get() = lastGood
    private var lastFixMs = 0L
    private var lastFixLogMs = 0L
    /** elapsedRealtime of the last real GPS-provider fix — while fresh, coarse NETWORK fixes are dropped. */
    private var lastGpsMs = 0L
    private var rejectSinceMs = 0L
    private var cogDeg: Double? = null
    // Top speed (m/s) the boat can make — the glitch gate. Read from the boat profile; refreshed by
    // MainActivity when the user changes it. Volatile: the filter runs on the location callback thread.
    @Volatile private var maxSpeedMs = 20.0 * KN_TO_MS

    // Anchor state
    private var anchorSet = false
    private var anchorLat = 0.0
    private var anchorLon = 0.0
    private var radiusM = 15.24
    private var alarmEnabled = true
    private var lastDistM = 0.0
    private var dragging = false
    /** elapsedRealtime when the boat first went outside the radius (0 = inside); drives DRAG_CONFIRM_MS. */
    private var breachSinceMs = 0L

    private var ringtone: Ringtone? = null
    private var vibrator: Vibrator? = null
    private var alarming = false

    // Held while a watch is armed: keeps the CPU awake so GPS fixes keep arriving (and the drag
    // check keeps running) with the screen off. Without it the device sleeps between fixes and the
    // anchor alarm silently stops watching — the whole point of the watch.
    private var wakeLock: android.os.PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        track = TrackStore(File(getExternalFilesDir(null) ?: filesDir, "tracks"))
        refreshFilterConfig()   // load the boat's top speed for the glitch gate
        ensureChannel()
        startForegroundNotif()
        startLocation()
        // Hold the CPU wake lock for the whole life of the service, not just while anchored. Without
        // it the CPU deep-sleeps between GPS fixes once the screen is off, so TRACK recording (and the
        // live position) freezes even when not anchored — the "GPS stops with the screen off" report.
        // A foreground-service + location-type + battery-opt-exempt app is still frozen by Doze without
        // this. (Samsung's separate "Never sleeping apps" list must also include MyNavvy — device-side.)
        acquireWatchWakeLock()
        if (BuildConfig.SIM_ENABLED) { registerSim(); registerNmeaDebug() }
        applyNmeaPrefs() // Settings → Data source choice survives restarts (production path)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SET_ANCHOR -> {
                anchorSet = true
                anchorLat = intent.getDoubleExtra(EX_LAT, anchorLat)
                anchorLon = intent.getDoubleExtra(EX_LON, anchorLon)
                radiusM = intent.getDoubleExtra(EX_RADIUS, radiusM)
                alarmEnabled = intent.getBooleanExtra(EX_ALARM, alarmEnabled)
                acquireWatchWakeLock()                 // keep GPS + drag check alive with the screen off
                scheduleWatchdog()                     // + a Doze-proof exact alarm re-checks position
                lastGood?.let { evaluateAnchor(it) } ?: updateNotif()
            }
            ACTION_UPDATE_ANCHOR -> {
                if (intent.hasExtra(EX_RADIUS)) radiusM = intent.getDoubleExtra(EX_RADIUS, radiusM)
                if (intent.hasExtra(EX_ALARM)) alarmEnabled = intent.getBooleanExtra(EX_ALARM, alarmEnabled)
                lastGood?.let { evaluateAnchor(it) } ?: updateNotif()
            }
            ACTION_CLEAR_ANCHOR -> {
                // Raising the anchor stops the drag watchdog/alarm, but NOT the wake lock: track
                // recording keeps going and must survive the screen turning off just like before.
                anchorSet = false; dragging = false; stopAlarm(); cancelWatchdog(); updateNotif()
            }
            ACTION_RESYNC -> resyncFilter()
            ACTION_WATCHDOG -> doWatchdogCheck()
        }
        startForegroundNotif() // re-satisfy the FGS contract on every start
        return START_STICKY
    }

    /** Re-read the boat's configurable top speed into the glitch gate (MainActivity calls this when
     *  the profile changes). Clamped so a sane value always drives the filter. */
    fun refreshFilterConfig() {
        val kn = BoatProfile.load(this).maxSpeedKn.coerceIn(1.0, 200.0)
        maxSpeedMs = kn * KN_TO_MS
        Log.i(TAG, "glitch gate: max ${kn.toInt()} kn (${"%.1f".format(maxSpeedMs)} m/s)")
    }

    private fun startLocation() {
        if (lm != null) return
        // In sim mode ON AN EMULATOR the injected SIM_FIX stream is the SOLE position source —
        // never register the emulator's fake GPS, or it would compete with the sim. On real
        // hardware we ALWAYS use the real GPS (even in a sim-enabled build), so a device in the
        // field can never lose its fix just because the simulator was left on.
        if (BuildConfig.SIM_ENABLED && isEmulator()) {
            Log.i(TAG, "SIM MODE (emulator): real GPS disabled — using SIM_FIX only")
            return
        }
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) return
        val manager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        lm = manager
        try {
            val providers = manager.getProviders(true)
            if (LocationManager.GPS_PROVIDER in providers) {
                // Marine use = the real GPS chip ONLY. Registering every provider (network/passive/
                // fused) and merging them is what made the boat "jump": the network/passive fixes are
                // cell-/wifi-derived (off by 100s of m) or belong to other apps, and our per-second
                // dedupe would keep whichever fired first. NETWORK is kept solely as a cold-start
                // fallback so the boat shows instantly on launch; handleFix ignores it once GPS locks.
                @Suppress("MissingPermission")
                manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this)
                if (LocationManager.NETWORK_PROVIDER in providers) {
                    @Suppress("MissingPermission")
                    manager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1000L, 0f, this)
                }
                Log.i(TAG, "location: GPS provider (network = cold-start fallback only)")
            } else {
                // No GPS chip (unusual tablet): fall back to whatever position source exists.
                providers.forEach { p -> @Suppress("MissingPermission") manager.requestLocationUpdates(p, 1000L, 0f, this) }
                Log.i(TAG, "location: no GPS provider — using ${providers.joinToString()}")
            }
        } catch (t: Throwable) { Log.w(TAG, "requestLocationUpdates failed: ${t.message}") }
    }


    // --- fix pipeline --------------------------------------------------------

    override fun onLocationChanged(location: Location) = handleFix(location, trusted = false)

    /** Feed a fix through the filter → COG → track → anchor → observer pipeline. [trusted] fixes
     *  (the NMEA source, the debug simulator) skip the spike/precision gates since they're
     *  deliberate input. [trustedCog] = the fix carries the source's real COG (NMEA RMC/VTG) —
     *  use it verbatim instead of deriving COG from successive positions (derived COG is
     *  garbage at anchor; the GO7's is not). */
    private fun handleFix(location: Location, trusted: Boolean, trustedCog: Boolean = false) {
        // Source priority: while NMEA fixes are fresh they are THE position. A straggling
        // phone-GPS fix (listener not yet removed) or a competing SIM_FIX must not fight it.
        if (location.provider != NMEA_PROVIDER && nmeaLive()) return
        val nowMs = SystemClock.elapsedRealtime()
        // Real GPS wins: track when we last had a GPS fix, and once it's fresh, drop NETWORK/passive
        // fixes entirely (they'd only drag the smooth GPS track sideways). NETWORK is thus used only
        // to seed a first position before the GPS chip has locked.
        if (!trusted && location.provider == LocationManager.GPS_PROVIDER) lastGpsMs = nowMs
        if (!trusted && location.provider != LocationManager.GPS_PROVIDER &&
            location.provider != NMEA_PROVIDER && lastGpsMs != 0L && nowMs - lastGpsMs < GPS_PREFER_MS) return
        if (!trusted) {
            // Dedupe the near-simultaneous duplicates from ALL providers (gps/network/fused/passive
            // each fire ~1 Hz, staggered) into ~1/sec — otherwise the whole pipeline, incl. the
            // notification, runs ~4×/sec and Android rate-limits/sheds the notification updates.
            if (lastGood != null && nowMs - lastFixMs < MIN_FIX_INTERVAL_MS) return
            val ageMs = location.ageMs(nowMs)
            if (ageMs > MAX_FIX_AGE_MS) { Log.w(TAG, "stale fix ignored: ${ageMs / 1000}s"); return }
            if (location.hasAccuracy() && location.accuracy > MAX_ACCURACY_M) {
                Log.w(TAG, "imprecise fix ignored: ±${location.accuracy.toInt()} m"); return
            }
        }
        val prev = lastGood
        if (!trusted && prev != null) {
            val jumpM = GeoUtils.distanceM(prev.latitude, prev.longitude, location.latitude, location.longitude)
            val dtSec = (nowMs - lastFixMs).coerceAtLeast(1L) / 1000.0
            val impliedMs = jumpM / dtSec
            // A boat can't teleport: any jump implying more than its top speed is a GPS spike. Only a
            // tiny floor (below GPS accuracy) passes unchecked, so stationary jitter doesn't freeze the
            // fix. A run of rejects longer than the resync window is accepted once, so a genuinely
            // relocated boat (GPS was off, real jump) recovers instead of staying stuck forever.
            if (jumpM > GLITCH_MIN_M && impliedMs > maxSpeedMs) {
                if (rejectSinceMs == 0L) rejectSinceMs = nowMs
                if (nowMs - rejectSinceMs < GLITCH_RESYNC_MS) {
                    Log.w(TAG, "glitch ignored: ${jumpM.toInt()} m @ ${"%.1f".format(impliedMs)} m/s (max ${"%.1f".format(maxSpeedMs)})"); return
                }
                Log.w(TAG, "resync after ${(nowMs - rejectSinceMs) / 1000}s frozen")
            }
        }
        rejectSinceMs = 0L
        lastFixMs = nowMs

        // COG: taken verbatim from a source that measures it ([trustedCog]), else derived from
        // successive positions — held when nearly still, reset on a teleport.
        var teleported = false
        if (prev != null) {
            val movedM = GeoUtils.distanceM(prev.latitude, prev.longitude, location.latitude, location.longitude)
            when {
                movedM > TELEPORT_M -> { cogDeg = null; teleported = true }
                movedM >= COG_MIN_MOVE_M && !trustedCog -> cogDeg = GeoUtils.bearingDeg(
                    org.maplibre.android.geometry.LatLng(prev.latitude, prev.longitude),
                    org.maplibre.android.geometry.LatLng(location.latitude, location.longitude))
            }
        }
        if (trustedCog && location.hasBearing()) cogDeg = location.bearing.toDouble()
        cogDeg?.let { location.bearing = it.toFloat() } ?: location.removeBearing()
        lastGood = location

        // Throttled GPS heartbeat so a diagnostics log can prove fixes keep arriving with the screen
        // off (or reveal exactly when they stop). One line per ~30 s — no spam.
        val nowRt = SystemClock.elapsedRealtime()
        if (nowRt - lastFixLogMs >= FIX_LOG_INTERVAL_MS) {
            lastFixLogMs = nowRt
            val screenOn = (getSystemService(Context.POWER_SERVICE) as android.os.PowerManager).isInteractive
            Log.i(TAG, "GPS fix: ${location.provider} acc=%.0fm sog=%.1fkn screen=%s wake=%s"
                .format(Locale.US, if (location.hasAccuracy()) location.accuracy else -1f,
                    location.speed / 0.514444f, if (screenOn) "on" else "OFF", wakeLock?.isHeld == true))
        }

        // Only lay down track/trail while actually MAKING WAY. A stationary boat's GPS drift — or,
        // indoors with no GPS lock, the coarse NETWORK-provider jitter (~15 m, SOG 0) — otherwise
        // scribbles a fake wandering track across the chart even when the boat hasn't moved an inch.
        // The boat marker still follows every fix; only the recorded track/trail is gated.
        val makingWay = location.provider != LocationManager.NETWORK_PROVIDER &&
            (!location.hasSpeed() || location.speed >= STATIONARY_MS)
        if (makingWay) track?.add(System.currentTimeMillis(), location.latitude, location.longitude,
            (location.speed / 0.514444f).toDouble(), cogDeg)

        if (anchorSet) evaluateAnchor(location) else updateNotif()
        val tp = teleported
        main.post { callback?.onFix(location, tp, makingWay) }
    }

    // --- anchor watch --------------------------------------------------------

    private fun evaluateAnchor(loc: Location) {
        lastDistM = GeoUtils.distanceM(anchorLat, anchorLon, loc.latitude, loc.longitude)
        val wasDragging = dragging
        val now = SystemClock.elapsedRealtime()
        // GPS-uncertainty margin: a fix that is (say) 20 m off can read 20 m outside a tight radius
        // even on a boat that hasn't budged. Only treat the boat as OUTSIDE when it's beyond the radius
        // even after discounting the fix's own accuracy — so ordinary GPS wander at anchor can't raise
        // a false alarm, while a genuine drag (a sustained, growing offset) still trips it.
        val acc = if (loc.hasAccuracy()) loc.accuracy.toDouble() else 0.0
        val outside = alarmEnabled && (lastDistM - acc) > radiusM
        // Hysteresis + confirm window so GPS jitter around the radius edge can't make `dragging`
        // (and therefore the alarm + the notification) flap several times a second. Raise the alarm
        // only after the boat has been OUTSIDE the radius continuously for DRAG_CONFIRM_MS; clear it
        // only once it comes back well INSIDE (radius * DRAG_CLEAR_FRACTION). One transition, not a storm.
        if (!dragging) {
            if (outside) {
                if (breachSinceMs == 0L) breachSinceMs = now
                if (now - breachSinceMs >= DRAG_CONFIRM_MS) dragging = true
            } else {
                breachSinceMs = 0L
            }
        } else {
            if (!alarmEnabled || lastDistM < radiusM * DRAG_CLEAR_FRACTION) {
                dragging = false
                breachSinceMs = 0L
            }
        }
        if (dragging) startAlarm() else stopAlarm()
        if (dragging != wasDragging)
            Log.i(TAG, "anchor drag ${if (dragging) "RAISED" else "cleared"}: dist=%.1fm radius=%.1fm acc=%.1fm anchor=%.6f,%.6f fix=%.6f,%.6f"
                .format(Locale.US, lastDistM, radiusM, acc, anchorLat, anchorLon, loc.latitude, loc.longitude))
        updateNotif(force = dragging != wasDragging)   // force on a confirmed drag-state change; else throttle
    }

    // --- alarm ---------------------------------------------------------------

    private fun startAlarm() {
        if (alarming) return
        alarming = true
        try {
            val uri = RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_NOTIFICATION)
            if (uri != null) ringtone = RingtoneManager.getRingtone(this, uri)?.apply {
                audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
                if (Build.VERSION.SDK_INT >= 28) isLooping = true
                play()
            }
        } catch (t: Throwable) { Log.w(TAG, "alarm sound failed: ${t.message}") }
        try {
            val v = vibrator ?: (if (Build.VERSION.SDK_INT >= 31)
                (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            else @Suppress("DEPRECATION") (getSystemService(Context.VIBRATOR_SERVICE) as Vibrator)).also { vibrator = it }
            val pattern = longArrayOf(0, 600, 500)
            if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createWaveform(pattern, 0))
            else @Suppress("DEPRECATION") v.vibrate(pattern, 0)
        } catch (_: Throwable) {}
    }

    private fun stopAlarm() {
        if (!alarming) return
        alarming = false
        try { ringtone?.stop() } catch (_: Throwable) {}
        ringtone = null
        try { vibrator?.cancel() } catch (_: Throwable) {}
    }

    /** Drop the spike-filter's memory of where the boat was, so the very next fix is accepted as
     *  ground truth (no boat-speed jump check against it). Called when the app is (re)opened: if the
     *  service survived in the background (screen-off / anchor) while the boat was carried to a new
     *  spot, the old position would otherwise make the new one look like an impossible jump and freeze
     *  the boat at the old location. The accuracy/age gates still apply — we trust the position, not junk.
     *  Anchor state is untouched (a raised/dropped anchor keeps its own point). */
    private fun resyncFilter() {
        lastGood = null
        cogDeg = null
        lastFixMs = 0L
        rejectSinceMs = 0L
        Log.i(TAG, "position filter reset (app opened) — next fix trusted as ground truth")
    }

    // --- wake lock (screen-off GPS) -----------------------------------------

    private fun acquireWatchWakeLock() {
        if (wakeLock?.isHeld == true) return
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            wakeLock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "MyNavvy:watch").apply {
                setReferenceCounted(false)
                acquire()
            }
            Log.i(TAG, "CPU wake lock held (GPS/track/anchor stay alive with the screen off)")
        } catch (t: Throwable) { Log.w(TAG, "wake lock acquire failed: ${t.message}") }
    }

    private fun releaseWatchWakeLock() {
        try { wakeLock?.let { if (it.isHeld) it.release() } } catch (_: Throwable) {}
        wakeLock = null
    }

    // --- Doze-proof watchdog -------------------------------------------------
    // The continuous GPS listener + wake lock keep fixes flowing when the CPU is awake, but the OS
    // can still freeze the process between fixes when idle. An exact `setExactAndAllowWhileIdle`
    // alarm fires EVEN in Doze — it wakes the CPU on a fixed cadence so a fresh fix is delivered and
    // the drag check runs, then reschedules itself. (Whitelisted from battery optimisation, so the
    // interval isn't rate-limited.) Note: this cannot save a process an aggressive OEM has fully
    // force-stopped — that needs the device's "never sleeping apps" allow-list.

    private var watchdogScheduled = false

    private fun watchdogIntent(): PendingIntent {
        val i = Intent(this, WatchService::class.java).setAction(ACTION_WATCHDOG)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return if (Build.VERSION.SDK_INT >= 26) PendingIntent.getForegroundService(this, 7, i, flags)
        else PendingIntent.getService(this, 7, i, flags)
    }

    private fun scheduleWatchdog() {
        val am = getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val next = SystemClock.elapsedRealtime() + WATCHDOG_INTERVAL_MS
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, next, watchdogIntent())
            watchdogScheduled = true
        } catch (t: Throwable) { Log.w(TAG, "watchdog schedule failed: ${t.message}") }
    }

    private fun cancelWatchdog() {
        if (!watchdogScheduled) return
        try { (getSystemService(Context.ALARM_SERVICE) as? AlarmManager)?.cancel(watchdogIntent()) } catch (_: Throwable) {}
        watchdogScheduled = false
    }

    private fun doWatchdogCheck() {
        val now = SystemClock.elapsedRealtime()
        val ageMs = if (lastFixMs == 0L) -1L else now - lastFixMs
        Log.i(TAG, "watchdog: anchor=$anchorSet lastFix=${if (ageMs < 0) "never" else "${ageMs / 1000}s"} " +
            "wakeLock=${wakeLock?.isHeld == true} dragging=$dragging dist=${lastDistM.toInt()}m")
        if (lm == null) startLocation()                 // re-register GPS if it was dropped
        // Continuous updates gone quiet → actively pull the freshest fix and re-run the drag check.
        if (anchorSet && (ageMs < 0 || ageMs > WATCHDOG_INTERVAL_MS - 5_000)) pollLastKnownAndEvaluate()
        updateNotif()
        if (anchorSet) scheduleWatchdog()               // keep the heartbeat going while armed
    }

    /** Grab the freshest last-known fix across providers and, if it's newer than what we have, feed
     *  it through the pipeline (trusted → drag check runs even if the fix is a little old). */
    private fun pollLastKnownAndEvaluate() {
        val manager = lm ?: return
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) return
        var best: Location? = null
        try {
            for (p in manager.getProviders(true)) {
                @Suppress("MissingPermission") val l = manager.getLastKnownLocation(p) ?: continue
                if (best == null || l.elapsedRealtimeNanos > best!!.elapsedRealtimeNanos) best = l
            }
        } catch (_: Throwable) {}
        val b = best ?: return
        if (b.elapsedRealtimeNanos != lastGood?.elapsedRealtimeNanos) handleFix(b, trusted = true)
        else lastGood?.let { evaluateAnchor(it) }       // no newer fix — re-run against the current one
    }

    // --- notification --------------------------------------------------------

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // The old channel was IMPORTANCE_HIGH, so the always-on "recording · GPS active" notification
        // chimed and popped a heads-up banner on every launch. Recording is a constant, not an event —
        // it shouldn't alert. Drop that channel for a silent low-importance one. A channel's importance
        // can't be lowered in place, so this uses a new id (the old one is deleted). The anchor drag
        // alarm is unaffected: it plays its own looping alarm ringtone + vibration in startAlarm().
        nm.deleteNotificationChannel(OLD_CHANNEL)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Anchor & GPS watch", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Silent background GPS + track recording. The anchor drag alarm sounds on its own."
                    setShowBadge(false)
                }
            )
        }
    }

    private fun startForegroundNotif() {
        try {
            val n = buildNotification()
            if (Build.VERSION.SDK_INT >= 29)
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            else startForeground(NOTIF_ID, n)
        } catch (t: Throwable) { Log.w(TAG, "startForeground failed: ${t.message}") }
    }

    private var lastNotifMs = 0L

    /** Re-issue the ongoing notification, but at most once per [NOTIF_MIN_INTERVAL_MS] unless [force]
     *  (a drag-state change / anchor set-clear). Without this the per-fix updates hit ~4×/sec, which
     *  Android rate-limits + sheds — and Samsung's power manager reaps a service that does that. */
    private fun updateNotif(force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        // Even a forced update keeps a small floor: a legitimate drag alarm still fires its SOUND
        // immediately (startAlarm), but the notification re-post can never exceed ~0.5/sec, so no
        // conceivable state flap can hit Android's rate-limit shed or trip an OEM power manager.
        val minGap = if (force) NOTIF_FORCE_MIN_MS else NOTIF_MIN_INTERVAL_MS
        if (now - lastNotifMs < minGap) return
        lastNotifMs = now
        try { (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, buildNotification()) }
        catch (_: Throwable) {}
    }

    private fun buildNotification(): android.app.Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP },
            PendingIntent.FLAG_IMMUTABLE
        )
        val title: String
        val body: String
        when {
            dragging -> {
                title = "⚠ ANCHOR DRAGGING"
                body = String.format(Locale.US, "%.0f ft from drop · radius %.0f ft",
                    lastDistM * BoatProfile.M_TO_FT, radiusM * BoatProfile.M_TO_FT)
            }
            anchorSet -> {
                title = "Anchor watch active"
                // Show the GPS fix age so a glance at the notification proves it's still watching
                // (a frozen age = the OS has stopped delivering fixes → check "never sleeping apps").
                val age = if (lastFixMs == 0L) null else (SystemClock.elapsedRealtime() - lastFixMs) / 1000
                val gps = when {
                    age == null -> "waiting for GPS"
                    age < 3 -> "GPS live"
                    else -> "GPS ${age}s ago"
                }
                body = if (lastGood != null) String.format(Locale.US, "%.0f ft from drop · radius %.0f ft · %s",
                    lastDistM * BoatProfile.M_TO_FT, radiusM * BoatProfile.M_TO_FT, gps) else "Waiting for GPS fix…"
            }
            else -> { title = "MyNavvy running"; body = "Recording track · GPS active" }
        }
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title).setContentText(body)
            .setOngoing(true).setOnlyAlertOnce(!dragging)
            .setCategory(if (dragging) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_STATUS)
            .setPriority(if (dragging) NotificationCompat.PRIORITY_MAX else NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pi).build()
    }

    // --- debug simulator input ----------------------------------------------

    private fun registerSim() {
        val r = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, i: Intent?) {
                i ?: return
                val lat = i.getDoubleExtra("lat", Double.NaN)
                val lon = i.getDoubleExtra("lon", Double.NaN)
                if (lat.isNaN() || lon.isNaN()) return
                val loc = Location("sim").apply {
                    latitude = lat; longitude = lon
                    speed = i.getFloatExtra("sog", 0f) / 1.94384f
                    bearing = ((i.getFloatExtra("cog", 0f) % 360f) + 360f) % 360f
                    accuracy = 5f; time = System.currentTimeMillis()
                    elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                }
                handleFix(loc, trusted = true)
            }
        }
        simReceiver = r
        ContextCompat.registerReceiver(this, r, IntentFilter("com.dvladi.mynavvy.SIM_FIX"), ContextCompat.RECEIVER_EXPORTED)
    }

    // --- NMEA source (live instrument data over WiFi) -------------------------

    /**
     * Debug on/off switch for the NMEA source (like SIM_FIX; the Settings UI is Phase 3):
     *
     *   adb shell am broadcast -a com.dvladi.mynavvy.NMEA_DEBUG -p com.dvladi.mynavvy \
     *       --es host 10.0.2.2 --ei port 10110      # start (defaults shown)
     *   adb shell am broadcast -a com.dvladi.mynavvy.NMEA_DEBUG -p com.dvladi.mynavvy \
     *       --ez stop true                            # stop
     *
     * Watch with:  adb logcat -s NmeaRaw NmeaClient WatchService
     * (NmeaRaw logs every sentence — that's also how GO7 parser fixtures get captured.)
     */
    private fun registerNmeaDebug() {
        val r = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, i: Intent?) {
                i ?: return
                if (i.getBooleanExtra("stop", false)) stopNmea()
                else startNmea(NmeaSource(
                    host = i.getStringExtra("host") ?: "10.0.2.2",
                    port = i.getIntExtra("port", 10110)))
            }
        }
        nmeaReceiver = r
        ContextCompat.registerReceiver(this, r, IntentFilter("com.dvladi.mynavvy.NMEA_DEBUG"), ContextCompat.RECEIVER_EXPORTED)
    }

    /** (Re)start the NMEA pipeline per the persisted Settings → Data source choice.
     *  Called on service start and by MainActivity after the dialog saves. */
    fun applyNmeaPrefs() {
        val p = NmeaPrefs.load(this)
        when (p.mode) {
            NmeaMode.OFF -> stopNmea()
            NmeaMode.MANUAL -> startNmea(p.manualSource())
            NmeaMode.AUTO -> startNmeaResolved(label = "auto") { network ->
                GoFreeDiscovery.discover(this, network)
                    ?: GoFreeDiscovery.probeGateway(this, network)
            }
        }
    }

    /** Connect to a fixed [source] and run the NMEA pipeline until [stopNmea]. */
    fun startNmea(source: NmeaSource) = startNmeaResolved(label = "$source") { source }

    /** Run the NMEA pipeline with an endpoint [resolve]r (re-evaluated every connect cycle —
     *  that's what makes auto mode self-heal). Restarts if already running. */
    private fun startNmeaResolved(label: String, resolve: suspend (Network?) -> NmeaSource?) {
        stopNmea()
        Log.i(TAG, "NMEA source starting ($label)")
        nmeaJob = ioScope.launch {
            val client = NmeaClient(this@WatchService, resolve)
            launch {
                client.state.collect { st ->
                    nmeaClientState = st
                    if (st is ConnectionState.Connected) nmeaSourceLabel = st.source.toString()
                    Log.i(TAG, "NMEA $st")
                }
            }
            client.sentences.collect { s ->
                Log.i(NMEA_RAW_TAG, s)
                NmeaParser.parse(s)?.let { onNmeaUpdate(it) }
            }
        }
    }

    /** Stop the NMEA source; position authority returns to the phone GPS immediately. */
    fun stopNmea() {
        nmeaJob?.cancel()
        nmeaJob = null
        nmeaFixMs = 0L
        nmeaClientState = ConnectionState.Off
        nmeaSourceLabel = null
        main.post { onNmeaAuthorityChanged() }
    }

    // --- status chip ----------------------------------------------------------

    enum class NmeaChip { OFF, LIVE, STALE }

    /** Chip state + endpoint label ("192.168.0.1:10110"), for the chrome chip. The chip shows
     *  ONLY while actually connected to an NMEA stream (TCP up) AND we've received position data —
     *  searching / reconnecting / off / not-yet-any-fix all show nothing, so selecting a source
     *  with no device present (or losing the link) isn't chrome noise. LIVE = connected + fresh
     *  fixes; STALE = still connected but the fixes have aged out (position is now on phone GPS). */
    fun nmeaChipState(): Pair<NmeaChip, String?> {
        val connected = nmeaJob?.isActive == true && nmeaClientState is ConnectionState.Connected
        return when {
            connected && nmeaLive() -> NmeaChip.LIVE to nmeaSourceLabel
            connected && nmeaFixMs != 0L -> NmeaChip.STALE to nmeaSourceLabel
            else -> NmeaChip.OFF to null
        }
    }

    /** Route one parsed sentence: fixes ride [handleFix], the rest feed the gauge getters. */
    private fun onNmeaUpdate(u: NavUpdate) {
        val now = SystemClock.elapsedRealtime()
        when (u) {
            is NavUpdate.Fix -> {
                // Fall back to fresh VTG way data when this fix carries none (GLL, sparse RMC).
                val vtgFresh = now - nmeaVtgMs < NMEA_STALE_MS
                val sog = u.sogKn ?: nmeaVtgSogKn.takeIf { vtgFresh }
                val cog = u.cogTrue ?: nmeaVtgCogT.takeIf { vtgFresh }
                val loc = Location(NMEA_PROVIDER).apply {
                    latitude = u.lat; longitude = u.lon
                    sog?.let { speed = (it * 0.514444).toFloat() }
                    cog?.let { bearing = ((it % 360.0 + 360.0) % 360.0).toFloat() }
                    accuracy = 5f
                    time = u.utcMs ?: System.currentTimeMillis()
                    elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                }
                nmeaFixMs = now
                main.post {
                    onNmeaAuthorityChanged()
                    // Re-arm the staleness watchdog: no NMEA fix for NMEA_STALE_MS -> GPS resumes.
                    main.removeCallbacks(nmeaStaleCheck)
                    main.postDelayed(nmeaStaleCheck, NMEA_STALE_MS)
                }
                handleFix(loc, trusted = true, trustedCog = cog != null)
            }
            is NavUpdate.Course -> { nmeaVtgCogT = u.cogTrue; nmeaVtgSogKn = u.sogKn; nmeaVtgMs = now }
            is NavUpdate.Depth -> { nmeaDepthM = u.meters; nmeaDepthMs = now }
            is NavUpdate.WaterSpeed -> {
                u.stwKn?.let { nmeaStwKn = it; nmeaStwMs = now }
                u.headingTrue?.let { nmeaHdgT = it; nmeaHdgMs = now }
            }
            is NavUpdate.Wind -> { /* source B (ESP32) — no consumer until Phase 4 */ }
        }
    }

    /** True while NMEA position fixes are fresh — the phone GPS and SIM_FIX stand down. */
    fun nmeaLive(): Boolean = nmeaFixMs != 0L &&
        SystemClock.elapsedRealtime() - nmeaFixMs < NMEA_STALE_MS

    /** Live measured depth below transducer (m), null when absent/stale — UI shows chart depth then. */
    fun nmeaDepthM(): Double? = nmeaDepthM?.takeIf {
        SystemClock.elapsedRealtime() - nmeaDepthMs < NMEA_STALE_MS }

    /** Live speed through water (kn), null when absent/stale. */
    fun nmeaStwKn(): Double? = nmeaStwKn?.takeIf {
        SystemClock.elapsedRealtime() - nmeaStwMs < NMEA_STALE_MS }

    /** Live true heading (deg), null when absent/stale. */
    fun nmeaHeadingT(): Double? = nmeaHdgT?.takeIf {
        SystemClock.elapsedRealtime() - nmeaHdgMs < NMEA_STALE_MS }

    private val nmeaStaleCheck = Runnable { onNmeaAuthorityChanged() }

    /** Suppress the phone GPS while NMEA holds authority; resume it the moment that lapses
     *  (stream stale or stopped) so the boat never loses a position. Main-thread only. */
    private fun onNmeaAuthorityChanged() {
        val live = nmeaLive()
        if (live == nmeaWasLive) return
        nmeaWasLive = live
        if (live) {
            Log.i(TAG, "NMEA live: it is now the position authority (phone GPS suppressed)")
            try { lm?.removeUpdates(this) } catch (_: Throwable) {}
            lm = null
        } else {
            Log.w(TAG, "NMEA stale/stopped: falling back to phone GPS")
            startLocation()
        }
    }

    // --- helpers -------------------------------------------------------------



    override fun onDestroy() {
        stopAlarm()
        releaseWatchWakeLock()
        cancelWatchdog()
        try { lm?.removeUpdates(this) } catch (_: Throwable) {}
        simReceiver?.let { runCatching { unregisterReceiver(it) } }
        nmeaReceiver?.let { runCatching { unregisterReceiver(it) } }
        main.removeCallbacks(nmeaStaleCheck)
        ioScope.cancel()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java") override fun onProviderDisabled(provider: String) {}
    @Deprecated("Deprecated in Java") override fun onProviderEnabled(provider: String) {}
    @Deprecated("Deprecated in Java") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

    companion object {
        private const val TAG = "WatchService"
        private const val NMEA_RAW_TAG = "NmeaRaw"
        /** Location.provider of NMEA-sourced fixes. */
        const val NMEA_PROVIDER = "nmea"
        /** No NMEA fix for this long → the source is stale: phone GPS resumes, gauge data hides. */
        private const val NMEA_STALE_MS = 10_000L
        private const val CHANNEL = "watch_status"       // silent (IMPORTANCE_LOW)
        private const val OLD_CHANNEL = "watch_service"   // deleted on start: was IMPORTANCE_HIGH, chimed
        private const val NOTIF_ID = 4711

        const val ACTION_SET_ANCHOR = "com.dvladi.mynavvy.watch.SET_ANCHOR"
        const val ACTION_UPDATE_ANCHOR = "com.dvladi.mynavvy.watch.UPDATE_ANCHOR"
        const val ACTION_CLEAR_ANCHOR = "com.dvladi.mynavvy.watch.CLEAR_ANCHOR"
        /** App opened/foregrounded: drop the stale filter reference so the next fix is trusted verbatim. */
        const val ACTION_RESYNC = "com.dvladi.mynavvy.watch.RESYNC"
        private const val ACTION_WATCHDOG = "com.dvladi.mynavvy.watch.WATCHDOG"
        /** Doze-proof re-check cadence for the anchor watch (seconds). */
        private const val WATCHDOG_INTERVAL_MS = 30_000L
        const val EX_LAT = "lat"; const val EX_LON = "lon"; const val EX_RADIUS = "radiusM"; const val EX_ALARM = "alarm"

        // Filter tuning.
        private const val KN_TO_MS = 0.514444
        /** Collapse the multi-provider (gps/network/fused/passive) fix flood to ~this cadence. */
        private const val MIN_FIX_INTERVAL_MS = 900L
        /** GPS heartbeat log cadence — proves (in a diagnostics log) fixes keep arriving screen-off. */
        private const val FIX_LOG_INTERVAL_MS = 30_000L
        /** Below this speed (~0.5 kn) the boat isn't making way — don't record track/trail (kills the
         *  stationary GPS-drift / network-jitter "fake track"). */
        private const val STATIONARY_MS = 0.26
        /** While a GPS fix is this fresh, coarse NETWORK-provider fixes are ignored (GPS-only tracking). */
        private const val GPS_PREFER_MS = 15_000L
        /** Re-issue the ongoing notification at most this often (grouped) — well under Android's
         *  5/sec rate limit, and gentle enough that OEM power managers don't flag the service. */
        private const val NOTIF_MIN_INTERVAL_MS = 20_000L
        /** Floor for even a FORCED notification re-post, so a drag-state flap can't storm the
         *  NotificationManager (the alarm sound is unaffected — it fires immediately). */
        private const val NOTIF_FORCE_MIN_MS = 2_000L
        /** Boat must be outside the radius continuously this long before the drag alarm raises. */
        private const val DRAG_CONFIRM_MS = 5_000L
        /** Once dragging, only clear when back inside this fraction of the radius (hysteresis). */
        private const val DRAG_CLEAR_FRACTION = 0.8
        private const val MAX_FIX_AGE_MS = 20_000L
        private const val MAX_ACCURACY_M = 50.0f
        // Jumps below this are never speed-checked (sub-GPS-accuracy jitter must not freeze the fix);
        // anything larger must respect the boat's top speed [maxSpeedMs] or it's dropped as a spike.
        private const val GLITCH_MIN_M = 6.0
        private const val GLITCH_RESYNC_MS = 30_000L
        private const val COG_MIN_MOVE_M = 2.0
        private const val TELEPORT_M = 500.0
    }
}
