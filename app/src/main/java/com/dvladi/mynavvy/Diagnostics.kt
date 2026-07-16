package com.dvladi.mynavvy

import android.app.Application
import android.os.Build
import android.util.Log
import io.sentry.Attachment
import io.sentry.Breadcrumb
import io.sentry.Sentry
import io.sentry.SentryLevel
import io.sentry.android.core.SentryAndroid
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.ArrayDeque
import kotlin.concurrent.thread

/**
 * Remote diagnostics for MyNavvy.
 *
 * Reports uncaught crashes, ANRs, and native (NDK) crashes to the self-hosted
 * GlitchTip server (Sentry-protocol compatible) at [BuildConfig.SENTRY_DSN].
 * The app queues events offline and retries, so a remote tablet phones home
 * whenever it next has connectivity — no live ADB session required.
 *
 * A background thread tails the app's OWN logcat (an app can read only its own
 * logs without any special permission since Android 4.1), keeps the last
 * [MAX_LINES] lines in a ring buffer, and:
 *   - adds warning/error lines as breadcrumbs, and
 *   - attaches the full recent log as `logcat.txt` to EVERY event via
 *     beforeSend — so both crashes and manual snapshots carry context.
 *
 * Disable by building with an empty `-PSENTRY_DSN=`.
 */
object Diagnostics {
    private const val TAG = "Diagnostics"
    private const val MAX_LINES = 500
    private const val MAX_EXTRA_CHARS = 50_000

    private val buffer = ArrayDeque<String>(MAX_LINES)
    @Volatile private var started = false

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
                val text = snapshotLog()
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
        startLogcat()
        Log.i(TAG, "Remote diagnostics active -> ${BuildConfig.SENTRY_DSN.substringAfter('@')}")
    }

    private fun snapshotLog(): String =
        synchronized(buffer) { buffer.joinToString("\n") }

    private fun startLogcat() {
        if (started) return
        started = true
        thread(isDaemon = true, name = "logcat-collector") {
            try {
                // No -pid/-T flags: those are API 24+. A plain read already returns
                // only this app's own logs on all supported API levels (21+).
                val proc = ProcessBuilder("logcat", "-v", "threadtime")
                    .redirectErrorStream(true)
                    .start()
                BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        synchronized(buffer) {
                            if (buffer.size >= MAX_LINES) buffer.pollFirst()
                            buffer.addLast(line)
                        }
                        // Skip Sentry's own log lines to avoid noisy self-referential breadcrumbs.
                        if (line.contains(" Sentry")) continue
                        when (levelOf(line)) {
                            'W' -> breadcrumb(line, SentryLevel.WARNING)
                            'E', 'F' -> breadcrumb(line, SentryLevel.ERROR)
                            else -> {}
                        }
                    }
                }
            } catch (t: Throwable) {
                // Some OEM ROMs restrict logcat exec. Crash reporting still works;
                // only the log-tail breadcrumbs/attachment are unavailable.
                Log.w(TAG, "logcat collector unavailable: ${t.message}")
            }
        }
    }

    private fun breadcrumb(line: String, level: SentryLevel) {
        Sentry.addBreadcrumb(Breadcrumb().apply {
            category = "logcat"
            message = line
            this.level = level
        })
    }

    /** threadtime format: "MM-DD HH:MM:SS.mmm  PID  TID L TAG: msg" */
    private fun levelOf(line: String): Char {
        val m = Regex("""\s([VDIWEF])\s""").find(line)
        return m?.groupValues?.getOrNull(1)?.firstOrNull() ?: 'I'
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
}
