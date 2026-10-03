package com.example.ui

import android.Manifest
import android.app.Application
import com.example.LanChatApplication
import com.example.data.call.CallStatus
import com.example.data.local.ContactEntity
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * BUG 2: لما المستخدم يرفض إذن الميكروفون، الـ UI كان بيطلق طلب الإذن بس
 * مابيحفظش مين كان عايز يعمل مكالمة. بعد ما يوافق، `startPendingCallIfPermitted`
 * كان بيلاقي `pendingCallRequest == null` وبيخرج، فالمكالمة ماهتشتغلش أبداً.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ChatViewModelPendingCallTest {

    private lateinit var application: LanChatApplication
    private lateinit var viewModel: ChatViewModel
    private var peer: CallOfferSink? = null

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext<Application>() as LanChatApplication
        viewModel = ChatViewModel(application)
        shadowOf(application).denyPermissions(Manifest.permission.RECORD_AUDIO)
    }

    @After
    fun tearDown() {
        viewModel.endCurrentCall()
        peer?.close()
        peer = null
    }

    private fun contact(deviceId: String = "peer_pending"): ContactEntity {
        val sink = peer ?: CallOfferSink().also { peer = it }
        return ContactEntity(
            deviceId = deviceId,
            displayName = "Pending Peer",
            ipAddress = "127.0.0.1",
            tcpPort = sink.port,
            isOnline = true
        )
    }

    @Test
    fun `a call requested without mic permission is deferred and the contact is remembered`() {
        val target = contact()

        viewModel.startVoiceCall(target)

        assertEquals(
            "the deferred call must survive the permission round trip",
            target,
            viewModel.pendingCallRequest.value
        )
        assertNull("no call may start while the mic permission is missing", viewModel.currentCall.value)
    }

    @Test
    fun `granting the mic permission later starts the deferred call`() {
        val target = contact()
        viewModel.startVoiceCall(target)
        assertNotNull(viewModel.pendingCallRequest.value)

        shadowOf(application).grantPermissions(Manifest.permission.RECORD_AUDIO)
        viewModel.startPendingCallIfPermitted()

        val call = viewModel.currentCall.value
        assertNotNull("the call that was waiting on the permission must now start", call)
        assertEquals(target.deviceId, call!!.peerId)
        assertEquals(CallStatus.OUTGOING_CALLING, call.status)
        assertEquals(false, call.isIncoming)
        assertNull("the pending request must be consumed", viewModel.pendingCallRequest.value)
    }

    @Test
    fun `a call requested with the mic permission already granted starts immediately`() {
        shadowOf(application).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val target = contact()

        viewModel.startVoiceCall(target)

        val call = viewModel.currentCall.value
        assertNotNull("no deferral is needed when the permission is already there", call)
        assertEquals(target.deviceId, call!!.peerId)
        assertEquals(CallStatus.OUTGOING_CALLING, call.status)
    }

    @Test
    fun `the second attempt only starts the call once`() {
        val target = contact()
        viewModel.startVoiceCall(target)
        shadowOf(application).grantPermissions(Manifest.permission.RECORD_AUDIO)

        viewModel.startPendingCallIfPermitted()
        val firstCallId = viewModel.currentCall.value?.callId
        viewModel.startPendingCallIfPermitted()

        assertEquals("the pending request is consumed, so no second call may start", firstCallId, viewModel.currentCall.value?.callId)
    }

    @Test
    fun `retrying while the permission is still denied reports the missing permission`() {
        viewModel.startVoiceCall(contact())

        viewModel.startPendingCallIfPermitted()

        assertNull("a still-denied permission must not start a silent call", viewModel.currentCall.value)
        assertNotNull("the user must be told why nothing happened", viewModel.errorMessage.value)
    }

    @Test
    fun `resuming with no pending call does nothing`() {
        shadowOf(application).grantPermissions(Manifest.permission.RECORD_AUDIO)

        viewModel.startPendingCallIfPermitted()

        assertNull(viewModel.currentCall.value)
        assertNull(viewModel.pendingCallRequest.value)
    }

    @Test
    fun `the permission check reflects the real android permission state`() {
        assertTrue("the test must start from a denied state", !viewModel.hasRecordAudioPermission())
        shadowOf(application).grantPermissions(Manifest.permission.RECORD_AUDIO)
        assertTrue(viewModel.hasRecordAudioPermission())
    }

    /** Accepts the call signalling packets so the offer really reaches a peer. */
    private class CallOfferSink : AutoCloseable {
        private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val port: Int get() = server.localPort

        init {
            Thread({
                while (!server.isClosed) {
                    try {
                        server.accept().use { socket ->
                            val input = DataInputStream(socket.getInputStream())
                            DataOutputStream(socket.getOutputStream())
                            val length = input.readInt()
                            if (length in 1..(2 * 1024 * 1024)) {
                                val body = ByteArray(length)
                                input.readFully(body)
                            }
                        }
                    } catch (_: Exception) {
                        return@Thread
                    }
                }
            }, "call-offer-sink").apply { isDaemon = true; start() }
        }

        override fun close() {
            server.close()
        }
    }
}
