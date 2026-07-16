package com.dvladi.mynavvy

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors

/**
 * Fetches marine weather + tides from free public APIs, off the main thread:
 *   - Wind (grid): Open-Meteo /forecast (GFS/ECMWF blend), knots, hourly, 3 days.
 *   - Tide (San Diego 9410170): NOAA CO-OPS predictions, hourly, 48 h.
 * Raw responses are cached to disk so a later offline launch still has data.
 */
class WeatherRepository(private val context: Context) {

    data class WindPoint(
        val lat: Double, val lon: Double,
        val dirDeg: Double, val speedKn: Double, val gustKn: Double,
        /** Precipitation (mm) for the hour — drives the rain overlay. */
        val precipMm: Double = 0.0
    )

    class Weather(val timesUtcMs: LongArray, val points: List<PointSeries>) {
        class PointSeries(
            val lat: Double, val lon: Double,
            val speedKn: DoubleArray, val dirDeg: DoubleArray, val gustKn: DoubleArray,
            /** Precipitation (mm) per hour, from the forecast grid. */
            val precipMmH: DoubleArray,
            /** Air temperature (°C) per hour, from the forecast grid. */
            val tempC: DoubleArray,
            /** Sea-surface temperature (°C) per hour, from the marine grid — NaN where unavailable
             *  (inland points). Filled in after the marine fetch; null until then. */
            var sstC: DoubleArray? = null
        )

        fun hourCount() = timesUtcMs.size

        private fun nearestSeries(lat: Double, lon: Double): PointSeries? {
            var best: PointSeries? = null; var bd = Double.MAX_VALUE
            for (p in points) {
                val d = (p.lat - lat) * (p.lat - lat) + (p.lon - lon) * (p.lon - lon)
                if (d < bd) { bd = d; best = p }
            }
            return best
        }

        /** Air temperature (°C) at the nearest grid point for hour [h], or null. */
        fun airTempCAt(lat: Double, lon: Double, h: Int): Double? =
            nearestSeries(lat, lon)?.let {
                if (h in it.tempC.indices && it.tempC[h].isFinite()) it.tempC[h] else null
            }

        /** Sea-surface temperature (°C) at the nearest grid point for hour [h], or null. */
        fun seaTempCAt(lat: Double, lon: Double, h: Int): Double? =
            nearestSeries(lat, lon)?.sstC?.let {
                if (h in it.indices && it[h].isFinite()) it[h] else null
            }

        fun windAt(h: Int): List<WindPoint> = points.mapNotNull { p ->
            if (h < 0 || h >= p.speedKn.size) null
            else WindPoint(p.lat, p.lon, p.dirDeg[h], p.speedKn[h], p.gustKn[h],
                if (h < p.precipMmH.size) p.precipMmH[h] else 0.0)
        }

        fun nearest(lat: Double, lon: Double, h: Int): WindPoint? {
            var best: PointSeries? = null; var bd = Double.MAX_VALUE
            for (p in points) {
                val d = (p.lat - lat) * (p.lat - lat) + (p.lon - lon) * (p.lon - lon)
                if (d < bd) { bd = d; best = p }
            }
            val p = best ?: return null
            if (h < 0 || h >= p.speedKn.size) return null
            return WindPoint(p.lat, p.lon, p.dirDeg[h], p.speedKn[h], p.gustKn[h])
        }

        fun nearestHourIndex(nowMs: Long): Int {
            var best = 0; var bd = Long.MAX_VALUE
            for (i in timesUtcMs.indices) {
                val d = Math.abs(timesUtcMs[i] - nowMs)
                if (d < bd) { bd = d; best = i }
            }
            return best
        }
    }

    class Tide(val timesMs: LongArray, val heightsFt: DoubleArray) {
        /**
         * Linear-interpolated tide height (feet above MLLW) at an arbitrary instant. NOAA gives
         * 6-minute predictions, so interpolation is accurate enough to read a ±15 min window.
         * Returns null outside the predicted range rather than silently extrapolating.
         */
        fun heightAt(ms: Long): Double? {
            if (timesMs.size < 2) return timesMs.firstOrNull()?.let { heightsFt[0] }
            if (ms < timesMs.first() || ms > timesMs.last()) return null
            // predictions are ascending and evenly spaced; binary search the bracketing pair
            var lo = 0; var hi = timesMs.size - 1
            while (hi - lo > 1) {
                val mid = (lo + hi) / 2
                if (timesMs[mid] <= ms) lo = mid else hi = mid
            }
            val span = (timesMs[hi] - timesMs[lo]).toDouble()
            if (span <= 0.0) return heightsFt[lo]
            val f = (ms - timesMs[lo]) / span
            return heightsFt[lo] + (heightsFt[hi] - heightsFt[lo]) * f
        }
    }

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    /** True when the data last delivered came off the disk cache rather than the network. */
    @Volatile
    var lastServedFromCache: Boolean = false
        private set

