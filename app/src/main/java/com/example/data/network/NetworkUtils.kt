package com.example.data.network

import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.provider.Settings
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InterfaceAddress
import java.net.NetworkInterface
import java.util.Collections

enum class ConnectionType {
    WIFI,
    HOTSPOT,
    WIFI_DIRECT,
    ETHERNET,
    CELLULAR,
    P2P_MESH,
    LOOPBACK,
    OFFLINE
}

data class NetworkInfoResult(
    val ipAddress: String,
    val connectionType: ConnectionType,
    val interfaceName: String,
    val isLanReady: Boolean
)

/**
 * وصف واجهة شبكة واحدة كما تراها من نظام التشغيل.
 * الفصل بين هذا النوع ودالة الاختيار يجعل منطق اختيار الشبكة قابلاً للاختبار
 * بدون أي اعتماد على أندرويد أو على واجهات الشبكة الحقيقية.
 */
data class LanInterfaceCandidate(
    val name: String,
    val ipv4: String? = null,
    val broadcast: String? = null,
    val isUp: Boolean = true,
    val isLoopback: Boolean = false,
    val prefixLength: Int = 24
)

object NetworkUtils {

    private const val TAG = "NetworkUtils"
    private const val LIMITED_BROADCAST = "255.255.255.255"
    private const val MAX_SUBNET_SCAN_HOSTS = 254

    private var multicastLock: WifiManager.MulticastLock? = null

    // ==================== Multicast lock ====================

