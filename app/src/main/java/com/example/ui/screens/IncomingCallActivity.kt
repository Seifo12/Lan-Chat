package com.example.ui.screens

import android.Manifest
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.data.call.LanAudioCallManager
import com.example.service.LanNotificationHelper
import com.example.ui.components.AvatarView
import com.example.ui.theme.LanChatTheme

class IncomingCallActivity : ComponentActivity() {

    companion object {
        @Volatile
        var activeCallManager: LanAudioCallManager? = null

        fun launch(context: Context, callerName: String, callerId: String, callId: String) {
            val intent = Intent(context, IncomingCallActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("EXTRA_CALLER_NAME", callerName)
                putExtra("EXTRA_CALLER_ID", callerId)
                putExtra("EXTRA_CALL_ID", callId)
            }
            try {
                context.startActivity(intent)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private var screenWakeLock: PowerManager.WakeLock? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setupLockscreenFlags()
        acquireScreenWakeLock()
        LanNotificationHelper.cancelCallNotification(this)

        val callerName = intent.getStringExtra("EXTRA_CALLER_NAME") ?: "مستخدم غير معروف"

        setContent {
            LanChatTheme(darkTheme = true) {
                val micPermissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestPermission()
                ) { isGranted ->
                    if (isGranted) {
                        acceptCallAndNavigate()
                    } else {
                        // في حال رفض إذن الصوت، نرفض المكالمة بأمان لمنع الانهيار
                        activeCallManager?.declineCall()
                        finish()
                    }
                }

                BackHandler {
                    // معالجة زر الرجوع: رفض المكالمة وتنظيف الشاشة فوراً
                    activeCallManager?.declineCall()
                    finish()
                }

                IncomingCallScreenContent(
                    callerName = callerName,
                    onAccept = {
                        val hasMic = ContextCompat.checkSelfPermission(
                            this@IncomingCallActivity,
                            Manifest.permission.RECORD_AUDIO
                        ) == PackageManager.PERMISSION_GRANTED

                        if (hasMic) {
                            acceptCallAndNavigate()
                        } else {
                            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                    onDecline = {
                        activeCallManager?.declineCall()
                        finish()
                    }
                )
            }
        }
    }

    /**
     * تشغيل الميكروفون بعد التأكد أن التطبيق يملك التركيز في الواجهة لتفادي قيود أندرويد 14+
     */
    private fun acceptCallAndNavigate() {
        val mainIntent = Intent(this@IncomingCallActivity, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        startActivity(mainIntent)
        activeCallManager?.acceptCall()
        finish()
    }

    private fun setupLockscreenFlags() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
            keyguardManager?.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                        WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }
    }

    private fun acquireScreenWakeLock() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            screenWakeLock = powerManager.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "BasataLanChat::IncomingCallWakeLock"
            ).apply {
                acquire(30000L)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        LanNotificationHelper.cancelCallNotification(this)
        try {
            if (screenWakeLock?.isHeld == true) {
                screenWakeLock?.release()
            }
        } catch (_: Exception) {}
    }
}

@Composable
fun IncomingCallScreenContent(
    callerName: String,
    onAccept: () -> Unit,
    onDecline: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "incoming_call_pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF0F172A),
                        Color(0xFF0B0E14),
                        Color(0xFF000000)
                    )
                )
            )
            .padding(24.dp)
            .testTag("incoming_call_activity_screen"),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(top = 50.dp)
            ) {
                Surface(
                    color = Color(0x3310B981),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.padding(bottom = 16.dp)
                ) {
                    Text(
                        text = "🔒 مكالمة صوتية مشفرة محلياً",
                        color = Color(0xFF34D399),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }

                Text(
                    text = callerName,
                    color = Color.White,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = "يرن الآن عبر الشبكة المباشرة...",
                    color = Color(0xFFA78BFA),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(240.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(190.dp)
                        .scale(pulseScale)
                        .clip(CircleShape)
                        .background(Color(0x3310B981))
                )
                Box(
                    modifier = Modifier
                        .size(160.dp)
                        .scale(pulseScale * 0.9f)
                        .clip(CircleShape)
                        .background(Color(0x4D8B5CF6))
                )
                AvatarView(
                    name = callerName,
                    avatarColorIndex = (callerName.hashCode() % 6 + 6) % 6,
                    size = 140.dp
                )
            }

            Row(
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 50.dp)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    FloatingActionButton(
                        onClick = onDecline,
                        containerColor = Color(0xFFEF4444),
                        contentColor = Color.White,
                        shape = CircleShape,
                        modifier = Modifier
                            .size(76.dp)
                            .testTag("incoming_call_decline_fab"),
                        elevation = FloatingActionButtonDefaults.elevation(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CallEnd,
                            contentDescription = "رفض المكالمة",
                            modifier = Modifier.size(36.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "رفض",
                        color = Color(0xFFFCA5A5),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    FloatingActionButton(
                        onClick = onAccept,
                        containerColor = Color(0xFF10B981),
                        contentColor = Color.White,
                        shape = CircleShape,
                        modifier = Modifier
                            .size(76.dp)
                            .testTag("incoming_call_accept_fab"),
                        elevation = FloatingActionButtonDefaults.elevation(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Call,
                            contentDescription = "قبول المكالمة",
                            modifier = Modifier.size(36.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "قبول",
                        color = Color(0xFF6EE7B7),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}