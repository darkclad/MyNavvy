package com.dvladi.mynavvy.nmea

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Where NMEA 0183 sentences come from. All known sources are a plain TCP stream:
 * GO7 GoFree AP (`192.168.0.1:10110` or `:2053`), the future ESP32 gateway
 * (`192.168.4.1:10110`), or boatsim's `--nmea-server` bench feed
 * (`10.0.2.2:10110` from the emulator, `192.168.137.1:10110` from a tablet on
 * the PC's Mobile Hotspot).
 */
data class NmeaSource(val host: String, val port: Int = 10110) {
    override fun toString() = "$host:$port"
}

/** Connection lifecycle, for the status chip and the GPS-authority logic. */
sealed class ConnectionState {
    /** Not started / stopped. */
    data object Off : ConnectionState()
    /** Trying to reach a source ([attempt] starts at 1, resets on success). */
    data class Connecting(val attempt: Int) : ConnectionState()
    /** Stream from [source] is up and sentences are flowing. */
    data class Connected(val sinceMs: Long, val source: NmeaSource) : ConnectionState()
    /** Connection lost; next attempt after [retryInMs]. */
    data class Waiting(val retryInMs: Long) : ConnectionState()
}

/**
 * TCP NMEA 0183 stream client. Collect [sentences] to run it; the flow connects,
 * reads CRLF-delimited sentences, and reconnects with backoff forever — cancel the
 * collecting coroutine to stop. Observe [state] for the connection lifecycle.
 *
 * The endpoint comes from [resolve], evaluated on **every** connect cycle — a fixed
 * host:port for manual mode, or GoFree discovery + gateway probe for auto mode (so a
 * GO7 that moved networks or changed its port is re-found on reconnect, for free).
 *
 * The Android trap this class exists for: boat APs (GO7/ESP32) have no internet,
 * so Android keeps cellular as the default network and plain sockets silently
 * bypass the WiFi — every connect times out. Fix: `requestNetwork(TRANSPORT_WIFI)`
 * and create sockets from that [Network]'s socketFactory, which both pins our
 * traffic to the WiFi and tells ConnectivityManager to keep that network alive.
 * If no WiFi network appears (e.g. the emulator's virtual setup misbehaves) we
 * fall back to an unbound socket rather than never connecting at all.
 */
class NmeaClient(
    context: Context,
    private val resolve: suspend (Network?) -> NmeaSource?,
) {
    /** Fixed-endpoint client (manual mode / debug broadcast / test connection). */
    constructor(context: Context, source: NmeaSource) : this(context, { source })

    private val cm = context.applicationContext
        .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Off)
    val state: StateFlow<ConnectionState> = _state

    val sentences: Flow<String> = flow {
        // Hold the WiFi network request for the whole collection: it pins the AP's
        // network up even though it has no internet (and re-delivers it after drops).
        val wifi = MutableStateFlow<Network?>(null)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { wifi.value = network }
            override fun onLost(network: Network) { if (wifi.value == network) wifi.value = null }
        }
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        val requested = runCatching { cm.requestNetwork(request, callback) }
            .onFailure { Log.w(TAG, "requestNetwork(WIFI) failed: ${it.message}") }
            .isSuccess

        try {
            var attempt = 0
            while (true) {
                attempt++
                _state.value = ConnectionState.Connecting(attempt)

                val network = if (requested)
                    withTimeoutOrNull(WIFI_WAIT_MS) { wifi.filterNotNull().first() }
                else null
                if (network == null)
                    Log.w(TAG, "no WiFi network after ${WIFI_WAIT_MS / 1000}s — trying unbound socket")

                try {
                    val source = resolve(network)
                    if (source == null) {
                        Log.i(TAG, "no source resolved (discovery found nothing)")
                    } else {
                        val socket = network?.socketFactory?.createSocket() ?: Socket()
                        socket.use { s ->
                            s.connect(InetSocketAddress(source.host, source.port), CONNECT_TIMEOUT_MS)
                            s.soTimeout = READ_TIMEOUT_MS // a dead feed must not hang forever
                            Log.i(TAG, "connected to $source" + if (network != null) " (WiFi-bound)" else " (unbound)")
                            _state.value = ConnectionState.Connected(System.currentTimeMillis(), source)
                            attempt = 0
                            val reader = s.getInputStream().bufferedReader(Charsets.US_ASCII)
                            while (true) {
                                val line = reader.readLine() ?: break // EOF = server closed
                                val sentence = line.trim()
                                if (sentence.startsWith('$') || sentence.startsWith('!')) emit(sentence)
                            }
                            Log.i(TAG, "stream ended (EOF from $source)")
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    Log.w(TAG, "connection failed: ${t.message}")
                }

                // Exponential-ish backoff, capped — the boat's AP may be gone for hours.
                val backoff = BACKOFF_MS[minOf(attempt, BACKOFF_MS.size - 1).coerceAtLeast(0)]
                _state.value = ConnectionState.Waiting(backoff)
                delay(backoff)
            }
        } finally {
            if (requested) runCatching { cm.unregisterNetworkCallback(callback) }
        }
    }
        .onCompletion { _state.value = ConnectionState.Off }
        .flowOn(Dispatchers.IO)

    companion object {
        private const val TAG = "NmeaClient"
        private const val WIFI_WAIT_MS = 5_000L
        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val READ_TIMEOUT_MS = 15_000
        // Index = consecutive failed attempts (0 unused: attempt resets to 0 on success,
        // so the first retry after a working stream drops is quick).
        private val BACKOFF_MS = longArrayOf(1_000, 1_000, 2_000, 5_000, 10_000, 15_000)
    }
}
