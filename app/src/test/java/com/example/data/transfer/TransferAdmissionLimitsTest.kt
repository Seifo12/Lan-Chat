package com.example.data.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferAdmissionLimitsTest {

    @Test
    fun `auto accept threshold is twenty megabytes`() {
        assertEquals(20L * 1024 * 1024, TransferAdmissionLimits.AUTO_ACCEPT_MAX_BYTES)
    }

    @Test
    fun `transfer ceiling is two gigabytes not five`() {
        assertEquals(2L * 1024 * 1024 * 1024, TransferAdmissionLimits.MAX_TRANSFER_BYTES)
        assertTrue(TransferAdmissionLimits.MAX_TRANSFER_BYTES < 5L * 1024 * 1024 * 1024)
    }

    @Test
    fun `apk ceiling keeps the existing five hundred megabytes`() {
        assertEquals(500L * 1024 * 1024, TransferAdmissionLimits.MAX_APK_BYTES)
    }

    @Test
    fun `two concurrent incoming transfers per peer`() {
        assertEquals(2, TransferAdmissionLimits.MAX_CONCURRENT_PER_PEER)
    }

    @Test
    fun `rate window and request budget`() {
        assertEquals(20, TransferAdmissionLimits.RATE_MAX_REQUESTS)
        assertEquals(10L * 60 * 1000, TransferAdmissionLimits.RATE_WINDOW_MS)
    }

    @Test
    fun `auto accepted byte budget`() {
        assertEquals(200L * 1024 * 1024, TransferAdmissionLimits.RATE_MAX_AUTOBYTES)
    }

    @Test
    fun `hold is five minutes and sender waits longer than the hold`() {
        assertEquals(5L * 60 * 1000, TransferAdmissionLimits.PENDING_HOLD_MS)
        assertTrue(
            "sender must outlive the hold or it gives up before consent can arrive",
            TransferAdmissionLimits.SENDER_OFFER_TIMEOUT_MS > TransferAdmissionLimits.PENDING_HOLD_MS
        )
    }

    @Test
    fun `pending caps and the admission pool outranks them`() {
        assertEquals(2, TransferAdmissionLimits.PENDING_PER_PEER)
        assertEquals(5, TransferAdmissionLimits.PENDING_TOTAL)
        assertTrue(
            "a park is a suspension, so the pool must exceed the pending cap or it deadlocks itself",
            TransferAdmissionLimits.ADMISSION_POOL_SLOTS > TransferAdmissionLimits.PENDING_TOTAL
        )
    }

    @Test
    fun `free space margin`() {
        assertEquals(64L * 1024 * 1024, TransferAdmissionLimits.FREE_SPACE_MARGIN_BYTES)
    }
}
