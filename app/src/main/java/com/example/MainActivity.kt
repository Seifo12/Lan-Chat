package com.example

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.ChatViewModel
import com.example.ui.screens.CallOverlayScreen
import com.example.ui.screens.ChatScreen
import com.example.ui.screens.ContactSecurityScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.theme.CanvasBackground
import com.example.ui.theme.LanChatTheme

enum class AppScreen {
    HOME,
    CHAT,
    SETTINGS,

    /** Phase 1.7b: the trust state and safety code for one contact. */
    CONTACT_SECURITY
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val viewModel: ChatViewModel = viewModel()
            val userProfile by viewModel.userProfile.collectAsState()

            val isDark = when (userProfile.themeMode) {
                "DARK" -> true
                "LIGHT" -> false
                else -> isSystemInDarkTheme()
            }

            LanChatTheme(darkTheme = isDark) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = com.example.ui.theme.AppTheme.colors.background
                ) {
                    MainAppNavHost(viewModel = viewModel)
                }
            }
        }
    }
}

@Composable
fun MainAppNavHost(
    viewModel: ChatViewModel = viewModel()
) {
    val context = LocalContext.current
    val activeContact by viewModel.activeContact.collectAsState()
    val activeGroup by viewModel.activeGroup.collectAsState()
    val showSettingsScreen by viewModel.showSettingsScreen.collectAsState()
    val contactSecurity by viewModel.contactSecurity.collectAsState()
    val failedMessageCount by viewModel.failedMessageCount.collectAsState()
    val currentCall by viewModel.currentCall.collectAsState()

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            viewModel.acceptIncomingCall()
        }
    }

    // Nearby's Bluetooth permissions were never requested at runtime, so a fresh
    // install could never start the mesh. Ask once on entry and retry afterwards.
    val meshPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        if (viewModel.hasMeshPermissions()) {
            viewModel.refreshDiscovery()
        }
    }

    LaunchedEffect(Unit) {
        val missing = viewModel.missingMeshPermissions()
        if (missing.isNotEmpty()) {
            meshPermissionLauncher.launch(missing)
        } else {
            viewModel.refreshDiscovery()
        }
    }

    val currentScreen = when {
        contactSecurity != null -> AppScreen.CONTACT_SECURITY
        showSettingsScreen -> AppScreen.SETTINGS
        activeContact != null || activeGroup != null -> AppScreen.CHAT
        else -> AppScreen.HOME
    }

    BackHandler(enabled = currentScreen != AppScreen.HOME) {
        when (currentScreen) {
            AppScreen.SETTINGS -> viewModel.closeSettings()
            AppScreen.CHAT -> {
                viewModel.selectContact(null)
                viewModel.selectGroup(null)
            }
            AppScreen.CONTACT_SECURITY -> viewModel.closeContactSecurity()
            AppScreen.HOME -> Unit
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = currentScreen,
            transitionSpec = {
                if (targetState != AppScreen.HOME) {
                    (slideInHorizontally { width -> width } + fadeIn()).togetherWith(
                        slideOutHorizontally { width -> -width } + fadeOut()
                    )
                } else {
                    (slideInHorizontally { width -> -width } + fadeIn()).togetherWith(
                        slideOutHorizontally { width -> width } + fadeOut()
                    )
                }
            },
            label = "nav_transition",
            modifier = Modifier
                .fillMaxSize()
                .background(com.example.ui.theme.AppTheme.colors.background)
        ) { screen ->
            when (screen) {
                AppScreen.CONTACT_SECURITY -> {
                    val target = contactSecurity ?: return@AnimatedContent
                    ContactSecurityScreen(
                        trust = target,
                        failedMessageCount = failedMessageCount,
                        onBack = { viewModel.closeContactSecurity() },
                        onMarkVerified = {
                            viewModel.markContactVerified(target.deviceId)
                        },
                        onAcceptNewKey = {
                            viewModel.acceptChangedKey(target.deviceId)
                        },
                        onResendFailed = {
                            viewModel.resendFailedMessages(target.deviceId)
                        },
                    )
                }
                AppScreen.SETTINGS -> {
                    SettingsScreen(
                        viewModel = viewModel,
                        onBack = { viewModel.closeSettings() }
                    )
                }
                AppScreen.CHAT -> {
                    ChatScreen(
                        contact = activeContact,
                        group = activeGroup,
                        viewModel = viewModel,
                        onBack = {
                            viewModel.selectContact(null)
                            viewModel.selectGroup(null)
                        }
                    )
                }
                AppScreen.HOME -> {
                    HomeScreen(
                        viewModel = viewModel,
                        onOpenChat = { selectedContact ->
                            viewModel.selectContact(selectedContact)
                        },
                        onOpenGroupChat = { selectedGroup ->
                            viewModel.selectGroup(selectedGroup)
                        },
                        onOpenSettings = {
                            viewModel.openSettings()
                        }
                    )
                }
            }
        }

        // Global Active Voice Call Overlay
        CallOverlayScreen(
            callInfo = currentCall,
            onAccept = {
                val hasMic = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED

                if (hasMic) {
                    viewModel.acceptIncomingCall()
                } else {
                    micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            },
            onDecline = { viewModel.declineIncomingCall() },
            onEnd = { viewModel.endCurrentCall() },
            onToggleMute = { viewModel.toggleCallMute() },
            onToggleSpeaker = { viewModel.toggleCallSpeaker() }
        )
    }
}
