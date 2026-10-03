package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.MainActivity

object LanNotificationHelper {
    const val CHANNEL_SERVICE_ID = "lan_service_channel"
    const val CHANNEL_MESSAGES_ID = "lan_messages_channel"
    const val CHANNEL_CALLS_ID = "lan_calls_channel"

    const val NOTIFICATION_ID_SERVICE = 1001
    const val NOTIFICATION_ID_MESSAGE = 1002
    const val NOTIFICATION_ID_CALL = 1003

    fun createNotificationChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // 1. Background Service Channel
            val serviceChannel = NotificationChannel(
                CHANNEL_SERVICE_ID,
                "خدمة الاتصال بالشبكة المحلية (Background Network)",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "تحافظ على اتصال التطبيق بالشبكة المحلية لاستقبال الرسائل والمكالمات في الخلفية"
                setShowBadge(false)
            }

            // 2. Messages Channel
            val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val audioAttributes = AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_INSTANT)
                .build()

            val messagesChannel = NotificationChannel(
                CHANNEL_MESSAGES_ID,
                "رسائل الدردشة (Chat Messages)",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "إشعارات الرسائل والصور الجديدة عبر الشبكة المحلية"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 200, 100, 200)
                setSound(soundUri, audioAttributes)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }

            // 3. Calls Channel
            val callRingtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            val callAudioAttributes = AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .build()

            val callsChannel = NotificationChannel(
                CHANNEL_CALLS_ID,
                "المكالمات الصوتية (Voice Calls)",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "إشعارات المكالمات الصوتية الواردة"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 1000, 500, 1000, 500, 1000)
                setSound(callRingtoneUri, callAudioAttributes)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }

            notificationManager.createNotificationChannel(serviceChannel)
            notificationManager.createNotificationChannel(messagesChannel)
            notificationManager.createNotificationChannel(callsChannel)
        }
    }

    fun buildServiceNotification(context: Context): Notification {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(context, CHANNEL_SERVICE_ID)
            .setContentTitle("LAN Chat متصل في الخلفية 🟢")
            .setContentText("جاهز لاستقبال الرسائل والمكالمات تلقائياً عبر الشبكة المحلية")
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    fun showMessageNotification(
        context: Context,
        senderName: String,
        messageText: String,
        conversationId: String,
        isPhoto: Boolean = false
    ) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("EXTRA_CONVERSATION_ID", conversationId)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            conversationId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val body = if (isPhoto) "📷 صورة جديدة: $messageText" else messageText

        val notification = NotificationCompat.Builder(context, CHANNEL_MESSAGES_ID)
            .setContentTitle(senderName)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setContentIntent(pendingIntent)
            .build()

        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(conversationId.hashCode(), notification)
    }

    fun showIncomingCallNotification(
        context: Context,
        callerName: String,
        callerId: String,
        callId: String
    ) {
        val fullScreenIntent = Intent(context, com.example.ui.screens.IncomingCallActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("EXTRA_INCOMING_CALL_ID", callId)
            putExtra("EXTRA_CALLER_ID", callerId)
            putExtra("EXTRA_CALLER_NAME", callerName)
        }
        val fullScreenPendingIntent = PendingIntent.getActivity(
            context,
            callId.hashCode(),
            fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_CALLS_ID)
            .setContentTitle("مكالمة صوتية واردة 📞")
            .setContentText("$callerName يتصل بك عبر الشبكة المحلية...")
            .setSmallIcon(android.R.drawable.stat_sys_phone_call)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setAutoCancel(true)
            .setOngoing(true)
            .setContentIntent(fullScreenPendingIntent)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .build()

        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID_CALL, notification)

        // Also launch activity directly if context can start activities
        try {
            com.example.ui.screens.IncomingCallActivity.launch(context, callerName, callerId, callId)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun cancelCallNotification(context: Context) {
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(NOTIFICATION_ID_CALL)
    }
}
