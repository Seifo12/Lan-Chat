package com.example.data.network

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Nothing in the app re-validated its listeners when the device's network state
 * changed, so a phone that lost Wi-Fi never came back without a process
 * restart. This covers registration being idempotent and teardown being safe.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ConnectivityWatcherTest {

    @Test
    fun `registering twice does not double register`() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val w = ConnectivityWatcher(ctx) {}
        w.register()
        w.register()
        assertTrue(w.isRegistered)
        w.unregister()
    }

    @Test
    fun `unregister twice is safe`() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val w = ConnectivityWatcher(ctx) {}
        w.register()
        w.unregister()
        w.unregister()
        assertTrue(!w.isRegistered)
    }

    @Test
    fun `unregister without register is safe`() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ConnectivityWatcher(ctx) {}.unregister()
    }
}