    fun acquireMulticastLock(context: Context) {
        try {
            if (multicastLock == null || multicastLock?.isHeld == false) {
                val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                multicastLock = wifi?.createMulticastLock("LANChatMulticastLock")?.apply {
                    setReferenceCounted(true)
                    acquire()
                }
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to acquire multicast lock: ${e.message}")
        }
    }

    fun releaseMulticastLock() {
        try {
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to release multicast lock: ${e.message}")
        }
    }

    // ==================== Interface classification ====================

    private fun isPrivateIpv4(ip: String): Boolean {
        if (ip.startsWith("192.168.")) return true
        if (ip.startsWith("10.")) return true
        if (ip.startsWith("172.")) {
            val second = ip.split(".").getOrNull(1)?.toIntOrNull() ?: return false
            return second in 16..31
        }
        return false
    }

    private fun isKnownHotspotSubnet(ip: String): Boolean =
        ip.startsWith("192.168.43.") || ip.startsWith("192.168.44.") ||
            ip.startsWith("192.168.49.") || ip.startsWith("192.168.50.") ||
            ip.startsWith("10.42.0.")

    /**
     * تصنيف الواجهة بالاسم مع بدايات دقيقة.
     * الكود القديم كان يستخدم String.contains("ap") وهو يلتقط أي اسم يحتوي "ap"
     * مثل wlanap0 فيعتبره نقطة اتصال، ويفشل في الوصول إلى فرع p2p أصلاً.
     */
    private fun classifyName(name: String): ConnectionType? {
        val n = name.lowercase()
        return when {
            n.contains("p2p") -> ConnectionType.WIFI_DIRECT
            n.startsWith("ap") || n.startsWith("softap") || n.startsWith("tether") ||
                n.startsWith("br-cm") -> ConnectionType.HOTSPOT
            n.startsWith("wlan") || n.startsWith("swlan") || n.startsWith("wifi") ||
                n.startsWith("wl") -> ConnectionType.WIFI
            n.startsWith("eth") || n.startsWith("enp") || n.startsWith("br") -> ConnectionType.ETHERNET
            n.startsWith("rmnet") || n.startsWith("ccmni") || n.startsWith("pdp") ||
                n.startsWith("wwan") -> ConnectionType.CELLULAR
            n.startsWith("rndis") || n.startsWith("usb") || n.startsWith("ncm") -> ConnectionType.ETHERNET
            else -> null
        }
    }

    /**
     * أولوية الاختيار: أقل رقم = أعلى أولوية.
     * شبكة الواي فاي المتصلة بالراوتر أولاً لأنها الشبكة التي توجد فيها بقية الأجهزة،
     * ثم نقطة الاتصال الخاصة بنا، ثم Wi-Fi Direct، ثم إيثرنت.
     * واجهات USB/RNDIS(Point-to-Point) في المرتبة الأخيرة لأنها لا تصل إلا للجهاز الموصول.
     */
    private fun rank(type: ConnectionType): Int = when (type) {
        ConnectionType.WIFI -> 0
        ConnectionType.HOTSPOT -> 1
        ConnectionType.WIFI_DIRECT -> 2
        ConnectionType.ETHERNET -> 3
        ConnectionType.P2P_MESH -> 4
        ConnectionType.CELLULAR -> 9
        ConnectionType.LOOPBACK -> 10
        ConnectionType.OFFLINE -> 11
    }

    /**
     * اختيار أفضل واجهة LAN من قائمة المرشحين.
     * اختيار الشبكة المرشحة-я результа لا يعتمد على ترتيب القوائم الذي يعيده نظام التشغيل.
     */
    fun selectLanInterface(candidates: List<LanInterfaceCandidate>): NetworkInfoResult {
        var cellular: LanInterfaceCandidate? = null
        var tether: LanInterfaceCandidate? = null

        val lanCandidates = candidates.mapNotNull { c ->
            val ip = c.ipv4
            if (!c.isUp || c.isLoopback || ip.isNullOrBlank() || ip.startsWith("127.")) return@mapNotNull null
            if (!isPrivateIpv4(ip)) {
                if (classifyName(c.name) == ConnectionType.CELLULAR && cellular == null) cellular = c
                return@mapNotNull null
            }
            val byName = classifyName(c.name)
            val type = when {
                byName == ConnectionType.CELLULAR -> { if (cellular == null) cellular = c; return@mapNotNull null }
                byName != null -> byName
                isKnownHotspotSubnet(ip) -> ConnectionType.HOTSPOT
                else -> ConnectionType.WIFI
            }
            c to type
        }

        // USB tethering point-to-point: صالح كحل أخير فقط لأنه لا يصل إلا للجهاز الموصول
        val (pointToPoint, ranked) = lanCandidates.partition { (c, _) ->
            val n = c.name.lowercase()
            n.startsWith("rndis") || n.startsWith("usb") || n.startsWith("ncm")
        }
        if (tether == null) tether = pointToPoint.firstOrNull()?.first

        val best = ranked.minWithOrNull(
            compareBy<Pair<LanInterfaceCandidate, ConnectionType>> { rank(it.second) }
                .thenBy { it.first.name }
        )

        if (best != null) {
            return NetworkInfoResult(
                ipAddress = best.first.ipv4!!,
                connectionType = best.second,
                interfaceName = best.first.name,
                isLanReady = true
            )
        }

        tether?.let {
            return NetworkInfoResult(
                ipAddress = it.ipv4!!,
                connectionType = ConnectionType.ETHERNET,
                interfaceName = it.name,
                isLanReady = true
            )
        }

        cellular?.let {
            return NetworkInfoResult(
                ipAddress = it.ipv4!!,
                connectionType = ConnectionType.CELLULAR,
                interfaceName = it.name,
                isLanReady = false
            )
        }

        return NetworkInfoResult(
            ipAddress = "127.0.0.1",
            connectionType = ConnectionType.OFFLINE,
            interfaceName = "none",
            isLanReady = false
        )
    }

    /**
     * استخراج كل عناوين البث الخاصة بالشبكة المحلية.
     * كان الكود القديم يعتمد على 5 عناوين ثابتة فقط، فعلى أي شبكة أخرى
     * (مثل 192.168.5.x أو 10.x) لا يوجد عنوان بث صالح ولا تصل أي رسالة.
     */
    fun resolveBroadcastAddresses(
        candidates: List<LanInterfaceCandidate>,
        localIp: String?
    ): List<String> {
        val result = LinkedHashSet<String>()

        candidates.forEach { c ->
            if (!c.isUp || c.isLoopback) return@forEach
            val ip = c.ipv4 ?: return@forEach
            if (!isPrivateIpv4(ip)) return@forEach
            c.broadcast?.takeIf { it.isNotBlank() }?.let { result.add(it) }
            deriveBroadcast(ip)?.let { result.add(it) }
        }

        if (!localIp.isNullOrBlank() && isPrivateIpv4(localIp)) {
            deriveBroadcast(localIp)?.let { result.add(it) }
            result.add(LIMITED_BROADCAST)
        }

        return result.toList()
    }

    private fun deriveBroadcast(ipv4: String): String? {
        val parts = ipv4.split(".")
        if (parts.size != 4) return null
        val octets = parts.map { it.toIntOrNull() ?: return null }
        if (octets.any { it < 0 || it > 255 }) return null
        return "${octets[0]}.${octets[1]}.${octets[2]}.255"
    }

    // ==================== Live system queries ====================

    private fun enumerateCandidates(): List<LanInterfaceCandidate> {
        val out = mutableListOf<LanInterfaceCandidate>()
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                val isLoopback = try { intf.isLoopback } catch (e: Exception) { false }
                val isUp = try { intf.isUp } catch (e: Exception) { true }
                val addresses = try { intf.interfaceAddresses } catch (e: Exception) { emptyList<InterfaceAddress>() }

                var ipv4: String? = null
                var broadcast: String? = null
                var prefix = 24
                for (ia in addresses) {
                    val addr = ia.address
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val host = addr.hostAddress ?: continue
                        if (host.startsWith("127.")) continue
                        if (ipv4 == null) {
                            ipv4 = host
                            broadcast = ia.broadcast?.hostAddress
                            prefix = ia.networkPrefixLength.toInt().coerceIn(8, 30)
                        }
                    }
                }

                if (ipv4 != null) {
                    out.add(
                        LanInterfaceCandidate(
                            name = intf.name,
                            ipv4 = ipv4,
                            broadcast = broadcast,
                            isUp = isUp,
                            isLoopback = isLoopback,
                            prefixLength = prefix
                        )
                    )
                }
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to enumerate network interfaces: ${e.message}")
        }
        return out
    }

    fun getNetworkInfoResult(): NetworkInfoResult = selectLanInterface(enumerateCandidates())

    fun getLocalIpAddress(): String? {
        val result = getNetworkInfoResult()
        return if (result.isLanReady) result.ipAddress else if (result.ipAddress != "127.0.0.1") result.ipAddress else null
    }

    fun isCellularOnly(): Boolean =
        getNetworkInfoResult().connectionType == ConnectionType.CELLULAR

    /**
     * استخراج عنوان البث للشبكة المحددة.
     */
    fun getBroadcastAddresses(): List<InetAddress> {
        val candidates = enumerateCandidates()
        val selected = selectLanInterface(candidates)
        val localIp = selected.ipAddress.takeIf { it != "127.0.0.1" }
        return resolveBroadcastAddresses(candidates, localIp).mapNotNull { text ->
            try {
                InetAddress.getByName(text)
            } catch (e: Exception) {
                null
            }
        }
    }

    /**
     * حساب قائمة المضيفين في الشبكة الفرعية باستخدام الـ prefix الحقيقي
     * من الواجهة بدلاً من افتراض /24 دائماً.
     */
    fun getSubnetIps(): List<String> {
        val candidates = enumerateCandidates()
        val selected = selectLanInterface(candidates)
        val myIp = selected.ipAddress.takeIf { it != "127.0.0.1" } ?: return emptyList()
        val candidate = candidates.firstOrNull { it.ipv4 == myIp }
        val prefix = candidate?.prefixLength ?: 24
        return subnetHosts(myIp, prefix)
    }

    private fun subnetHosts(ipv4: String, prefixLength: Int): List<String> {
        val parts = ipv4.split(".")
        if (parts.size != 4) return emptyList()
        val octets = parts.map { it.toIntOrNull() ?: return emptyList() }
        if (octets.any { it < 0 || it > 255 }) return emptyList()

        val prefix = prefixLength.coerceIn(16, 30)
        val mask = prefixToMask(prefix)

        val network = IntArray(4) { (octets[it] and mask[it]) }
        val broadcast = IntArray(4) { (network[it] or mask[it].inv() and 0xFF) }

        val firstHost = IntArray(4) { network[it] }.also { it[3] = it[3] + 1 }
        val lastHost = IntArray(4) { broadcast[it] }.also { it[3] = it[3] - 1 }

        val total = ((lastHost[0] - firstHost[0]) shl 24) or
            ((lastHost[1] - firstHost[1]) shl 16) or
            ((lastHost[2] - firstHost[2]) shl 8) or
            (lastHost[3] - firstHost[3])

        if (total <= 0) return emptyList()

        val count = minOf(total, MAX_SUBNET_SCAN_HOSTS)
        val out = ArrayList<String>(count)
        var cur = (firstHost[0] shl 24) or (firstHost[1] shl 16) or (firstHost[2] shl 8) or firstHost[3]
        repeat(count) {
            out.add(
                "${(cur shr 24) and 0xFF}.${(cur shr 16) and 0xFF}.${(cur shr 8) and 0xFF}.${cur and 0xFF}"
            )
            cur++
        }
        return out
    }

    private fun prefixToMask(prefix: Int): IntArray {
        val mask = IntArray(4)
        var bits = prefix
        for (i in 0 until 4) {
            mask[i] = when {
                bits >= 8 -> 0xFF
                bits <= 0 -> 0x00
                else -> (0xFF shl (8 - bits)) and 0xFF
            }
            bits -= 8
        }
        return mask
    }

    fun getSubnetPrefix(): String? {
        val candidates = enumerateCandidates()
        val selected = selectLanInterface(candidates)
        val myIp = selected.ipAddress.takeIf { it != "127.0.0.1" } ?: return null
        val parts = myIp.split(".")
        if (parts.size != 4) return null
        val prefixLength = candidates.firstOrNull { it.ipv4 == myIp }?.prefixLength ?: 24
        val mask = prefixToMask(prefixLength.coerceIn(16, 30))
        val octets = parts.map { it.toIntOrNull() ?: return null }
        val net = IntArray(4) { (octets[it] and mask[it]) }
        return "${net[0]}.${net[1]}.${net[2]}.${net[3]}/$prefixLength"
    }

    fun getGatewayIp(): String? {
        val myIp = getLocalIpAddress() ?: return null
        val parts = myIp.split(".")
        if (parts.size != 4) return null
        val candidates = enumerateCandidates()
        val selected = selectLanInterface(candidates)
        val prefixLength = candidates.firstOrNull { it.ipv4 == selected.ipAddress }?.prefixLength ?: 24
        val mask = prefixToMask(prefixLength.coerceIn(16, 30))
        val octets = parts.map { it.toIntOrNull() ?: return null }
        val net = IntArray(4) { (octets[it] and mask[it]) }
        net[3] = net[3] + 1
        if (net[3] > 255) return null
        return "${net[0]}.${net[1]}.${net[2]}.${net[3]}"
    }

    fun pingHostLatency(ip: String, port: Int = 9999, timeoutMs: Int = 1200): Long {
        var socket: java.net.Socket? = null
        val start = System.currentTimeMillis()
        return try {
            socket = java.net.Socket()
            socket.connect(java.net.InetSocketAddress(ip, port), timeoutMs)
            val elapsed = System.currentTimeMillis() - start
            if (elapsed <= 0) 1 else elapsed
        } catch (e: Exception) {
            -1L
        } finally {
            try { socket?.close() } catch (_: Exception) {}
        }
    }

    fun testTcpPort(ip: String, port: Int = 9999, timeoutMs: Int = 250): Boolean {
        var socket: java.net.Socket? = null
        return try {
            socket = java.net.Socket()
            socket.connect(java.net.InetSocketAddress(ip, port), timeoutMs)
            true
        } catch (e: Exception) {
            false
        } finally {
            try { socket?.close() } catch (_: Exception) {}
        }
    }

    fun openHotspotSettings(context: Context) {
        val intents = listOf(
            Intent().setClassName("com.android.settings", "com.android.settings.TetherSettings"),
            Intent("android.settings.TETHER_SETTINGS"),
            Intent("android.settings.WIFI_AP_SETTINGS"),
            Intent(Settings.ACTION_WIRELESS_SETTINGS),
            Intent(Settings.ACTION_WIFI_SETTINGS),
            Intent(Settings.ACTION_SETTINGS)
        )
        for (intent in intents) {
            try {
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                context.startActivity(intent)
                return
            } catch (e: Exception) {
                // Try next intent
            }
        }
    }
}
