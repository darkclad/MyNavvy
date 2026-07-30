package com.dvladi.mynavvy.nmea

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface

/**
 * Finds the NMEA 0183 endpoint on the boat's WiFi so nobody has to type an IP.
 *
 * Primary: Navico **GoFree** announce — the GO7 multicasts a JSON service blob to
 * `239.2.1.1:2052` every few seconds listing its data services incl. the NMEA-0183
 * TCP port. Needs a [WifiManager.MulticastLock]; Android drops multicast otherwise.
 * (boatsim's `--nmea-server` sends the same announce for bench testing.)
 *
 * Fallback: probe the AP's **gateway** on the standard ports (10110, then 2053) —
 * covers announce-less firmware, and on the emulator it finds boatsim through the
 * host loopback (the virtual WiFi gateway 10.0.2.2 IS the host PC).
 */
object GoFreeDiscovery {
    private const val TAG = "GoFreeDiscovery"
    const val GROUP = "239.2.1.1"
    const val ANNOUNCE_PORT = 2052
    private val PROBE_PORTS = intArrayOf(10110, 2053)
    private const val PROBE_TIMEOUT_MS = 2_000

    /**
     * Parse one GoFree announce datagram. Pure — unit-tested. Liberal by design: any
     * service whose name mentions "nmea" (but not N2K variants like "nmea-2000") counts;
     * the host comes from the JSON `IP` field or falls back to the datagram's [senderIp].
     */
    fun parseAnnounce(json: String, senderIp: String?): NmeaSource? = try {
        val o = JSONObject(json)
        var port = -1
        val services = o.optJSONArray("Services")
        if (services != null) {
            for (i in 0 until services.length()) {
                val s = services.optJSONObject(i) ?: continue
                val name = s.optString("Service").lowercase()
                if (name.contains("nmea") && !name.contains("2000") && !name.contains("2k")) {
                    val p = s.optInt("Port", -1)
                    if (p > 0) { port = p; break }
                }
            }
        }
        val host = o.optString("IP").ifEmpty { senderIp.orEmpty() }
        if (port in 1..65535 && host.isNotEmpty()) NmeaSource(host, port) else null
    } catch (_: Exception) {
        null
    }

    /** Wait up to [timeoutMs] for a valid announce on the WiFi [network]; null = none heard. */
    suspend fun discover(context: Context, network: Network?, timeoutMs: Long = 6_000L): NmeaSource? =
        withContext(Dispatchers.IO) {
            val app = context.applicationContext
            val wifi = app.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val lock = wifi.createMulticastLock("mynavvy-gofree").apply {
                setReferenceCounted(false); acquire()
            }
            try {
                MulticastSocket(ANNOUNCE_PORT).use { sock ->
                    val group = InetAddress.getByName(GROUP)
                    // Join on the WiFi interface specifically when we can name it — the phone
                    // may hold several networks and the default interface can be cellular.
                    val iface = wifiInterface(app, network)
                    if (iface != null) {
                        sock.networkInterface = iface
                        sock.joinGroup(InetSocketAddress(group, ANNOUNCE_PORT), iface)
                    } else {
                        @Suppress("DEPRECATION") sock.joinGroup(group)
                    }
                    sock.soTimeout = timeoutMs.toInt().coerceAtLeast(500)
                    val buf = ByteArray(4096)
                    val deadline = System.currentTimeMillis() + timeoutMs
                    while (System.currentTimeMillis() < deadline) {
                        val packet = DatagramPacket(buf, buf.size)
                        sock.receive(packet) // SocketTimeoutException ends the wait
                        val source = parseAnnounce(
                            String(packet.data, 0, packet.length, Charsets.UTF_8),
                            (packet.address as? Inet4Address)?.hostAddress)
                        if (source != null) {
                            Log.i(TAG, "GoFree announce → $source")
                            return@withContext source
                        }
                    }
                    null
                }
            } catch (t: Throwable) {
                Log.i(TAG, "no GoFree announce (${t.message})")
                null
            } finally {
                runCatching { lock.release() }
            }
        }

    /** Try the AP gateway on the standard NMEA ports; null if nothing accepts a connection. */
    suspend fun probeGateway(context: Context, network: Network?): NmeaSource? =
        withContext(Dispatchers.IO) {
            network ?: return@withContext null
            val cm = context.applicationContext
                .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val gateway = cm.getLinkProperties(network)?.routes
                ?.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }
                ?.gateway?.hostAddress
                ?: return@withContext null
            for (port in PROBE_PORTS) {
                try {
                    network.socketFactory.createSocket().use { s ->
                        s.connect(InetSocketAddress(gateway, port), PROBE_TIMEOUT_MS)
                    }
                    Log.i(TAG, "gateway probe hit $gateway:$port")
                    return@withContext NmeaSource(gateway, port)
                } catch (_: Exception) { /* next port */ }
            }
            Log.i(TAG, "gateway probe: nothing at $gateway on ${PROBE_PORTS.joinToString("/")}")
            null
        }

    /** The [NetworkInterface] behind the WiFi [network], for multicast group joins. */
    private fun wifiInterface(context: Context, network: Network?): NetworkInterface? {
        network ?: return null
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.getLinkProperties(network)?.interfaceName?.let { NetworkInterface.getByName(it) }
        } catch (_: Exception) {
            null
        }
    }
}
