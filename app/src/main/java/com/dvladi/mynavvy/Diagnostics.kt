package com.dvladi.mynavvy

import android.app.Application
import android.os.Build
import android.util.Log
import io.sentry.Attachment
import io.sentry.Sentry
import io.sentry.SentryLevel
import io.sentry.android.core.SentryAndroid

/**
 * Remote diagnostics for MyNavvy.
 *
 * Reports uncaught crashes, ANRs, and native (NDK) crashes to the self-hosted
 * GlitchTip server (Sentry-protocol compatible) at [BuildConfig.SENTRY_DSN].
 * The app queues events offline and retries, so a remote tablet phones home
 * whenever it next has connectivity — no live ADB session required.
 *
 * Log capture is ON-DEMAND ONLY: a one-shot `logcat -d` runs when an event is
 * sent (crash/ANR/manual snapshot) or when the user taps Share diagnostics, and
 * the app's own recent log is attached to the event. We deliberately do NOT run
 * a long-lived background `logcat` process — a persistent log tail in an app
 * that also holds INTERNET matches on-device malware heuristics (Play Protect /
 * Samsung Auto Blocker) and gets the app flagged as harmful. A brief exec, only
 * at the moment something is actually reported, does not.
 *
 * An app can read only its OWN logs via `logcat` without any special permission
 * (since Android 4.1), so no READ_LOGS is requested.
 *
 * Disable by building with an empty `-PSENTRY_DSN=`.
 */
object Diagnostics {
    private const val TAG = "Diagnostics"
    private const val MAX_EXTRA_CHARS = 50_000

    private val enabled: Boolean get() = BuildConfig.SENTRY_DSN.isNotBlank()

    fun init(app: Application) {
        if (!enabled) {
            Log.i(TAG, "Sentry DSN blank — remote diagnostics disabled")
            return
        }
        SentryAndroid.init(app) { o ->
            o.dsn = BuildConfig.SENTRY_DSN
            o.release = "${BuildConfig.APPLICATION_ID}@${BuildConfig.VERSION_NAME}+${BuildConfig.VERSION_CODE}"
            o.environment = if (BuildConfig.DEBUG) "debug" else "production"
            o.isAttachStacktrace = true
            o.isEnableAppComponentBreadcrumbs = true
            o.isAnrEnabled = true
            // No performance tracing — keep payloads light for the old tablet.
            o.tracesSampleRate = 0.0
            o.beforeSend = io.sentry.SentryOptions.BeforeSendCallback { event, hint ->
                // One-shot capture of the app's own recent logcat, only now that we're sending.
                val text = fullLog()
                if (text.isNotEmpty()) {
                    // Attach as a file (works on real Sentry / if attachments enabled)...
                    hint.addAttachment(Attachment(text.toByteArray(), "logcat.txt", "text/plain"))
                    // ...and also embed in the event body, because GlitchTip CE
                    // discards attachments — the extra persists and shows in the UI.
                    event.setExtra("logcat_tail", if (text.length > MAX_EXTRA_CHARS) text.takeLast(MAX_EXTRA_CHARS) else text)
                }
                event
            }
        }
        Sentry.configureScope { scope ->
            scope.setTag("device.model", Build.MODEL ?: "?")
            scope.setTag("device.manufacturer", Build.MANUFACTURER ?: "?")
            scope.setTag("android.sdk", Build.VERSION.SDK_INT.toString())
        }
        Log.i(TAG, "Remote diagnostics active -> ${BuildConfig.SENTRY_DSN.substringAfter('@')}")
    }

    /**
     * Send the current logs plus [message] to the server without needing a crash.
     * beforeSend attaches the recent logcat automatically. Safe to call from the UI.
     */
    fun sendLogSnapshot(message: String) {
        if (!enabled) return
        Sentry.captureMessage(message, SentryLevel.INFO)
    }

    /** Report a handled exception (e.g. from a caught error path). */
    fun capture(t: Throwable) {
        if (!enabled) return
        Sentry.captureException(t)
    }

    /**
     * Write a shareable diagnostics report — a device/state header + the app's own recent logcat —
     * to cacheDir/logs and return the file. For field debugging with no ADB / no signal: the user
     * shares it (Config → Data → Share diagnostics) and sends it on when they next have a connection.
     */
    fun writeReport(context: android.content.Context): java.io.File {
        val sb = StringBuilder()
        sb.append("=== MyNavvy diagnostics ===\n")
        sb.append("app: ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})\n")
        sb.append("device: ${Build.MANUFACTURER} ${Build.MODEL}  Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})\n")
        sb.append("now: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", java.util.Locale.US).format(java.util.Date())}\n")
        runCatching {
            val pm = context.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
            sb.append("battery-optimisation exempt: ${pm.isIgnoringBatteryOptimizations(context.packageName)}\n")
        }
        runCatching {
            val cm = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
            sb.append("network: ${if (caps == null) "none" else buildString {
                if (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)) append("wifi ")
                if (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR)) append("cellular ")
                append(if (caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)) "internet" else "no-internet")
                if (!caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)) append(" UNVALIDATED(captive?)")
            }}\n")
        }
        runCatching {
            val offline = java.io.File(context.filesDir, "offline")
            sb.append("offline cache: ")
            sb.append(offline.listFiles()?.joinToString(", ") { "${it.name}=${it.length()}b" } ?: "(none)")
            sb.append("\n")
        }
        runCatching { sb.append("prefs: nmea=${context.getSharedPreferences("nmea", 0).all}\n") }
        sb.append("\n=== logcat (this app) ===\n")
        sb.append(fullLog())

        val dir = java.io.File(context.cacheDir, "logs").apply { mkdirs() }
        val f = java.io.File(dir, "mynavvy-log.txt")
        f.writeText(sb.toString())
        return f
    }

    /** A one-shot dump of the app's own logcat (empty string if the ROM blocks the exec). */
    private fun fullLog(): String = try {
        val proc = ProcessBuilder("logcat", "-d", "-v", "time").redirectErrorStream(true).start()
        proc.inputStream.bufferedReader().use { it.readText() }.takeLast(400_000)   // cap so the file stays sendable
    } catch (t: Throwable) {
        Log.w(TAG, "logcat dump unavailable: ${t.message}")
        ""
    }
}
