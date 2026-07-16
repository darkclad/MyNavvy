package com.dvladi.mynavvy

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
    interface Fixes { fun onFix(location: Location, teleported: Boolean) }

    inner class LocalBinder : Binder() { val service get() = this@WatchService }
    private val binder = LocalBinder()
    private var callback: Fixes? = null
    fun setCallback(cb: Fixes?) { callback = cb; lastGood?.let { cb?.onFix(it, false) } }

    private val main = Handler(Looper.getMainLooper())
    private var lm: LocationManager? = null
    private var track: TrackStore? = null
    private var simReceiver: BroadcastReceiver? = null

    // Filter state
    private var lastGood: Location? = null
    private var lastFixMs = 0L
    private var rejectSinceMs = 0L
    private var cogDeg: Double? = null

    // Anchor state
    private var anchorSet = false
    private var anchorLat = 0.0
    private var anchorLon = 0.0
    private var radiusM = 15.24
    private var alarmEnabled = true
    private var lastDistM = 0.0
    private var dragging = false

    private var ringtone: Ringtone? = null
    private var vibrator: Vibrator? = null
    private var alarming = false

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        track = TrackStore(File(getExternalFilesDir(null) ?: filesDir, "tracks"))
        ensureChannel()
        startForegroundNotif()
        startLocation()
        if (BuildConfig.SIM_ENABLED) registerSim()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SET_ANCHOR -> {
                anchorSet = true
                anchorLat = intent.getDoubleExtra(EX_LAT, anchorLat)
                anchorLon = intent.getDoubleExtra(EX_LON, anchorLon)
                radiusM = intent.getDoubleExtra(EX_RADIUS, radiusM)
                alarmEnabled = intent.getBooleanExtra(EX_ALARM, alarmEnabled)
                lastGood?.let { evaluateAnchor(it) } ?: updateNotif()
            }
            ACTION_UPDATE_ANCHOR -> {
                if (intent.hasExtra(EX_RADIUS)) radiusM = intent.getDoubleExtra(EX_RADIUS, radiusM)
                if (intent.hasExtra(EX_ALARM)) alarmEnabled = intent.getBooleanExtra(EX_ALARM, alarmEnabled)
                lastGood?.let { evaluateAnchor(it) } ?: updateNotif()
            }
            ACTION_CLEAR_ANCHOR -> { anchorSet = false; dragging = false; stopAlarm(); updateNotif() }
        }
        startForegroundNotif() // re-satisfy the FGS contract on every start
        return START_STICKY
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
            manager.getProviders(true).forEach { p ->
                @Suppress("MissingPermission") manager.requestLocationUpdates(p, 1000L, 0f, this)
            }
        } catch (t: Throwable) { Log.w(TAG, "requestLocationUpdates failed: ${t.message}") }
    }


    // --- fix pipeline --------------------------------------------------------

    override fun onLocationChanged(location: Location) = handleFix(location, trusted = false)

    /** Feed a fix through the filter → COG → track → anchor → observer pipeline. [trusted] fixes
     *  (the debug simulator) skip the spike/precision gates since they're intentional test input. */
    private fun handleFix(location: Location, trusted: Boolean) {
        val nowMs = SystemClock.elapsedRealtime()
        if (!trusted) {
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
            val impliedKn = (jumpM / 1852.0) / (dtSec / 3600.0)
            if (jumpM > GLITCH_MIN_M && impliedKn > GLITCH_MAX_KN) {
                if (rejectSinceMs == 0L) rejectSinceMs = nowMs
                if (nowMs - rejectSinceMs < GLITCH_RESYNC_MS) {
                    Log.w(TAG, "glitch ignored: ${jumpM.toInt()} m @ ${impliedKn.toInt()} kn"); return
                }
                Log.w(TAG, "resync after ${(nowMs - rejectSinceMs) / 1000}s frozen")
            }
        }
        rejectSinceMs = 0L
        lastFixMs = nowMs

        // COG from successive positions; hold when nearly still, reset on a teleport.
        var teleported = false
        if (prev != null) {
            val movedM = GeoUtils.distanceM(prev.latitude, prev.longitude, location.latitude, location.longitude)
            when {
                movedM > TELEPORT_M -> { cogDeg = null; teleported = true }
                movedM >= COG_MIN_MOVE_M -> cogDeg = GeoUtils.bearingDeg(
                    org.maplibre.android.geometry.LatLng(prev.latitude, prev.longitude),
                    org.maplibre.android.geometry.LatLng(location.latitude, location.longitude))
            }
        }
        cogDeg?.let { location.bearing = it.toFloat() } ?: location.removeBearing()
        lastGood = location

        track?.add(System.currentTimeMillis(), location.latitude, location.longitude,
            (location.speed / 0.514444f).toDouble(), cogDeg)

        if (anchorSet) evaluateAnchor(location) else updateNotif()
        val tp = teleported
        main.post { callback?.onFix(location, tp) }
    }

    // --- anchor watch --------------------------------------------------------

    private fun evaluateAnchor(loc: Location) {
        lastDistM = GeoUtils.distanceM(anchorLat, anchorLon, loc.latitude, loc.longitude)
        dragging = alarmEnabled && lastDistM > radiusM
        if (dragging) startAlarm() else stopAlarm()
        updateNotif()
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

    private fun updateNotif() {
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
                body = if (lastGood != null) String.format(Locale.US, "%.0f ft from drop · radius %.0f ft",
                    lastDistM * BoatProfile.M_TO_FT, radiusM * BoatProfile.M_TO_FT) else "Waiting for GPS fix…"
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

    // --- helpers -------------------------------------------------------------



    override fun onDestroy() {
        stopAlarm()
        try { lm?.removeUpdates(this) } catch (_: Throwable) {}
        simReceiver?.let { runCatching { unregisterReceiver(it) } }
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java") override fun onProviderDisabled(provider: String) {}
    @Deprecated("Deprecated in Java") override fun onProviderEnabled(provider: String) {}
    @Deprecated("Deprecated in Java") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

    companion object {
        private const val TAG = "WatchService"
        private const val CHANNEL = "watch_status"       // silent (IMPORTANCE_LOW)
        private const val OLD_CHANNEL = "watch_service"   // deleted on start: was IMPORTANCE_HIGH, chimed
        private const val NOTIF_ID = 4711

        const val ACTION_SET_ANCHOR = "com.dvladi.mynavvy.watch.SET_ANCHOR"
        const val ACTION_UPDATE_ANCHOR = "com.dvladi.mynavvy.watch.UPDATE_ANCHOR"
        const val ACTION_CLEAR_ANCHOR = "com.dvladi.mynavvy.watch.CLEAR_ANCHOR"
        const val EX_LAT = "lat"; const val EX_LON = "lon"; const val EX_RADIUS = "radiusM"; const val EX_ALARM = "alarm"

        // Filter tuning (mirrors the values the Activity used before the move).
        private const val MAX_FIX_AGE_MS = 20_000L
        private const val MAX_ACCURACY_M = 50.0f
        private const val GLITCH_MIN_M = 100.0
        private const val GLITCH_MAX_KN = 15.0
        private const val GLITCH_RESYNC_MS = 30_000L
        private const val COG_MIN_MOVE_M = 2.0
        private const val TELEPORT_M = 500.0
    }
}
