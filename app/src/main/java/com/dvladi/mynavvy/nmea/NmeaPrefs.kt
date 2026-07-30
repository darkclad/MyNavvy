package com.dvladi.mynavvy.nmea

import android.content.Context

/** Which position/instrument source the user picked in Settings → Data source. */
enum class NmeaMode {
    /** Phone GPS only — NMEA machinery entirely off (default). */
    OFF,
    /** Find the source on the boat WiFi: GoFree announce, then gateway probe. */
    AUTO,
    /** Fixed TCP host:port. */
    MANUAL,
}

/**
 * Persisted Data-source choice. Read by [com.dvladi.mynavvy.WatchService] on start
 * (the NMEA pipeline is production behavior, not gated on the sim build flag) and
 * written by MainActivity's Data source dialog.
 */
data class NmeaPrefs(
    val mode: NmeaMode = NmeaMode.OFF,
    val host: String = DEFAULT_HOST,
    val port: Int = DEFAULT_PORT,
) {
    fun manualSource() = NmeaSource(host, port)

    companion object {
        /** GO7 GoFree AP defaults, per the proposal doc. */
        const val DEFAULT_HOST = "192.168.0.1"
        const val DEFAULT_PORT = 10110
        private const val FILE = "nmea"

        fun load(context: Context): NmeaPrefs {
            val sp = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            val mode = runCatching { NmeaMode.valueOf(sp.getString("mode", null) ?: "OFF") }
                .getOrDefault(NmeaMode.OFF)
            return NmeaPrefs(
                mode = mode,
                host = sp.getString("host", DEFAULT_HOST) ?: DEFAULT_HOST,
                port = sp.getInt("port", DEFAULT_PORT))
        }

        fun save(context: Context, prefs: NmeaPrefs) {
            context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
                .putString("mode", prefs.mode.name)
                .putString("host", prefs.host)
                .putInt("port", prefs.port)
                .apply()
        }
    }
}
