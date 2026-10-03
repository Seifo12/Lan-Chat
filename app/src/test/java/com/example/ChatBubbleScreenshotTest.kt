package com.example

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.example.data.local.ChatMessageEntity
import com.example.data.local.MessageStatus
import com.example.ui.components.ChatBubble
import com.example.ui.theme.LanChatTheme
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class ChatBubbleScreenshotTest {

  @get:Rule val composeTestRule = createComposeRule()

  @Test
  fun chat_bubble_screenshot() {
    val sampleMessage = ChatMessageEntity(
        id = "test_1",
        conversationId = "peer_1",
        senderId = "me",
        senderName = "الجد أحمد",
        recipientId = "peer_1",
        text = "السلام عليكم، كيف حالكم؟",
        isFromMe = true,
        status = MessageStatus.READ
    )

    composeTestRule.setContent {
      LanChatTheme {
        ChatBubble(message = sampleMessage, onPhotoClick = {})
      }
    }

    composeTestRule.onRoot().captureRoboImage(filePath = "src/test/screenshots/chat_bubble.png")
  }
}
