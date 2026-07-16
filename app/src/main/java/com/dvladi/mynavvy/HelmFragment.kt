package com.dvladi.mynavvy

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.fragment.app.Fragment
import com.dvladi.mynavvy.game.HelmData
import com.dvladi.mynavvy.screens.HelmScreen
import org.maplibre.android.geometry.LatLng
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot

/**
 * Helm: the Simrad NSO-Evo steering page, drawn in a Compose Canvas (no XML). A phone has no
 * masthead/log/fluxgate, so heading = COG, boat speed = SOG, depth from chart+tide, wind from the
 * Open-Meteo forecast. Nav (DTW/BTW/VMG/TTD/XTE) is derived from the active route: the destination
 * is the last route waypoint and the active leg is its final leg.
 *
 * The heavy state lives in [MainActivity]; this fragment snapshots it on the UI thread once a second
 * into an immutable [HelmData] state that the Compose gauges observe.
 */
class HelmFragment : Fragment() {

    private val dataState = mutableStateOf(HelmData())
    private val heelState = mutableFloatStateOf(0f)
    private val trimState = mutableFloatStateOf(0f)

    private var sensorManager: SensorManager? = null
    private var tiltSensor: Sensor? = null
    private var usesRawAccel = false
    private val gravity = FloatArray(3)   // low-passed gravity when falling back to the accelerometer

    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() { pushData(); handler.postDelayed(this, 1000) }
    }

    /** Heel (roll) + trim (pitch) from the gravity vector; stable for a roughly-upright mount. */
    private val tiltListener = object : SensorEventListener {
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        override fun onSensorChanged(e: SensorEvent) {
            val gx: Float; val gy: Float; val gz: Float
            if (usesRawAccel) {
                val a = 0.15f
                gravity[0] += a * (e.values[0] - gravity[0])
                gravity[1] += a * (e.values[1] - gravity[1])
                gravity[2] += a * (e.values[2] - gravity[2])
                gx = gravity[0]; gy = gravity[1]; gz = gravity[2]
            } else { gx = e.values[0]; gy = e.values[1]; gz = e.values[2] }
            heelState.floatValue = Math.toDegrees(atan2(gx, hypot(gy, gz)).toDouble()).toFloat()
            trimState.floatValue = Math.toDegrees(atan2(gz, hypot(gx, gy)).toDouble()).toFloat()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = ComposeView(requireContext()).apply {
        // NB: keep the DEFAULT composition strategy — DisposeOnViewTreeLifecycleDestroyed does not
        // find the lifecycle owner inside this FragmentContainerView and silently never composes.
        setContent {
            val data by dataState
            HelmScreen(data, heelState.floatValue, trimState.floatValue)
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(ticker)   // pushes immediately, then every second
        val sm = requireContext().getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        sensorManager = sm
        val grav = sm?.getDefaultSensor(Sensor.TYPE_GRAVITY)
        tiltSensor = grav ?: sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        usesRawAccel = grav == null
        tiltSensor?.let { sm?.registerListener(tiltListener, it, SensorManager.SENSOR_DELAY_UI) }
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
        sensorManager?.unregisterListener(tiltListener)
    }

    /** Snapshot live app state + derived route nav into an immutable frame for the gauges. */
    private fun pushData() {
        val act = activity as? MainActivity ?: return
        val loc = act.uiLastLocation()
        val cog = if (loc?.hasBearing() == true) loc.bearing.toDouble() else null
        val sog = loc?.let { it.speed * 1.94384 }
        val wind = act.uiWindAtBoatNow()
        val (depthM, ukcM) = act.uiDepthUkcNow()
        val ift = act.uiUnitsFt()
        val depthDisp = depthM?.let { if (ift) it * BoatProfile.M_TO_FT else it }

        var btw: Double? = null; var dtw: Double? = null; var ttd: Double? = null
        var vmg: Double? = null; var xte: Double? = null; var hasWp = false
        val wps = act.uiRouteWaypoints()
        if (loc != null && wps.isNotEmpty()) {
            val boat = LatLng(loc.latitude, loc.longitude)
            val target = wps.last()               // v1: destination = final route waypoint
            dtw = GeoUtils.distanceNm(boat, target)
            btw = GeoUtils.bearingDeg(boat, target)
            hasWp = true
            if (sog != null && sog > 0.05) {
                ttd = dtw / sog
                if (cog != null) vmg = sog * cos(Math.toRadians(btw!! - cog))
            }
            if (wps.size >= 2) xte = GeoUtils.crossTrackNm(wps[wps.size - 2], target, boat)
        }

        dataState.value = HelmData(
            hdg = cog, sogKn = sog, depthDisp = depthDisp, depthColor = act.uiUkcColor(ukcM),
            unitsFt = ift, btw = btw, dtwNm = dtw, ttdHours = ttd, vmgKn = vmg, xteNm = xte,
            windDir = wind?.dirDeg, hasWaypoint = hasWp)
    }
}
