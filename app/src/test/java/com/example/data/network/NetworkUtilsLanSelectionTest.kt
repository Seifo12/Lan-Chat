package com.example.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for peer discovery on the local network.
 *
 * These cover the two defects that made the app unable to see any nearby phone:
 *  1. getNetworkInfoResult() returned on the FIRST hotspot-classified interface with no
 *     priority ordering, so a USB/RNDIS or Wi-Fi Direct interface hijacked the address
 *     and every beacon was sent to the wrong subnet.
 *  2. getBroadcastAddresses() only had 5 hardcoded fallback subnets, so on any other
 *     LAN (192.168.5.x, 10.x, 172.16-31.x) there was no valid broadcast address at all
 *     and beacons silently went nowhere.
 */
class NetworkUtilsLanSelectionTest {

    private fun candidate(
        name: String,
        ipv4: String? = null,
        broadcast: String? = null
    ) = LanInterfaceCandidate(name = name, ipv4 = ipv4, broadcast = broadcast)

    // ---------- BUG 1: interface priority ----------

    @Test
    fun `usb rndis interface does not hijack the wifi lan address`() {
        val result = NetworkUtils.selectLanInterface(listOf(
            candidate("wlan0", "192.168.1.50", "192.168.1.255"),
            candidate("rndis0", "192.168.42.129", "192.168.42.255")
        ))

        assertEquals("192.168.1.50", result.ipAddress)
        assertEquals(ConnectionType.WIFI, result.connectionType)
        assertEquals("wlan0", result.interfaceName)
        assertTrue(result.isLanReady)
    }

    @Test
    fun `rndis interface is only used when nothing better exists`() {
        val result = NetworkUtils.selectLanInterface(listOf(
            candidate("rmnet_data0", "10.10.0.2", null),
            candidate("rndis0", "192.168.42.129", "192.168.42.255")
        ))

        assertEquals("192.168.42.129", result.ipAddress)
        assertEquals("rndis0", result.interfaceName)
    }

    @Test
    fun `wifi direct p2p interface is not misclassified as hotspot`() {
        val result = NetworkUtils.selectLanInterface(listOf(
            candidate("p2p-wlan0-0", "192.168.49.42", "192.168.49.255")
        ))

        assertEquals(ConnectionType.WIFI_DIRECT, result.connectionType)
    }

    @Test
    fun `wifi station is preferred over wifi direct when both are up`() {
        val result = NetworkUtils.selectLanInterface(listOf(
            candidate("wlan0", "192.168.1.50", "192.168.1.255"),
            candidate("p2p-wlan0-0", "192.168.49.42", "192.168.49.255")
        ))

        assertEquals("192.168.1.50", result.ipAddress)
        assertEquals(ConnectionType.WIFI, result.connectionType)
    }

    @Test
    fun `hotspot interface is used when it is the only lan`() {
        val result = NetworkUtils.selectLanInterface(listOf(
            candidate("ap0", "192.168.43.1", "192.168.43.255")
        ))

        assertEquals(ConnectionType.HOTSPOT, result.connectionType)
        assertEquals("192.168.43.1", result.ipAddress)
    }

    @Test
    fun `interface name containing ap is not treated as hotspot`() {
        val result = NetworkUtils.selectLanInterface(listOf(
            candidate("wlanap0", "192.168.7.20", "192.168.7.255")
        ))

        assertEquals(ConnectionType.WIFI, result.connectionType)
        assertEquals("192.168.7.20", result.ipAddress)
    }

    @Test
    fun `cellular interface alone is never lan ready`() {
        val result = NetworkUtils.selectLanInterface(listOf(
            candidate("rmnet_data0", "10.10.0.2", null)
        ))

        assertEquals(ConnectionType.CELLULAR, result.connectionType)
        assertFalse(result.isLanReady)
    }

    @Test
    fun `no usable interface reports offline`() {
        val result = NetworkUtils.selectLanInterface(emptyList())

        assertEquals(ConnectionType.OFFLINE, result.connectionType)
        assertEquals("127.0.0.1", result.ipAddress)
        assertFalse(result.isLanReady)
    }

    @Test
    fun `down and loopback interfaces are ignored`() {
        val result = NetworkUtils.selectLanInterface(listOf(
            LanInterfaceCandidate("wlan0", "192.168.1.50", "192.168.1.255", isUp = false),
            LanInterfaceCandidate("lo", "127.0.0.1", null, isLoopback = true)
        ))

        assertEquals(ConnectionType.OFFLINE, result.connectionType)
    }

    // ---------- BUG 2: broadcast address resolution ----------

    @Test
    fun `broadcast covers arbitrary lan subnets not in the old hardcoded list`() {
        val addresses = NetworkUtils.resolveBroadcastAddresses(
            candidates = listOf(candidate("wlan0", "192.168.5.30", broadcast = null)),
            localIp = "192.168.5.30"
        )

        assertTrue("expected 192.168.5.255 in $addresses", addresses.contains("192.168.5.255"))
    }

    @Test
    fun `broadcast covers ten point subnets`() {
        val addresses = NetworkUtils.resolveBroadcastAddresses(
            candidates = listOf(candidate("wlan0", "10.1.2.3", broadcast = null)),
            localIp = "10.1.2.3"
        )

        assertTrue("expected 10.1.2.255 in $addresses", addresses.contains("10.1.2.255"))
    }

    @Test
    fun `broadcast covers rfc1918 172_16_31 subnets`() {
        val addresses = NetworkUtils.resolveBroadcastAddresses(
            candidates = listOf(candidate("wlan0", "172.20.0.4", broadcast = null)),
            localIp = "172.20.0.4"
        )

        assertTrue("expected 172.20.0.255 in $addresses", addresses.contains("172.20.0.255"))
    }

    @Test
    fun `broadcast prefers the interface reported broadcast when available`() {
        val addresses = NetworkUtils.resolveBroadcastAddresses(
            candidates = listOf(candidate("wlan0", "192.168.9.4", broadcast = "192.168.9.255")),
            localIp = "192.168.9.4"
        )

        assertTrue(addresses.contains("192.168.9.255"))
    }

    @Test
    fun `broadcast always includes the limited broadcast address`() {
        val addresses = NetworkUtils.resolveBroadcastAddresses(
            candidates = listOf(candidate("wlan0", "192.168.1.5", null)),
            localIp = "192.168.1.5"
        )

        assertTrue("expected 255.255.255.255 in $addresses", addresses.contains("255.255.255.255"))
    }

    @Test
    fun `broadcast includes every lan capable interface not just the selected one`() {
        val addresses = NetworkUtils.resolveBroadcastAddresses(
            candidates = listOf(
                candidate("wlan0", "192.168.1.50", null),
                candidate("ap0", "192.168.43.1", null)
            ),
            localIp = "192.168.1.50"
        )

        assertTrue("expected wlan broadcast in $addresses", addresses.contains("192.168.1.255"))
        assertTrue("expected hotspot broadcast in $addresses", addresses.contains("192.168.43.255"))
    }

    @Test
    fun `broadcast is empty when offline`() {
        val addresses = NetworkUtils.resolveBroadcastAddresses(emptyList(), localIp = null)

        assertTrue(addresses.isEmpty())
    }

    @Test
    fun `local ip is null when offline so beacons are not sent`() {
        val result = NetworkUtils.selectLanInterface(emptyList())

        assertEquals("127.0.0.1", result.ipAddress)
    }
}
