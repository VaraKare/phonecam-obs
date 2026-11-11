package dev.phonecam.obs

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.provider.Settings
import java.io.File

/**
 * Finds PhoneCam OBS sources on the local network: Bonjour/DNS-SD "_phonecam._tcp" with TXT id
 * (Mac id) and pair=1 while a pairing window is open. Resolves one service at a time, as older
 * Android versions require. Holds only addresses; nothing is sent until a handshake starts.
 */
class WifiDiscovery(context: Context) {
    class Found(val id: String, val name: String, val host: String, val port: Int, val pairing: Boolean)
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val found = mutableMapOf<String, Found>()
    private val pending = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private var running = false

    private val discovery = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) = Unit
        override fun onDiscoveryStopped(serviceType: String) = Unit
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        override fun onServiceFound(info: NsdServiceInfo) { synchronized(this@WifiDiscovery) { pending.addLast(info) }; next() }
        override fun onServiceLost(info: NsdServiceInfo) { synchronized(this@WifiDiscovery) { found.remove(info.serviceName) } }
    }

    @Suppress("DEPRECATION")  // resolveService: the only resolver before Android 14
    private fun next() {
        val info = synchronized(this) {
            if (resolving || !running) return
            pending.removeFirstOrNull()?.also { resolving = true } ?: return
        }
        nsd.resolveService(info, object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) { synchronized(this@WifiDiscovery) { resolving = false }; next() }
            override fun onServiceResolved(info: NsdServiceInfo) {
                val id = info.attributes["id"]?.let { String(it) }
                val host = (if (Build.VERSION.SDK_INT >= 34) info.hostAddresses.firstOrNull { it is java.net.Inet4Address } else info.host)?.hostAddress
                synchronized(this@WifiDiscovery) {
                    if (id != null && WifiPairings.isId(id) && host != null && PairingLink.isIpv4(host) && info.port in 1..65535)
                        found[info.serviceName] = Found(id, info.serviceName.removePrefix("PhoneCam on "), host, info.port,
                            info.attributes["pair"]?.let { String(it) } == "1")
                    resolving = false
                }
                next()
            }
        })
    }

    fun start() { synchronized(this) { running = true }; runCatching { nsd.discoverServices("_phonecam._tcp", NsdManager.PROTOCOL_DNS_SD, discovery) } }
    fun stop() { synchronized(this) { running = false; pending.clear() }; runCatching { nsd.stopServiceDiscovery(discovery) } }
    fun all(): List<Found> = synchronized(this) { found.values.toList() }
}

/** Phone side of direct Wi-Fi: pairing and finding a paired Mac when live starts. */
class WifiConnector(private val context: Context) {
    val pairings = WifiPairings(File(context.filesDir, "wifi-pairings"))
    val hasPairings: Boolean get() = pairings.macs.isNotEmpty()
    /** Why the last attempt failed, for the "OBS did not connect" message. */
    @Volatile var problem: String? = null; private set

    fun phoneName(): String = runCatching { Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME) }.getOrNull()
        ?.takeIf { it.isNotBlank() } ?: Build.MODEL

    /** Tries every paired Mac, at Bonjour and last-known addresses, until one accepts or [cancelled]. */
    fun connect(cancelled: () -> Boolean): WifiClient.Session? {
        if (!hasPairings) return null
        val discovery = WifiDiscovery(context).apply { start() }
        try {
            while (!cancelled()) {
                for (mac in pairings.macs) {
                    val candidates = (discovery.all().filter { it.id == mac.id }.map { it.host to it.port } + mac.addresses.map { it to mac.port }).distinct()
                    if (candidates.isEmpty()) problem = "${mac.name} not found on this Wi-Fi. Check the phone and Mac are on the same network and the PhoneCam source is visible."
                    for ((host, port) in candidates) {
                        if (cancelled()) return null
                        try {
                            val session = WifiClient.open(WifiClient.connect(host, port), mac.id, pairings.phoneId, mac.key)
                            pairings.remember(PairedMac(mac.id, mac.name, mac.key, port, (listOf(host) + mac.addresses).distinct().take(4)))
                            problem = null
                            return session
                        } catch (e: WifiClient.Refused) { problem = e.message }
                        catch (e: Exception) { problem = "Can't reach ${mac.name} at $host. Put the phone and Mac on the same Wi-Fi (some routers isolate devices, for example guest networks), or use the phone's hotspot." }
                    }
                }
                Thread.sleep(1000)
            }
        } finally { discovery.stop() }
        return null
    }

    /** Pairs with the Mac from a scanned QR code. Returns the Mac's name; throws with a readable message. */
    fun pair(link: PairingLink): String {
        var last: Exception? = null
        for (host in link.addresses) {
            try {
                val key = WifiClient.pair(WifiClient.connect(host, link.port, 3000), link.macId, pairings.phoneId, link.secret, phoneName())
                pairings.remember(PairedMac(link.macId, link.name, key, link.port, link.addresses))
                return link.name
            } catch (e: WifiClient.Refused) { throw e } catch (e: Exception) { last = e }
        }
        // The QR addresses may be from another interface; look for the Mac on this network.
        val found = findPairing(8_000).filter { it.id == link.macId }
        for (mac in found) {
            val key = WifiClient.pair(WifiClient.connect(mac.host, mac.port, 3000), mac.id, pairings.phoneId, link.secret, phoneName())
            pairings.remember(PairedMac(mac.id, link.name, key, mac.port, listOf(mac.host)))
            return link.name
        }
        throw java.io.IOException("Can't reach ${link.name} (${link.addresses.joinToString().ifEmpty { "no address" }}). Put the phone and Mac on the same Wi-Fi, or join the Mac to the phone's hotspot.", last)
    }

    /** Pairs with whichever nearby OBS source shows this typed code. */
    fun pair(code: ByteArray): String {
        val candidates = findPairing(8_000)
        if (candidates.isEmpty()) throw java.io.IOException("No OBS source with an open pairing window found on this Wi-Fi. In OBS click Pair a phone over Wi-Fi, check both devices are on the same network, or scan the QR code instead.")
        var last: Exception? = null
        for (mac in candidates) {
            try {
                val key = WifiClient.pair(WifiClient.connect(mac.host, mac.port, 3000), mac.id, pairings.phoneId, code, phoneName())
                pairings.remember(PairedMac(mac.id, mac.name, key, mac.port, listOf(mac.host)))
                return mac.name
            } catch (e: Exception) { last = e }
        }
        throw last ?: java.io.IOException("Pairing failed")
    }

    private fun findPairing(waitMs: Long): List<WifiDiscovery.Found> {
        val discovery = WifiDiscovery(context).apply { start() }
        try {
            val end = System.currentTimeMillis() + waitMs
            while (System.currentTimeMillis() < end) {
                val open = discovery.all().filter { it.pairing }
                if (open.isNotEmpty()) { Thread.sleep(500); return discovery.all().filter { it.pairing } }
                Thread.sleep(250)
            }
            return emptyList()
        } finally { discovery.stop() }
    }
}
