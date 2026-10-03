package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.lanchat.offline.messenger.R
import com.example.data.call.ActiveCallInfo
import com.example.data.call.CallStatus
import com.example.data.local.ChatMessageEntity
import com.example.data.local.ContactEntity
import com.example.data.local.GroupEntity
import com.example.data.local.MessageStatus
import com.example.data.local.UserPreferences
import com.example.data.network.AckDeliveredPacket
import com.example.data.network.AckReadPacket
import com.example.data.network.AppUpdateRequestPacket
import com.example.data.network.AppUpdateResponsePacket
import com.example.data.network.BeaconAckPacket
import com.example.data.network.BeaconPacket
import com.example.data.network.CallAnswerPacket
import com.example.data.network.CallEndPacket
import com.example.data.network.CallOfferPacket
import com.example.data.network.CallRingingPacket
import com.example.data.network.FileMessagePacket
import com.example.data.network.GroupAnnouncePacket
import com.example.data.network.MeshRelayPacket
import com.example.data.network.NetworkPacket
import com.example.data.network.PacketType
import com.example.data.network.PhotoMessagePacket
import com.example.data.network.TextMessagePacket
import com.example.data.network.VideoMessagePacket
import com.example.data.network.VoiceMessagePacket
import com.example.data.security.EncryptionManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AppCoreTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("LAN Chat", appName)
  }

  @Test
  fun `verify user preferences defaults and properties`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val prefs = UserPreferences(context)

    assertNotNull(prefs.deviceId)
    assertTrue(prefs.deviceId.isNotBlank())
    assertEquals("DARK", prefs.themeMode)

    prefs.displayName = "مستخدم تجريبي"
    assertEquals("مستخدم تجريبي", prefs.displayName)

    prefs.isDeveloper = true
    assertTrue(prefs.isDeveloper)

    prefs.isLargeFontMode = false
    assertFalse(prefs.isLargeFontMode)
  }

  @Test
  fun `verify chat message entity and media classification`() {
    val textMsg = ChatMessageEntity(
      id = "m1",
      conversationId = "c1",
      senderId = "s1",
      senderName = "أحمد",
      recipientId = "r1",
      text = "مرحبا",
      isFromMe = true,
      status = MessageStatus.SENT
    )
    assertFalse(textMsg.isVoice)
    assertFalse(textMsg.isPhoto)
    assertFalse(textMsg.isVideo)
    assertFalse(textMsg.isFile)

    val voiceMsg = ChatMessageEntity(
      id = "m2",
      conversationId = "c1",
      senderId = "s1",
      senderName = "أحمد",
      recipientId = "r1",
      text = "",
      isVoice = true,
      filePath = "/path/to/voice.m4a",
      audioDurationSeconds = 3,
      isFromMe = true,
      status = MessageStatus.DELIVERED
    )
    assertTrue(voiceMsg.isVoice)
    assertEquals(3, voiceMsg.audioDurationSeconds)

    val photoMsg = ChatMessageEntity(
      id = "m3",
      conversationId = "c1",
      senderId = "s1",
      senderName = "أحمد",
      recipientId = "r1",
      text = "لقطة شاشة",
      isPhoto = true,
      photoPath = "/path/to/photo.jpg",
      isFromMe = false,
      status = MessageStatus.READ
    )
    assertTrue(photoMsg.isPhoto)

    val videoMsg = ChatMessageEntity(
      id = "m4",
      conversationId = "c1",
      senderId = "s1",
      senderName = "أحمد",
      recipientId = "r1",
      text = "فيديو",
      isVideo = true,
      filePath = "/path/to/video.mp4",
      fileName = "video.mp4",
      fileSize = 1048576L,
      isFromMe = true
    )
    assertTrue(videoMsg.isVideo)
    assertEquals("video.mp4", videoMsg.fileName)

    val fileMsg = ChatMessageEntity(
      id = "m5",
      conversationId = "c1",
      senderId = "s1",
      senderName = "أحمد",
      recipientId = "r1",
      text = "",
      isFile = true,
      filePath = "/path/to/doc.pdf",
      fileName = "doc.pdf",
      fileSize = 102400L,
      isFromMe = false
    )
    assertTrue(fileMsg.isFile)
    assertEquals("doc.pdf", fileMsg.fileName)
    assertEquals(102400L, fileMsg.fileSize)
  }

  @Test
  fun `verify contacts and groups entities`() {
    val contact = ContactEntity(
      deviceId = "dev_99",
      displayName = "محمد علي",
      ipAddress = "192.168.1.50",
      tcpPort = 9999,
      isOnline = true,
      isMeshPeer = false,
      customNickname = "أبو علي"
    )
    assertEquals("dev_99", contact.deviceId)
    assertEquals("أبو علي", contact.customNickname)
    assertTrue(contact.isOnline)

    val group = GroupEntity(
      groupId = "grp_team",
      groupName = "فريق العمل",
      description = "تنسيق المهام",
      createdBy = "dev_99",
      avatarColorIndex = 2
    )
    assertEquals("grp_team", group.groupId)
    assertEquals("فريق العمل", group.groupName)
    assertEquals(2, group.avatarColorIndex)
  }

  @Test
  fun `verify network packet serialization and deserialization`() {
    // 1. Text message
    val textPacket = TextMessagePacket(
      messageId = "txt_101",
      senderId = "dev_1",
      senderName = "خالد",
      recipientId = "dev_2",
      text = "أهلاً بك في شبكة LAN Chat",
      isGroup = false,
      isDeveloper = false
    )
    val parsedText = NetworkPacket.fromJson(textPacket.toJson()) as? TextMessagePacket
    assertNotNull(parsedText)
    assertEquals("txt_101", parsedText?.messageId)
    assertEquals("أهلاً بك في شبكة LAN Chat", parsedText?.text)

    // 2. Beacon discovery packet
    val beacon = BeaconPacket(
      deviceId = "dev_node_1",
      displayName = "هاتف نوكيا",
      avatarColorIndex = 3,
      tcpPort = 9999,
      isDeveloper = true,
      versionCode = 2,
      versionName = "2.0.0",
      isMeshSupported = true
    )
    val parsedBeacon = NetworkPacket.fromJson(beacon.toJson()) as? BeaconPacket
    assertNotNull(parsedBeacon)
    assertEquals("dev_node_1", parsedBeacon?.deviceId)
    assertEquals(true, parsedBeacon?.isDeveloper)
    assertEquals(true, parsedBeacon?.isMeshSupported)

    // 3. Voice packet
    val voice = VoiceMessagePacket(
      messageId = "voc_202",
      senderId = "dev_1",
      senderName = "خالد",
      recipientId = "dev_2",
      audioBase64 = "UklGRiAAAABXQVZFZm10IBAAAAABAAEA",
      durationSeconds = 3
    )
    val parsedVoice = NetworkPacket.fromJson(voice.toJson()) as? VoiceMessagePacket
    assertNotNull(parsedVoice)
    assertEquals("voc_202", parsedVoice?.messageId)
    assertEquals(3, parsedVoice?.durationSeconds)

    // 4. Photo packet
    val photo = PhotoMessagePacket(
      messageId = "img_303",
      senderId = "dev_1",
      senderName = "خالد",
      recipientId = "dev_2",
      photoBase64 = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==",
      caption = "صورة توضيحية"
    )
    val parsedPhoto = NetworkPacket.fromJson(photo.toJson()) as? PhotoMessagePacket
    assertNotNull(parsedPhoto)
    assertEquals("صورة توضيحية", parsedPhoto?.caption)

    // 5. Video packet
    val video = VideoMessagePacket(
      messageId = "vid_404",
      senderId = "dev_1",
      senderName = "خالد",
      recipientId = "dev_2",
      fileName = "clip.mp4",
      fileSize = 5242880,
      mimeType = "video/mp4",
      fileBase64 = "AAAA",
      caption = "شاهد هذا"
    )
    val parsedVideo = NetworkPacket.fromJson(video.toJson()) as? VideoMessagePacket
    assertNotNull(parsedVideo)
    assertEquals("clip.mp4", parsedVideo?.fileName)
    assertEquals("video/mp4", parsedVideo?.mimeType)

    // 6. File packet
    val file = FileMessagePacket(
      messageId = "fil_505",
      senderId = "dev_1",
      senderName = "خالد",
      recipientId = "dev_2",
      fileName = "presentation.pdf",
      fileSize = 1048576,
      mimeType = "application/pdf",
      fileBase64 = "JVBERi0=",
      caption = "العرض النهائي"
    )
    val parsedFile = NetworkPacket.fromJson(file.toJson()) as? FileMessagePacket
    assertNotNull(parsedFile)
    assertEquals("presentation.pdf", parsedFile?.fileName)

    // 7. Delivery & Read Receipts (Double Ticks)
    val ackDelivered = AckDeliveredPacket(messageId = "txt_101", senderId = "dev_2", recipientId = "dev_1")
    val parsedAckDelivered = NetworkPacket.fromJson(ackDelivered.toJson()) as? AckDeliveredPacket
    assertNotNull(parsedAckDelivered)
    assertEquals("txt_101", parsedAckDelivered?.messageId)

    val ackRead = AckReadPacket(messageId = "txt_101", senderId = "dev_2", recipientId = "dev_1")
    val parsedAckRead = NetworkPacket.fromJson(ackRead.toJson()) as? AckReadPacket
    assertNotNull(parsedAckRead)
    assertEquals("txt_101", parsedAckRead?.messageId)

    // 8. Call signaling packets (VoIP)
    val callOffer = CallOfferPacket(
      callId = "call_77",
      callerId = "dev_1",
      callerName = "خالد",
      calleeId = "dev_2",
      callerAudioPort = 10002
    )
    val parsedCallOffer = NetworkPacket.fromJson(callOffer.toJson()) as? CallOfferPacket
    assertNotNull(parsedCallOffer)
    assertEquals("call_77", parsedCallOffer?.callId)
    assertEquals(10002, parsedCallOffer?.callerAudioPort)

    val callAnswer = CallAnswerPacket(
      callId = "call_77",
      callerId = "dev_1",
      calleeId = "dev_2",
      accepted = true,
      calleeAudioPort = 10004
    )
    val parsedCallAnswer = NetworkPacket.fromJson(callAnswer.toJson()) as? CallAnswerPacket
    assertNotNull(parsedCallAnswer)
    assertTrue(parsedCallAnswer?.accepted == true)

    val callRinging = CallRingingPacket(callId = "call_77", callerId = "dev_1", calleeId = "dev_2")
    val parsedCallRinging = NetworkPacket.fromJson(callRinging.toJson()) as? CallRingingPacket
    assertNotNull(parsedCallRinging)

    val callEnd = CallEndPacket(callId = "call_77", senderId = "dev_1", targetId = "dev_2", reason = "NORMAL")
    val parsedCallEnd = NetworkPacket.fromJson(callEnd.toJson()) as? CallEndPacket
    assertNotNull(parsedCallEnd)
    assertEquals("NORMAL", parsedCallEnd?.reason)

    // 9. Mesh relay
    val meshRelay = MeshRelayPacket(
      meshPacketId = "mesh_1",
      originSenderId = "dev_1",
      originSenderName = "خالد",
      targetRecipientId = "dev_3",
      encryptedPayload = textPacket.toJson(),
      hopsRemaining = 3
    )
    val parsedMeshRelay = NetworkPacket.fromJson(meshRelay.toJson()) as? MeshRelayPacket
    assertNotNull(parsedMeshRelay)
    assertEquals("dev_3", parsedMeshRelay?.targetRecipientId)
    assertEquals(3, parsedMeshRelay?.hopsRemaining)

    // 10. App update packets (P2P APK sharing)
    val updateReq = AppUpdateRequestPacket(requesterDeviceId = "dev_1", requesterVersionCode = 1)
    val parsedUpdateReq = NetworkPacket.fromJson(updateReq.toJson()) as? AppUpdateRequestPacket
    assertNotNull(parsedUpdateReq)
    assertEquals(1, parsedUpdateReq?.requesterVersionCode)

    val updateResp = AppUpdateResponsePacket(
      providerDeviceId = "dev_2",
      versionCode = 2,
      versionName = "2.0.0",
      apkSizeBytes = 15000000L,
      isUpdateAvailable = true
    )
    val parsedUpdateResp = NetworkPacket.fromJson(updateResp.toJson()) as? AppUpdateResponsePacket
    assertNotNull(parsedUpdateResp)
    assertEquals(2, parsedUpdateResp?.versionCode)
    assertEquals("2.0.0", parsedUpdateResp?.versionName)
  }

  @Test
  fun `verify active call info model and transitions`() {
    val call = ActiveCallInfo(
      callId = "c_99",
      peerId = "peer_88",
      peerName = "عمر",
      peerIp = "192.168.1.120",
      peerTcpPort = 9999,
      peerAudioPort = 10002,
      isIncoming = false,
      status = CallStatus.OUTGOING_CALLING
    )
    assertEquals(CallStatus.OUTGOING_CALLING, call.status)
    assertEquals(9999, call.peerPort)
    assertFalse(call.isIncoming)

    val connected = call.copy(status = CallStatus.CONNECTED, durationSeconds = 45L)
    assertEquals(CallStatus.CONNECTED, connected.status)
    assertEquals(45L, connected.durationSeconds)

    val ended = connected.copy(status = CallStatus.ENDED)
    assertEquals(CallStatus.ENDED, ended.status)
  }

  @Test
  fun `verify encryption and decryption roundtrip`() {
    val plainMessage = "رسالة سرية للغاية مشفرة بالكامل عبر شبكة P2P المحلية"
    val encrypted = EncryptionManager.encrypt(plainMessage)
    assertTrue(EncryptionManager.isEncrypted(encrypted))

    val decrypted = EncryptionManager.decrypt(encrypted)
    assertEquals(plainMessage, decrypted)
  }
}