    fun fetch(bounds: LatLngBounds, cb: (Weather?, Tide?, String?) -> Unit) {
        io.execute {
            // At sea there is no signal. Don't burn ~35 s of connect timeouts before falling back.
            if (!online()) {
                val w = loadCached("wind.json") { parseWind(it) }
                val t = loadCached("tide.json") { parseTide(it) }
                if (w != null) readCache("marine.json")?.let { runCatching { mergeMarine(w, it) } }
                lastServedFromCache = true
                val msg = if (w == null && t == null) "Offline — no cached forecast"
                else "Offline — using cached forecast"
                main.post { cb(w, t, msg) }
                return@execute
            }

            var err: String? = null
            var weather: Weather? = null
            var tide: Tide? = null
            var cached = false
            try {
                val raw = httpGet(windUrl(bounds))
                writeCache("wind.json", raw)
                weather = parseWind(raw)
            } catch (e: Exception) {
                Log.w(TAG, "wind fetch failed", e)
                err = "wind offline (${e.message})"
                weather = loadCached("wind.json") { parseWind(it) }
                if (weather != null) cached = true
            }
            try {
                val raw = httpGet(tideUrl())
                writeCache("tide.json", raw)
                tide = parseTide(raw)
            } catch (e: Exception) {
                Log.w(TAG, "tide fetch failed", e)
                if (err == null) err = "tide offline (${e.message})"
                tide = loadCached("tide.json") { parseTide(it) }
                if (tide != null) cached = true
            }
            // Sea-surface temperature (marine grid). Best-effort: a failure here must not lose the
            // wind/tide we already have, so it only folds SST into the existing forecast.
            if (weather != null) {
                try {
                    val raw = httpGet(marineUrl(bounds))
                    writeCache("marine.json", raw)
                    mergeMarine(weather, raw)
                } catch (e: Exception) {
                    Log.w(TAG, "marine (SST) fetch failed", e)
                    readCache("marine.json")?.let { runCatching { mergeMarine(weather, it) } }
                }
            }
            lastServedFromCache = cached
            main.post { cb(weather, tide, err) }
        }
    }

    private fun <T> loadCached(name: String, parse: (String) -> T): T? =
        readCache(name)?.let { runCatching { parse(it) }.getOrNull() }

