package com.dvladi.mynavvy

import android.content.Context
import android.location.Location
import android.os.Build
import androidx.fragment.app.Fragment

// Shared one-liners that used to be copy-pasted per class. Keep these tiny and dependency-free.

/** True on the Android emulator (goldfish/ranchu/sdk images). Used to suppress the emulator's fake
 *  GPS in sim mode, and to work around its guest GLES encoder SIGSEGVing when MapLibre renders a
 *  tilted / course-up-rotated camera (real GPUs are fine — devices get the full 3D nav camera). */
fun isEmulator(): Boolean =
    Build.HARDWARE.contains("goldfish") || Build.HARDWARE.contains("ranchu") ||
    Build.FINGERPRINT.startsWith("generic") || Build.FINGERPRINT.contains("emulator", true) ||
    Build.MODEL.contains("Emulator", true) || Build.MODEL.contains("Android SDK built for", true) ||
    Build.MANUFACTURER.contains("Genymotion", true) || Build.PRODUCT.contains("sdk") ||
    (Build.BRAND.startsWith("generic") && Build.DEVICE.startsWith("generic"))

/** How old a fix is (ms), preferring the monotonic elapsed-realtime clock; falls back to the wall
 *  clock, and to "fresh" (0) when a fix carries no timestamp at all. */
fun Location.ageMs(nowElapsedMs: Long): Long {
    if (elapsedRealtimeNanos > 0L) return nowElapsedMs - elapsedRealtimeNanos / 1_000_000L
    if (time > 0L) return System.currentTimeMillis() - time
    return 0L
}

/** dp → px. */
fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()
fun Fragment.dp(v: Int) = requireContext().dp(v)
