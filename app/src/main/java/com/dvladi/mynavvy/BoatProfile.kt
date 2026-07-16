package com.dvladi.mynavvy

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * The boat, as configured by the user. Single source of truth: [BoatModel] and the router read
 * from this, so changing the draft here immediately changes what water the router considers safe.
 *
 * Stored as JSON in filesDir. All lengths are METRES internally; [units] only affects display.
 */
data class BoatProfile(
    var name: String = "My Boat",
    var make: String = "",
    var model: String = "",
    var year: Int = 0,
    var keel: String = "",           // fin / wing / shoal — determines draft, so it is not optional

    var loaM: Double = 0.0,
    var lwlM: Double = 0.0,
    var beamM: Double = 0.0,
    var draftM: Double = 1.8,        // load-bearing: feeds RoutingGrid.navigable()
    var airDraftM: Double = 0.0,     // for bridges
    var displacementKg: Double = 0.0,

    var cruiseKn: Double = 6.0,      // motoring cruise speed
    var ukcMarginM: Double = 0.5,    // required clearance under the keel

    var units: String = "m",         // "m" or "ft" — display only
    var defaultRangeNm: Double = 1.0, // chart range (screen width, nm) the map opens at on launch
    var dayMode: Boolean = false     // Helm readout: true = light (sunlit deck), false = dark
) {
    fun label(): String = listOfNotNull(
        year.takeIf { it > 0 }?.toString(),
        make.takeIf { it.isNotBlank() },
        model.takeIf { it.isNotBlank() },
        keel.takeIf { it.isNotBlank() }?.let { "($it keel)" }
    ).joinToString(" ").ifBlank { name }

    /** True once we know enough to route safely. */
    fun isRoutable(): Boolean = draftM > 0.0 && cruiseKn > 0.0

    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name); put("make", make); put("model", model)
        put("year", year); put("keel", keel)
        put("loaM", loaM); put("lwlM", lwlM); put("beamM", beamM)
        put("draftM", draftM); put("airDraftM", airDraftM)
        put("displacementKg", displacementKg)
        put("cruiseKn", cruiseKn); put("ukcMarginM", ukcMarginM)
        put("units", units); put("defaultRangeNm", defaultRangeNm); put("dayMode", dayMode)
    }

    companion object {
        private const val TAG = "BoatProfile"
        private const val FILE = "boat_profile.json"

        fun load(ctx: Context): BoatProfile {
            val f = File(ctx.filesDir, FILE)
            if (!f.exists()) return BoatProfile()
            return try {
                fromJson(JSONObject(f.readText()))
            } catch (t: Throwable) {
                Log.w(TAG, "profile unreadable, using defaults: ${t.message}")
                BoatProfile()
            }
        }

        fun save(ctx: Context, p: BoatProfile) {
            try {
                File(ctx.filesDir, FILE).writeText(p.toJson().toString(2))
            } catch (t: Throwable) {
                Log.e(TAG, "could not save profile: ${t.message}")
                Diagnostics.capture(t)
            }
        }

        fun fromJson(j: JSONObject): BoatProfile = BoatProfile(
            name = j.optString("name", "My Boat"),
            make = j.optString("make", ""),
            model = j.optString("model", ""),
            year = j.optInt("year", 0),
            keel = j.optString("keel", ""),
            loaM = j.optDouble("loaM", 0.0),
            lwlM = j.optDouble("lwlM", 0.0),
            beamM = j.optDouble("beamM", 0.0),
            draftM = j.optDouble("draftM", 1.8),
            airDraftM = j.optDouble("airDraftM", 0.0),
            displacementKg = j.optDouble("displacementKg", 0.0),
            cruiseKn = j.optDouble("cruiseKn", 6.0),
            ukcMarginM = j.optDouble("ukcMarginM", 0.5),
            units = j.optString("units", "m"),
            defaultRangeNm = j.optDouble("defaultRangeNm", 1.0),
            dayMode = j.optBoolean("dayMode", false)
        )

        const val M_TO_FT = 3.280839895
    }
}