    /** Cheap connectivity probe so an offline launch falls back instantly instead of timing out. */
    private fun online(): Boolean = try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val net = cm.activeNetwork
            val caps = if (net != null) cm.getNetworkCapabilities(net) else null
            caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        } else {
            @Suppress("DEPRECATION") (cm.activeNetworkInfo?.isConnected == true)
        }
    } catch (t: Throwable) {
        true // can't tell — try the network rather than refuse to fetch
    }

    // --- URLs ---------------------------------------------------------------

    /** The same 5×5 grid of lat/lon, as comma-joined strings, shared by the forecast + marine calls
     *  so their point arrays line up index-for-index. */
    private fun gridLatLons(b: LatLngBounds): Pair<String, String> {
        val grid = ArrayList<LatLng>()
        val n = GRID_N
        val ne = b.northEast; val sw = b.southWest
        val latN = ne.latitude; val latS = sw.latitude
        val lonE = ne.longitude; val lonW = sw.longitude
        for (i in 0 until n) for (j in 0 until n) {
            val lat = latS + (latN - latS) * i / (n - 1)
            val lon = lonW + (lonE - lonW) * j / (n - 1)
            grid.add(LatLng(lat, lon))
        }
        return grid.joinToString(",") { String.format(Locale.US, "%.4f", it.latitude) } to
            grid.joinToString(",") { String.format(Locale.US, "%.4f", it.longitude) }
    }

    private fun windUrl(b: LatLngBounds): String {
        val (lats, lons) = gridLatLons(b)
        return "https://api.open-meteo.com/v1/forecast?latitude=$lats&longitude=$lons" +
            "&hourly=wind_speed_10m,wind_direction_10m,wind_gusts_10m,temperature_2m,precipitation" +
            "&wind_speed_unit=kn&temperature_unit=celsius&forecast_days=3&timezone=UTC"
    }

    /** Sea-surface temperature on the SAME grid, from the Open-Meteo Marine API. Points align with
     *  the forecast call by index; inland points come back null (parsed as NaN). */
    private fun marineUrl(b: LatLngBounds): String {
        val (lats, lons) = gridLatLons(b)
        return "https://marine-api.open-meteo.com/v1/marine?latitude=$lats&longitude=$lons" +
            "&hourly=sea_surface_temperature&forecast_days=3&timezone=UTC"
    }

    /**
     * Tide predictions. NOTE: no `interval` parameter — for product=predictions that yields NOAA's
     * native 6-minute series (hourly is far too coarse to read a +/-15 min window). `datum=MLLW`
     * matches the ENC sounding datum (DSPM_SDAT=12), so these heights add straight onto charted
     * depths with no datum conversion.
     */
    private fun tideUrl(): String {
        val today = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
        return "https://api.tidesandcurrents.noaa.gov/api/prod/datagetter?product=predictions" +
            "&application=MyNavvy&begin_date=$today&range=48&datum=MLLW&station=$TIDE_STATION" +
            "&time_zone=lst_ldt&units=english&format=json"
    }

    // --- Parsing ------------------------------------------------------------

    private fun parseWind(raw: String): Weather {
        val trimmed = raw.trim()
        val arr = if (trimmed.startsWith("[")) JSONArray(trimmed)
        else JSONArray().put(JSONObject(trimmed))
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
        var times = LongArray(0)
        val points = ArrayList<Weather.PointSeries>()
        for (k in 0 until arr.length()) {
            val o = arr.getJSONObject(k)
            val h = o.getJSONObject("hourly")
            val t = h.getJSONArray("time")
            if (times.isEmpty()) times = LongArray(t.length()) { iso.parse(t.getString(it))!!.time }
            val sp = h.getJSONArray("wind_speed_10m")
            val di = h.getJSONArray("wind_direction_10m")
            val gu = h.getJSONArray("wind_gusts_10m")
            val tp = h.optJSONArray("temperature_2m")
            val pr = h.optJSONArray("precipitation")
            val n = t.length()
            points.add(
                Weather.PointSeries(
                    o.optDouble("latitude"), o.optDouble("longitude"),
                    DoubleArray(n) { sp.optDouble(it, 0.0) },
                    DoubleArray(n) { di.optDouble(it, 0.0) },
                    DoubleArray(n) { gu.optDouble(it, 0.0) },
                    DoubleArray(n) { pr?.optDouble(it, 0.0) ?: 0.0 },
                    DoubleArray(n) { tp?.optDouble(it, Double.NaN) ?: Double.NaN }
                )
            )
        }
        return Weather(times, points)
    }

    /** Fold sea-surface temperature from a marine response into an existing [Weather], matching
     *  points by index (both calls request the same grid in the same order). */
    private fun mergeMarine(weather: Weather, raw: String) {
        val trimmed = raw.trim()
        val arr = if (trimmed.startsWith("[")) JSONArray(trimmed)
        else JSONArray().put(JSONObject(trimmed))
        for (k in 0 until minOf(arr.length(), weather.points.size)) {
            val h = arr.getJSONObject(k).optJSONObject("hourly") ?: continue
            val sst = h.optJSONArray("sea_surface_temperature") ?: continue
            val n = weather.points[k].tempC.size
            weather.points[k].sstC = DoubleArray(n) { sst.optDouble(it, Double.NaN) }
        }
    }

    private fun parseTide(raw: String): Tide {
        val arr = JSONObject(raw).getJSONArray("predictions")
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US) // station local (lst_ldt) ~ device tz
        val times = LongArray(arr.length())
        val hts = DoubleArray(arr.length())
        for (i in 0 until arr.length()) {
            val p = arr.getJSONObject(i)
            times[i] = fmt.parse(p.getString("t"))!!.time
            hts[i] = p.getString("v").toDouble()
        }
        return Tide(times, hts)
    }

    // --- HTTP + cache -------------------------------------------------------

    private fun httpGet(url: String): String {
        Log.d(TAG, "GET $url")
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 20000
        c.setRequestProperty("User-Agent", "MyNavvy")
        try {
            if (c.responseCode !in 200..299) throw RuntimeException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }

    /**
     * Offline store. Deliberately NOT cacheDir: Android may evict that under storage pressure,
     * and it would do so exactly when we're offshore with no way to refetch.
     */
    private fun offlineDir(): File = File(context.filesDir, "offline").apply { mkdirs() }

    private fun writeCache(name: String, data: String) =
        runCatching { File(offlineDir(), name).writeText(data) }

    private fun readCache(name: String): String? =
        File(offlineDir(), name).let { if (it.exists()) it.readText() else null }

    /** When the stored forecast/tide was last refreshed, or null if we have none. */
    fun cachedAtMs(name: String): Long? =
        File(offlineDir(), name).let { if (it.exists()) it.lastModified() else null }

    companion object {
        private const val TAG = "MyNavvyWx"
        private const val GRID_N = 8           // 8x8 = 64 wind points per fetch (denser field)
        private const val TIDE_STATION = "9410170" // San Diego, San Diego Bay
    }
}
