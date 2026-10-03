package com.example.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MeshReadinessTest {

    private fun r(bt: Boolean, perm: Boolean, ps: Boolean) =
        MeshReadiness(bluetoothEnabled = bt, hasNearbyPermissions = perm, playServicesAvailable = ps)

    @Test
    fun `all three satisfied means ready`() {
        assertTrue(r(true, true, true).isReady)
        assertEquals(MeshBlockingStep.NONE, r(true, true, true).blockingStep())
    }

    @Test
    fun `bluetooth off blocks first`() {
        assertFalse(r(false, true, true).isReady)
        assertEquals(MeshBlockingStep.BLUETOOTH_OFF, r(false, true, true).blockingStep())
    }

    @Test
    fun `missing permissions block second`() {
        assertFalse(r(true, false, true).isReady)
        assertEquals(MeshBlockingStep.PERMISSIONS_MISSING, r(true, false, true).blockingStep())
    }

    @Test
    fun `missing play services block last`() {
        assertFalse(r(true, true, false).isReady)
        assertEquals(MeshBlockingStep.PLAY_SERVICES_UNAVAILABLE, r(true, true, false).blockingStep())
    }

    @Test
    fun `bluetooth is reported before permissions`() {
        assertEquals(MeshBlockingStep.BLUETOOTH_OFF, r(false, false, false).blockingStep())
    }

    @Test
    fun `permissions are reported before play services`() {
        assertEquals(MeshBlockingStep.PERMISSIONS_MISSING, r(true, false, false).blockingStep())
    }

    @Test
    fun `required permissions include the nearby group`() {
        val perms = MeshReadiness.requiredPermissions().toList()
        assertTrue(perms.contains("android.permission.NEARBY_WIFI_DEVICES"))
        assertTrue(perms.contains("android.permission.BLUETOOTH_ADVERTISE"))
        assertTrue(perms.contains("android.permission.BLUETOOTH_SCAN"))
        assertTrue(perms.contains("android.permission.BLUETOOTH_CONNECT"))
    }
}
