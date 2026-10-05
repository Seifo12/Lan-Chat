package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.unit.sp
import com.example.data.local.ContactEntity
import com.example.data.local.GroupEntity
import com.example.data.local.MessageStatus
import com.lanchat.offline.messenger.R
import com.example.ui.ChatViewModel
import com.example.ui.ConversationUiItem
import com.example.ui.components.AvatarView
import com.example.ui.components.ContactEditDialog
import com.example.ui.components.CreateGroupDialog
import com.example.ui.components.DeveloperBadge
import com.example.ui.components.NetworkStatusCard
import com.example.ui.components.ProfileSetupDialog
import com.example.ui.theme.AppTheme
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

enum class ChatFilterType {
    ALL,
    UNREAD,
    ONLINE,
    GROUPS
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: ChatViewModel,
    onOpenChat: (ContactEntity) -> Unit,
    onOpenGroupChat: (GroupEntity) -> Unit,
    onOpenSettings: () -> Unit = { viewModel.openSettings() },
    modifier: Modifier = Modifier
) {
    val conversations by viewModel.conversations.collectAsState()
    val contacts by viewModel.contacts.collectAsState()
    val groups by viewModel.groups.collectAsState()
    val userProfile by viewModel.userProfile.collectAsState()
    val localIp by viewModel.localIpAddress.collectAsState()
    val networkDiagnostic by viewModel.networkDiagnostic.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val meshStatus by viewModel.meshStatus.collectAsState()
    val availableUpdates by viewModel.availableUpdates.collectAsState()
    val isSubnetScanning by viewModel.isSubnetScanning.collectAsState()
    val subnetScanProgress by viewModel.subnetScanProgress.collectAsState()
    val scanResultNotice by viewModel.scanResultNotice.collectAsState()
    val networkLogs by viewModel.networkLogs.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    // Main Navigation Bar Tab (0: Chats, 1: Mesh/Network, 2: Groups, 3: Calls)
    var selectedNavTab by remember { mutableIntStateOf(0) }

    // Search query & Filter pill for Chats
    var searchQuery by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf(ChatFilterType.ALL) }

    var showProfileDialog by remember { mutableStateOf(false) }
    var showCreateGroupDialog by remember { mutableStateOf(false) }
    var showNewChatSheet by remember { mutableStateOf(false) }
    var showNearbyPeersSheet by remember { mutableStateOf(false) }
    val callMicPermissionLauncher = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            viewModel.startPendingCallIfPermitted()
        } else {
            coroutineScope.launch {
                snackbarHostState.showSnackbar("يلزم إذن الميكروفون لإجراء المكالمات")
            }
        }
    }
    val discoveredPeers by viewModel.discoveredPeers.collectAsState()
    var showManualIpDialog by remember { mutableStateOf(false) }
    var contactToEdit by remember { mutableStateOf<ContactEntity?>(null) }
    var contactToDelete by remember { mutableStateOf<ContactEntity?>(null) }
    var conversationToDeleteId by remember { mutableStateOf<String?>(null) }

    val newChatSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val nearbyPeersSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val totalUnreadCount = remember(conversations) {
        conversations.sumOf { it.unreadCount }
    }

    LaunchedEffect(Unit) {
        viewModel.refreshNetworkAndPermissions()
    }

    LaunchedEffect(errorMessage) {
        if (errorMessage != null) {
            snackbarHostState.showSnackbar(errorMessage!!)
            viewModel.clearErrorMessage()
        }
    }

    // First time onboarding setup dialog
    if (!userProfile.isSetupCompleted || showProfileDialog) {
        ProfileSetupDialog(
            initialName = userProfile.displayName,
            initialAvatarPath = userProfile.avatarPath,
            initialColorIndex = userProfile.avatarColorIndex,
            initialIsDeveloper = userProfile.isDeveloper,
            deviceId = userProfile.deviceId,
            isFirstTime = !userProfile.isSetupCompleted,
            onDismiss = { showProfileDialog = false },
            onSave = { name, avatarPath, colorIndex, isDev ->
                viewModel.updateUserProfile(name, avatarPath, colorIndex, isDev)
                showProfileDialog = false
            }
        )
    }

    // Create Group Dialog
    if (showCreateGroupDialog) {
        CreateGroupDialog(
            onDismiss = { showCreateGroupDialog = false },
            onCreate = { name, description, colorIndex ->
                viewModel.createGroup(name, description, colorIndex)
                showCreateGroupDialog = false
            }
        )
    }

    // Contact Customization Dialog
    if (contactToEdit != null) {
        ContactEditDialog(
            contact = contactToEdit!!,
            onDismiss = { contactToEdit = null },
            onSave = { nickname, avatarPath ->
                viewModel.updateContactCustomization(contactToEdit!!.deviceId, nickname, avatarPath)
                contactToEdit = null
            }
        )
    }

    // Delete Contact locally dialog
    if (contactToDelete != null) {
        AlertDialog(
            onDismissRequest = { contactToDelete = null },
            title = { Text("حذف هذا الحساب من عندك؟", fontWeight = FontWeight.Bold, color = AppTheme.colors.textPrimary) },
            text = {
                Text(
                    "سيتم حذف جهة الاتصال '${contactToDelete!!.customNickname ?: contactToDelete!!.displayName}' ومحادثتها من جهازك فقط.",
                    color = AppTheme.colors.textSecondary,
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteContactLocally(contactToDelete!!.deviceId)
                        contactToDelete = null
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFEF4444))
                ) {
                    Text("حذف من عندي", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { contactToDelete = null }) {
                    Text("إلغاء", color = AppTheme.colors.textSecondary)
                }
            },
            containerColor = AppTheme.colors.dialogBackground
        )
    }

    // Clear conversation dialog
    if (conversationToDeleteId != null) {
        AlertDialog(
            onDismissRequest = { conversationToDeleteId = null },
            title = { Text("مسح المحادثة؟", fontWeight = FontWeight.Bold, color = AppTheme.colors.textPrimary) },
            text = {
                Text(
                    "هل أنت متأكد من مسح جميع الرسائل في هذه المحادثة من جهازك؟",
                    color = AppTheme.colors.textSecondary,
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearConversationLocally(conversationToDeleteId!!)
                        conversationToDeleteId = null
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFEF4444))
                ) {
                    Text("مسح المحادثة", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { conversationToDeleteId = null }) {
                    Text("إلغاء", color = AppTheme.colors.textSecondary)
                }
            },
            containerColor = AppTheme.colors.dialogBackground
        )
    }

    // New Chat Bottom Sheet (WhatsApp style contact selector)
    if (showNewChatSheet) {
        ModalBottomSheet(
            onDismissRequest = { showNewChatSheet = false },
            sheetState = newChatSheetState,
            containerColor = AppTheme.colors.dialogBackground,
            contentColor = AppTheme.colors.textPrimary
        ) {
            NewChatBottomSheetContent(
                contacts = contacts,
                onSelectContact = { contact ->
                    showNewChatSheet = false
                    onOpenChat(contact)
                },
                onShowNearby = {
                    showNewChatSheet = false
                    showNearbyPeersSheet = true
                },
                nearbyCount = discoveredPeers.size,
                onCreateGroup = {
                    showNewChatSheet = false
                    showCreateGroupDialog = true
                },
                onRefreshScan = {
                    viewModel.refreshNetworkAndPermissions()
                    viewModel.refreshDiscovery()
                    coroutineScope.launch {
                        snackbarHostState.showSnackbar("جاري فحص الأجهزة في الشبكة المحلية...")
                    }
                }
            )
        }
    }

    if (showNearbyPeersSheet) {
        ModalBottomSheet(
            onDismissRequest = { showNearbyPeersSheet = false },
            sheetState = nearbyPeersSheetState,
            containerColor = AppTheme.colors.dialogBackground,
            contentColor = AppTheme.colors.textPrimary
        ) {
            NearbyPeersSheet(
                peers = discoveredPeers,
                onOpenChat = { deviceId ->
                    showNearbyPeersSheet = false
                    viewModel.startChatWithDiscoveredPeer(deviceId)
                },
                onRefresh = {
                    viewModel.refreshNetworkAndPermissions()
                    viewModel.refreshDiscovery()
                },
                onDismiss = { showNearbyPeersSheet = false }
            )
        }
    }

    if (showManualIpDialog) {
        ManualIpConnectDialog(
            currentSubnet = com.example.data.network.NetworkUtils.getSubnetPrefix() ?: (localIp?.substringBeforeLast(".") ?: "192.168.43"),
            onDismiss = { showManualIpDialog = false },
            onConnect = { ip ->
                showManualIpDialog = false
                viewModel.connectToManualIp(ip) { success, msg ->
                    coroutineScope.launch {
                        snackbarHostState.showSnackbar(msg)
                    }
                }
            }
        )
    }

    Surface(
        color = AppTheme.colors.background,
        modifier = modifier.fillMaxSize().testTag("home_screen")
    ) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            containerColor = AppTheme.colors.background,
            modifier = Modifier.fillMaxSize().statusBarsPadding(),
            floatingActionButton = {
                if (selectedNavTab == 0 || selectedNavTab == 1) {
                    FloatingActionButton(
                        onClick = {
                            if (selectedNavTab == 1) {
                                showCreateGroupDialog = true
                            } else {
                                showNewChatSheet = true
                            }
                        },
                        containerColor = AppTheme.colors.primary,
                        contentColor = Color.White,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .padding(bottom = 8.dp)
                            .testTag("start_chat_fab")
                    ) {
                        Icon(
                            imageVector = if (selectedNavTab == 1) Icons.Default.Add else Icons.Default.Chat,
                            contentDescription = "بدء محادثة جديدة",
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            },
            bottomBar = {
                Surface(
                    color = AppTheme.colors.surface,
                    shadowElevation = 8.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.navigationBars)
                ) {
                    NavigationBar(
                        containerColor = AppTheme.colors.surface,
                        contentColor = AppTheme.colors.primary,
                        tonalElevation = 0.dp
                    ) {
                        val navItemColors = NavigationBarItemDefaults.colors(
                            selectedIconColor = AppTheme.colors.primary,
                            selectedTextColor = AppTheme.colors.primary,
                            indicatorColor = AppTheme.colors.primaryContainer,
                            unselectedIconColor = AppTheme.colors.textSecondary,
                            unselectedTextColor = AppTheme.colors.textSecondary
                        )

                        // Chats Tab
                        NavigationBarItem(
                            selected = selectedNavTab == 0,
                            onClick = { selectedNavTab = 0 },
                            icon = {
                                if (totalUnreadCount > 0) {
                                    BadgedBox(
                                        badge = {
                                            Badge(
                                                containerColor = AppTheme.colors.primary,
                                                contentColor = Color.White
                                            ) {
                                                Text(text = "$totalUnreadCount", fontSize = 11.sp)
                                            }
                                        }
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Chat,
                                            contentDescription = "المحادثات"
                                        )
                                    }
                                } else {
                                    Icon(
                                        imageVector = Icons.Default.Chat,
                                        contentDescription = "المحادثات"
                                    )
                                }
                            },
                            label = {
                                Text(
                                    text = "المحادثات",
                                    fontSize = 12.sp,
                                    fontWeight = if (selectedNavTab == 0) FontWeight.Bold else FontWeight.Normal
                                )
                            },
                            colors = navItemColors
                        )

                        // Groups Tab
                        NavigationBarItem(
                            selected = selectedNavTab == 1,
                            onClick = { selectedNavTab = 1 },
                            icon = {
                                BadgedBox(
                                    badge = {
                                        if (groups.isNotEmpty()) {
                                            Badge(
                                                containerColor = AppTheme.colors.surfaceElevated,
                                                contentColor = AppTheme.colors.textSecondary
                                            ) {
                                                Text(text = "${groups.size}", fontSize = 10.sp)
                                            }
                                        }
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Groups,
                                        contentDescription = "المجموعات"
                                    )
                                }
                            },
                            label = {
                                Text(
                                    text = "المجموعات",
                                    fontSize = 12.sp,
                                    fontWeight = if (selectedNavTab == 1) FontWeight.Bold else FontWeight.Normal
                                )
                            },
                            colors = navItemColors
                        )

                        // Calls Tab
                        NavigationBarItem(
                            selected = selectedNavTab == 2,
                            onClick = { selectedNavTab = 2 },
                            icon = {
                                Icon(
                                    imageVector = Icons.Default.Call,
                                    contentDescription = "المكالمات"
                                )
                            },
                            label = {
                                Text(
                                    text = "المكالمات",
                                    fontSize = 12.sp,
                                    fontWeight = if (selectedNavTab == 2) FontWeight.Bold else FontWeight.Normal
                                )
                            },
                            colors = navItemColors
                        )
                    }
                }
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .background(AppTheme.colors.background)
            ) {
                // Top App Header
                LanChatTopBar(
                    userName = userProfile.displayName,
                    userAvatarPath = userProfile.avatarPath,
                    userColorIndex = userProfile.avatarColorIndex,
                    isDeveloper = userProfile.isDeveloper,
                    onProfileClick = { showProfileDialog = true },
                    onSettingsClick = onOpenSettings,
                    onRefreshScan = {
                        viewModel.refreshNetworkAndPermissions()
                        viewModel.refreshDiscovery()
                        coroutineScope.launch {
                            snackbarHostState.showSnackbar("جاري فحص وتحديث الشبكة المحلية...")
                        }
                    }
                )

                val meshIssue = meshStatus.unavailableReason
                if (userProfile.isMeshModeEnabled && meshIssue != null) {
                    Surface(
                        color = Color(0xFF2B1414),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 4.dp)
                            .border(1.dp, Color(0xFF7F1D1D), RoundedCornerShape(12.dp))
                            .clickable { onOpenSettings() }
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = null,
                                tint = Color(0xFFFCA5A5),
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = "MESH غير متاح",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFFECACA)
                                )
                                Text(
                                    text = meshIssue,
                                    fontSize = 12.sp,
                                    color = Color(0xFFFCA5A5)
                                )
                                Text(
                                    text = "اكتشاف نفس الشبكة يعمل. اضغط للإصلاح.",
                                    fontSize = 12.sp,
                                    color = Color(0xFFFCA5A5)
                                )
                            }
                        }
                    }
                }

                when (selectedNavTab) {
                    0 -> {
                        // CHATS TAB (WhatsApp Style Layout)
                        ChatsTabContent(
                            conversations = conversations,
                            contacts = contacts,
                            searchQuery = searchQuery,
                            onSearchQueryChange = { searchQuery = it },
                            selectedFilter = selectedFilter,
                            onFilterChange = { selectedFilter = it },
                            onOpenConversation = { conv ->
                                if (conv.isGroup && conv.group != null) {
                                    onOpenGroupChat(conv.group)
                                } else if (conv.contact != null) {
                                    onOpenChat(conv.contact)
                                }
                            },
                            onVoiceCall = { contact ->
                                // الـ ViewModel هو اللي بيتحكم في البوطة: لو الإذن
                                // مش موجود بيحفظ الطلب كـ pending ويطلب الإذن، وبعد
                                // الموافقة بيبدأ المكالمة أوتوماتيك. لازم نندهوله
                                // في الحالتين، وإلا الطلب بيضيع.
                                viewModel.startVoiceCall(contact)
                            },
                            onEditContact = { contact ->
                                contactToEdit = contact
                            },
                            onDeleteContact = { contact ->
                                contactToDelete = contact
                            },
                            onClearConversation = { convId ->
                                conversationToDeleteId = convId
                            },
                            onRetrySend = { messageId -> viewModel.retryFailedMessage(messageId) },
                            onStartNewChat = { showNewChatSheet = true },
                            onScanClick = {
                                viewModel.refreshNetworkAndPermissions()
                                viewModel.refreshDiscovery()
                            }
                        )
                    }

                    1 -> {
                        // GROUPS TAB
                        GroupsTabContent(
                            groups = groups,
                            onCreateGroup = { showCreateGroupDialog = true },
                            onOpenGroup = onOpenGroupChat
                        )
                    }

                    2 -> {
                        // CALLS TAB
                        CallsTabContent(
                            contacts = contacts,
                            onVoiceCall = { contact ->
                                viewModel.startVoiceCall(contact)
                            },
                            onScanClick = {
                                viewModel.refreshNetworkAndPermissions()
                                viewModel.refreshDiscovery()
                            }
                        )
                    }
                }
            }
        }
    }
}

// -------------------------------------------------------------
// Top Header Bar
// -------------------------------------------------------------
@Composable
private fun LanChatTopBar(
    userName: String,
    userAvatarPath: String?,
    userColorIndex: Int,
    isDeveloper: Boolean,
    onProfileClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onRefreshScan: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }

    Surface(
        color = AppTheme.colors.surface,
        shadowElevation = 2.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // App Brand Mark & Title
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(AppTheme.colors.primary),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = "LAN Chat Logo",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.width(10.dp))

                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "LAN Chat",
                            fontSize = 19.sp,
                            fontWeight = FontWeight.Bold,
                            color = AppTheme.colors.textPrimary,
                            modifier = Modifier.testTag("app_title_text")
                        )
                        if (isDeveloper) {
                            Spacer(modifier = Modifier.width(6.dp))
                            DeveloperBadge(isSmall = true)
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF10B981))
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "شبكة محلية مباشرة",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Normal,
                            color = AppTheme.colors.textSecondary
                        )
                    }
                }
            }

            // Top action icons
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Refresh Radar Button
                IconButton(
                    onClick = onRefreshScan,
                    modifier = Modifier.size(38.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "تحديث الشبكة",
                        tint = AppTheme.colors.textSecondary,
                        modifier = Modifier.size(22.dp)
                    )
                }

                // Profile Avatar Button
                Box(
                    modifier = Modifier
                        .clickable { onProfileClick() }
                        .padding(horizontal = 4.dp)
                ) {
                    AvatarView(
                        name = userName,
                        avatarPath = userAvatarPath,
                        avatarColorIndex = userColorIndex,
                        showOnlineBadge = false,
                        size = 34.dp
                    )
                }

                // Overflow Menu (⋮)
                Box {
                    IconButton(
                        onClick = { showMenu = true },
                        modifier = Modifier.size(38.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "الخيارات",
                            tint = AppTheme.colors.textSecondary,
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false },
                        modifier = Modifier.background(AppTheme.colors.dialogBackground)
                    ) {
                        DropdownMenuItem(
                            text = { Text("الملف الشخصي", color = AppTheme.colors.textPrimary) },
                            leadingIcon = {
                                Icon(Icons.Default.Person, contentDescription = null, tint = AppTheme.colors.primary)
                            },
                            onClick = {
                                showMenu = false
                                onProfileClick()
                            }
                        )

                        DropdownMenuItem(
                            text = { Text("الإعدادات", color = AppTheme.colors.textPrimary) },
                            leadingIcon = {
                                Icon(Icons.Default.Settings, contentDescription = null, tint = AppTheme.colors.primary)
                            },
                            onClick = {
                                showMenu = false
                                onSettingsClick()
                            }
                        )

                        DropdownMenuItem(
                            text = { Text("فحص الأجهزة في الشبكة", color = AppTheme.colors.textPrimary) },
                            leadingIcon = {
                                Icon(Icons.Default.Refresh, contentDescription = null, tint = AppTheme.colors.primary)
                            },
                            onClick = {
                                showMenu = false
                                onRefreshScan()
                            }
                        )
                    }
                }
            }
        }
    }
}

// -------------------------------------------------------------
// CHATS TAB (Unified Conversation List with WhatsApp Aesthetics)
// -------------------------------------------------------------
@Composable
private fun ChatsTabContent(
    conversations: List<ConversationUiItem>,
    contacts: List<ContactEntity>,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    selectedFilter: ChatFilterType,
    onFilterChange: (ChatFilterType) -> Unit,
    onOpenConversation: (ConversationUiItem) -> Unit,
    onVoiceCall: (ContactEntity) -> Unit,
    onEditContact: (ContactEntity) -> Unit,
    onDeleteContact: (ContactEntity) -> Unit,
    onClearConversation: (String) -> Unit,
    onRetrySend: (String) -> Unit,
    onStartNewChat: () -> Unit,
    onScanClick: () -> Unit
) {
    // Filter conversations based on search and selected filter pill
    val filteredList = remember(conversations, searchQuery, selectedFilter) {
        conversations.filter { item ->
            // Filter pill
            val matchesFilter = when (selectedFilter) {
                ChatFilterType.ALL -> true
                ChatFilterType.UNREAD -> item.unreadCount > 0
                ChatFilterType.ONLINE -> item.isOnline
                ChatFilterType.GROUPS -> item.isGroup
            }

            // Search query
            val matchesSearch = if (searchQuery.isBlank()) {
                true
            } else {
                item.title.contains(searchQuery, ignoreCase = true) ||
                        item.subtitle.contains(searchQuery, ignoreCase = true) ||
                        (item.contact?.ipAddress?.contains(searchQuery) == true)
            }

            matchesFilter && matchesSearch
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Modern Search Bar
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 8.dp)
        ) {
            Surface(
                color = AppTheme.colors.surfaceVariant,
                shape = RoundedCornerShape(14.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, AppTheme.colors.border),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "بحث",
                        tint = AppTheme.colors.textSecondary,
                        modifier = Modifier.size(20.dp)
                    )

                    Spacer(modifier = Modifier.width(10.dp))

                    BasicTextField(
                        value = searchQuery,
                        onValueChange = onSearchQueryChange,
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        textStyle = TextStyle(
                            color = AppTheme.colors.textPrimary,
                            fontSize = 14.sp
                        ),
                        cursorBrush = SolidColor(AppTheme.colors.primary),
                        decorationBox = { innerTextField ->
                            if (searchQuery.isEmpty()) {
                                Text(
                                    text = "بحث في المحادثات أو الأجهزة...",
                                    color = AppTheme.colors.textSecondary,
                                    fontSize = 14.sp
                                )
                            }
                            innerTextField()
                        }
                    )

                    if (searchQuery.isNotEmpty()) {
                        IconButton(
                            onClick = { onSearchQueryChange("") },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "مسح",
                                tint = AppTheme.colors.textSecondary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }

        // Horizontal Category Filter Chips
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            LanFilterChip(
                label = "الكل",
                isSelected = selectedFilter == ChatFilterType.ALL,
                onClick = { onFilterChange(ChatFilterType.ALL) }
            )

            LanFilterChip(
                label = "غير مقروءة",
                isSelected = selectedFilter == ChatFilterType.UNREAD,
                onClick = { onFilterChange(ChatFilterType.UNREAD) }
            )

            LanFilterChip(
                label = "المتصلون الآن 🌐",
                isSelected = selectedFilter == ChatFilterType.ONLINE,
                onClick = { onFilterChange(ChatFilterType.ONLINE) }
            )

            LanFilterChip(
                label = "المجموعات",
                isSelected = selectedFilter == ChatFilterType.GROUPS,
                onClick = { onFilterChange(ChatFilterType.GROUPS) }
            )

            // "+" Quick action to start new chat or group
            Surface(
                color = AppTheme.colors.surfaceVariant,
                shape = RoundedCornerShape(100.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, AppTheme.colors.border),
                modifier = Modifier
                    .height(34.dp)
                    .clickable { onStartNewChat() }
            ) {
                Box(
                    modifier = Modifier.padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "جديد",
                        tint = AppTheme.colors.textSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Main Conversation List or Empty State
        if (filteredList.isEmpty()) {
            if (conversations.isEmpty()) {
                if (contacts.isNotEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 14.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = "الأجهزة المتاحة حولك الآن (${contacts.size})",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = AppTheme.colors.textPrimary
                        )
                        Text(
                            text = "اضغط على أي هاتف لبدء المحادثة أو الاتصال فوراً بدون إنترنت:",
                            fontSize = 12.sp,
                            color = AppTheme.colors.textSecondary
                        )
                        Spacer(modifier = Modifier.height(10.dp))

                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(bottom = 80.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(contacts, key = { it.deviceId }) { contact ->
                                Card(
                                    shape = RoundedCornerShape(14.dp),
                                    colors = CardDefaults.cardColors(containerColor = AppTheme.colors.cardBackground),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, AppTheme.colors.border),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            onOpenConversation(
                                                ConversationUiItem(
                                                    id = contact.deviceId,
                                                    title = contact.customNickname ?: contact.displayName,
                                                    subtitle = "متاح للمحادثة",
                                                    isGroup = false,
                                                    isOnline = contact.isOnline,
                                                    isMeshPeer = contact.isMeshPeer,
                                                    avatarPath = contact.avatarPath,
                                                    avatarColorIndex = contact.avatarColorIndex,
                                                    lastMessage = null,
                                                    unreadCount = 0,
                                                    lastTimestamp = contact.lastSeen,
                                                    contact = contact,
                                                    group = null
                                                )
                                            )
                                        }
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        AvatarView(
                                            name = contact.customNickname ?: contact.displayName,
                                            avatarPath = contact.avatarPath,
                                            avatarColorIndex = contact.avatarColorIndex,
                                            isOnline = contact.isOnline,
                                            size = 46.dp
                                        )

                                        Spacer(modifier = Modifier.width(12.dp))

                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = contact.customNickname ?: contact.displayName,
                                                fontSize = 15.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = AppTheme.colors.textPrimary
                                            )
                                            Text(
                                                text = "متصل وجاهز للتواصل 🟢",
                                                fontSize = 12.sp,
                                                color = Color(0xFF10B981)
                                            )
                                        }

                                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            IconButton(
                                                onClick = { onVoiceCall(contact) },
                                                modifier = Modifier
                                                    .size(38.dp)
                                                    .clip(CircleShape)
                                                    .background(AppTheme.colors.primaryContainer)
                                            ) {
                                                Icon(
                                                    Icons.Default.Call,
                                                    contentDescription = "اتصال",
                                                    tint = AppTheme.colors.primary,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }

                                            Button(
                                                onClick = {
                                                    onOpenConversation(
                                                        ConversationUiItem(
                                                            id = contact.deviceId,
                                                            title = contact.customNickname ?: contact.displayName,
                                                            subtitle = "متاح للمحادثة",
                                                            isGroup = false,
                                                            isOnline = contact.isOnline,
                                                            isMeshPeer = contact.isMeshPeer,
                                                            avatarPath = contact.avatarPath,
                                                            avatarColorIndex = contact.avatarColorIndex,
                                                            lastMessage = null,
                                                            unreadCount = 0,
                                                            lastTimestamp = contact.lastSeen,
                                                            contact = contact,
                                                            group = null
                                                        )
                                                    )
                                                },
                                                colors = ButtonDefaults.buttonColors(containerColor = AppTheme.colors.primary),
                                                shape = RoundedCornerShape(8.dp),
                                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                                modifier = Modifier.height(38.dp)
                                            ) {
                                                Text("محادثة", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    // Clean Zero Mock-Data Empty State
                    CleanEmptyStateView(
                        onScanClick = onScanClick,
                        onStartChatClick = onStartNewChat
                    )
                }
            } else {
                // No search/filter match
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "لا توجد محادثات مطابقة للتصفية أو البحث",
                        color = AppTheme.colors.textSecondary,
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 80.dp)
            ) {
                items(filteredList, key = { it.id }) { item ->
                    LanConversationItem(
                        item = item,
                        onClick = { onOpenConversation(item) },
                        onVoiceCall = {
                            if (item.contact != null) onVoiceCall(item.contact)
                        },
                        onEditContact = {
                            if (item.contact != null) onEditContact(item.contact)
                        },
                        onDeleteContact = {
                            if (item.contact != null) onDeleteContact(item.contact)
                        },
                        onRetrySend = { messageId -> onRetrySend(messageId) },
                        onClearConversation = {
                            onClearConversation(item.id)
                        }
                    )
                    HorizontalDivider(
                        color = AppTheme.colors.borderLight,
                        thickness = 0.5.dp,
                        modifier = Modifier.padding(start = 74.dp)
                    )
                }
            }
        }
    }
}

// -------------------------------------------------------------
// Filter Pill Chip
// -------------------------------------------------------------
@Composable
private fun LanFilterChip(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        color = if (isSelected) AppTheme.colors.primary else AppTheme.colors.surfaceVariant,
        shape = RoundedCornerShape(100.dp),
        border = if (isSelected) null else androidx.compose.foundation.BorderStroke(1.dp, AppTheme.colors.border),
        modifier = Modifier
            .height(34.dp)
            .clickable { onClick() }
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                fontSize = 13.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                color = if (isSelected) Color.White else AppTheme.colors.textSecondary
            )
        }
    }
}

// -------------------------------------------------------------
// Conversation List Item
// -------------------------------------------------------------
@Composable
private fun LanConversationItem(
    item: ConversationUiItem,
    onClick: () -> Unit,
    onVoiceCall: () -> Unit,
    onEditContact: () -> Unit,
    onDeleteContact: () -> Unit,
    onClearConversation: () -> Unit,
    onRetrySend: (String) -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Avatar View with Online Indicator
        Box {
            if (item.isGroup) {
                Box(
                    modifier = Modifier
                        .size(50.dp)
                        .clip(CircleShape)
                        .background(AppTheme.colors.primaryContainer)
                        .border(1.dp, AppTheme.colors.primary.copy(alpha = 0.4f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Groups,
                        contentDescription = null,
                        tint = AppTheme.colors.primary,
                        modifier = Modifier.size(26.dp)
                    )
                }
            } else {
                AvatarView(
                    name = item.title,
                    avatarPath = item.avatarPath,
                    avatarColorIndex = item.avatarColorIndex,
                    isOnline = item.isOnline,
                    size = 50.dp
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        // Center: Name and Subtitle
        Column(
            modifier = Modifier.weight(1f)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Name
                Text(
                    text = item.title,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = AppTheme.colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )

                if (item.lastMessageWasMesh) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.Default.Wifi,
                        contentDescription = "عبر MESH",
                        tint = Color(0xFF10B981),
                        modifier = Modifier.size(14.dp)
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Timestamp
                val timeStr = formatChatTimestamp(item.lastTimestamp)
                if (timeStr.isNotBlank()) {
                    Text(
                        text = timeStr,
                        fontSize = 12.sp,
                        color = if (item.unreadCount > 0) AppTheme.colors.primary else AppTheme.colors.textSecondary,
                        fontWeight = if (item.unreadCount > 0) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }

            Spacer(modifier = Modifier.height(3.dp))

            // Subtitle Row (Message preview + Delivery ticks or Unread badge)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Delivery Tick Status for last sent message
                    val lastMsg = item.lastMessage
                    if (lastMsg != null && lastMsg.isFromMe) {
                        when (lastMsg.status) {
                            MessageStatus.SENDING -> {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = stringResource(R.string.status_sending),
                                    tint = AppTheme.colors.textSecondary,
                                    modifier = Modifier.size(13.dp)
                                )
                            }
                            // Step 1.1: a queued message is waiting for the peer
                            // to come back, which is normal here, so it reads as
                            // "waiting" rather than as an error.
                            MessageStatus.QUEUED -> {
                                Icon(
                                    imageVector = Icons.Default.Schedule,
                                    contentDescription = stringResource(R.string.status_queued),
                                    tint = AppTheme.colors.textSecondary,
                                    modifier = Modifier.size(13.dp)
                                )
                            }
                            // A failed message is terminal and offers a retry.
                            MessageStatus.FAILED -> {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    modifier = Modifier.clickable { onRetrySend(lastMsg.id) },
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.ErrorOutline,
                                        contentDescription = stringResource(R.string.status_failed),
                                        tint = AppTheme.colors.error,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Text(
                                        text = stringResource(R.string.action_retry_send),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = AppTheme.colors.error,
                                    )
                                }
                            }
                            MessageStatus.SENT -> {
                                Icon(
                                    imageVector = Icons.Default.Done,
                                    contentDescription = stringResource(R.string.status_sent),
                                    tint = AppTheme.colors.textSecondary,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                            MessageStatus.DELIVERED -> {
                                Icon(
                                    imageVector = Icons.Default.DoneAll,
                                    contentDescription = stringResource(R.string.status_delivered),
                                    tint = AppTheme.colors.textSecondary,
                                    modifier = Modifier.size(15.dp)
                                )
                            }
                            MessageStatus.READ -> {
                                Icon(
                                    imageVector = Icons.Default.DoneAll,
                                    contentDescription = stringResource(R.string.status_read),
                                    tint = AppTheme.colors.primaryLight,
                                    modifier = Modifier.size(15.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                    }

                    // Media Type Icon Preview
                    if (lastMsg != null) {
                        when {
                            lastMsg.isVoice -> {
                                Icon(Icons.Default.Mic, contentDescription = null, tint = AppTheme.colors.textSecondary, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(3.dp))
                            }
                            lastMsg.isPhoto -> {
                                Icon(Icons.Default.Image, contentDescription = null, tint = AppTheme.colors.textSecondary, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(3.dp))
                            }
                            lastMsg.isVideo -> {
                                Icon(Icons.Default.Movie, contentDescription = null, tint = AppTheme.colors.textSecondary, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(3.dp))
                            }
                            lastMsg.isFile -> {
                                Icon(Icons.Default.Description, contentDescription = null, tint = AppTheme.colors.textSecondary, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(3.dp))
                            }
                        }
                    }

                    Text(
                        text = item.subtitle,
                        fontSize = 13.sp,
                        color = AppTheme.colors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Unread Count Badge
                if (item.unreadCount > 0) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .clip(CircleShape)
                            .background(AppTheme.colors.primary),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (item.unreadCount > 99) "99+" else "${item.unreadCount}",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }
            }
        }

        // Three dots action for conversation item
        Box {
            IconButton(
                onClick = { showMenu = true },
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = "خيارات",
                    tint = AppTheme.colors.textSecondary,
                    modifier = Modifier.size(18.dp)
                )
            }

            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false },
                modifier = Modifier.background(AppTheme.colors.dialogBackground)
            ) {
                if (!item.isGroup && item.contact != null) {
                    DropdownMenuItem(
                        text = { Text("اتصال صوتي", color = AppTheme.colors.textPrimary) },
                        leadingIcon = {
                            Icon(Icons.Default.Call, contentDescription = null, tint = AppTheme.colors.primary)
                        },
                        onClick = {
                            showMenu = false
                            onVoiceCall()
                        }
                    )

                    DropdownMenuItem(
                        text = { Text("تعديل جهة الاتصال", color = AppTheme.colors.textPrimary) },
                        leadingIcon = {
                            Icon(Icons.Default.Edit, contentDescription = null, tint = AppTheme.colors.primary)
                        },
                        onClick = {
                            showMenu = false
                            onEditContact()
                        }
                    )

                    DropdownMenuItem(
                        text = { Text("حذف الحساب من عندي", color = Color(0xFFEF4444)) },
                        leadingIcon = {
                            Icon(Icons.Default.Delete, contentDescription = null, tint = Color(0xFFEF4444))
                        },
                        onClick = {
                            showMenu = false
                            onDeleteContact()
                        }
                    )
                }

                DropdownMenuItem(
                    text = { Text("مسح المحادثة", color = Color(0xFFEF4444)) },
                    leadingIcon = {
                        Icon(Icons.Default.Delete, contentDescription = null, tint = Color(0xFFEF4444))
                    },
                    onClick = {
                        showMenu = false
                        onClearConversation()
                    }
                )
            }
        }
    }
}

// -------------------------------------------------------------
// Clean Zero-Mock-Data Empty State View
// -------------------------------------------------------------
@Composable
private fun CleanEmptyStateView(
    onScanClick: () -> Unit,
    onStartChatClick: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse_radar")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(90.dp)
                .scale(pulseScale)
                .clip(CircleShape)
                .background(AppTheme.colors.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Wifi,
                contentDescription = null,
                tint = AppTheme.colors.primary,
                modifier = Modifier.size(44.dp)
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        Text(
            text = "لا توجد محادثات بعد",
            fontSize = 19.sp,
            fontWeight = FontWeight.Bold,
            color = AppTheme.colors.textPrimary
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "الأجهزة المتصلة على نفس شبكة Wi-Fi أو البلوتوث تظهر هنا تلقائياً بدون إنترنت.",
            fontSize = 13.sp,
            color = AppTheme.colors.textSecondary,
            textAlign = TextAlign.Center,
            lineHeight = 19.sp
        )

        Spacer(modifier = Modifier.height(24.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = onScanClick,
                colors = ButtonDefaults.buttonColors(containerColor = AppTheme.colors.primary),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.height(44.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "بحث",
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "فحص الشبكة",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }

            Button(
                onClick = onStartChatClick,
                colors = ButtonDefaults.buttonColors(
                    containerColor = AppTheme.colors.surfaceVariant,
                    contentColor = AppTheme.colors.textPrimary
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.height(44.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Chat,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "الأجهزة المتصلة",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

// -------------------------------------------------------------
// New Chat Bottom Sheet (WhatsApp style contact selector)
// -------------------------------------------------------------
@Composable
private fun NewChatBottomSheetContent(
    contacts: List<ContactEntity>,
    onSelectContact: (ContactEntity) -> Unit,
    onShowNearby: () -> Unit,
    nearbyCount: Int,
    onCreateGroup: () -> Unit,
    onRefreshScan: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 32.dp)
    ) {
        Surface(
            color = Color(0xFF0F291E),
            shape = RoundedCornerShape(12.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1B4D36)),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .clickable { onShowNearby() }
        ) {
            Row(
                modifier = Modifier.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Wifi,
                    contentDescription = null,
                    tint = Color(0xFF10B981),
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (nearbyCount > 0) "قريبين الآن ($nearbyCount)" else "قريبين الآن",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = AppTheme.colors.textPrimary
                    )
                    Text(
                        text = "شوف الأجهزة القريبة وابدأ محادثة مع اللي تعرفهم",
                        fontSize = 12.sp,
                        color = AppTheme.colors.textSecondary
                    )
                }
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                    contentDescription = null,
                    tint = AppTheme.colors.textSecondary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "بدء محادثة جديدة",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = AppTheme.colors.textPrimary
            )

            IconButton(onClick = onRefreshScan, modifier = Modifier.size(32.dp)) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "تحديث",
                    tint = AppTheme.colors.primary
                )
            }
        }

        // Action: New Group
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onCreateGroup() }
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(AppTheme.colors.primary),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Groups, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column {
                Text(
                    text = "مجموعة جديدة +",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = AppTheme.colors.textPrimary
                )
                Text(
                    text = "محادثة جماعية محلية لكل أجهزة الشبكة",
                    fontSize = 12.sp,
                    color = AppTheme.colors.textSecondary
                )
            }
        }

        HorizontalDivider(color = AppTheme.colors.border, thickness = 0.5.dp, modifier = Modifier.padding(vertical = 4.dp))

        // Discovered Contacts Header
        Text(
            text = "الأجهزة المكتشفة في الشبكة المحلية (${contacts.size})",
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = AppTheme.colors.textSecondary,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
        )

        if (contacts.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "جاري البحث التلقائي عن الهواتف المجاورة...",
                    fontSize = 13.sp,
                    color = AppTheme.colors.textSecondary
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(280.dp)
            ) {
                items(contacts, key = { it.deviceId }) { contact ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectContact(contact) }
                            .padding(horizontal = 20.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AvatarView(
                            name = contact.customNickname ?: contact.displayName,
                            avatarPath = contact.avatarPath,
                            avatarColorIndex = contact.avatarColorIndex,
                            isOnline = contact.isOnline,
                            size = 42.dp
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = contact.customNickname ?: contact.displayName,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = AppTheme.colors.textPrimary
                            )
                            Text(
                                text = if (contact.isOnline) "متصل الآن (IP: ${contact.ipAddress})" else "غير متصل",
                                fontSize = 12.sp,
                                color = if (contact.isOnline) Color(0xFF10B981) else AppTheme.colors.textSecondary
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ManualIpConnectDialog(
    currentSubnet: String,
    onDismiss: () -> Unit,
    onConnect: (String) -> Unit
) {
    var ipInput by remember { mutableStateOf("$currentSubnet.") }
    var isTestingPing by remember { mutableStateOf(false) }
    var pingResult by remember { mutableStateOf<String?>(null) }
    val coroutineScope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Add, contentDescription = null, tint = Color(0xFF10B981), modifier = Modifier.size(24.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("اتصال مباشر بـ IP", fontWeight = FontWeight.Bold, color = AppTheme.colors.textPrimary)
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "أدخل عنوان IP للجهاز الآخر المتصل معك بنفس شبكة الـ Wi-Fi أو نقطة الاتصال (Hotspot) للاتصال به فوراً:",
                    fontSize = 13.sp,
                    color = AppTheme.colors.textSecondary,
                    lineHeight = 18.sp
                )
                Spacer(modifier = Modifier.height(14.dp))

                OutlinedTextField(
                    value = ipInput,
                    onValueChange = {
                        ipInput = it
                        pingResult = null
                    },
                    label = { Text("عنوان IP للجهاز الهدف") },
                    placeholder = { Text("192.168.43.15") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                )

                if (pingResult != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = pingResult!!,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (pingResult!!.contains("ناجح")) Color(0xFF10B981) else Color(0xFFEF4444)
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = {
                            val ip = ipInput.trim()
                            if (ip.isBlank()) return@TextButton
                            isTestingPing = true
                            pingResult = "جاري فحص الاستجابة..."
                            coroutineScope.launch(Dispatchers.IO) {
                                val latency = com.example.data.network.NetworkUtils.pingHostLatency(ip)
                                withContext(Dispatchers.Main) {
                                    isTestingPing = false
                                    pingResult = if (latency > 0) "✅ فحص ناجح: زمن الاستجابة $latency ms" else "⚠️ لا يوجد استجابة من $ip"
                                }
                            }
                        },
                        enabled = !isTestingPing && ipInput.isNotBlank()
                    ) {
                        Text(if (isTestingPing) "جاري الفحص..." else "⚡ فحص Ping", fontSize = 12.sp, color = Color(0xFF3B82F6))
                    }

                    Text(
                        text = "المنفذ: 9999",
                        fontSize = 11.sp,
                        color = AppTheme.colors.textSecondary
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConnect(ipInput.trim()) },
                enabled = ipInput.trim().isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("اتصال وإضافة", fontWeight = FontWeight.Bold, color = Color.White)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("إلغاء", color = AppTheme.colors.textSecondary)
            }
        },
        containerColor = AppTheme.colors.dialogBackground
    )
}

// -------------------------------------------------------------
// GROUPS TAB
// -------------------------------------------------------------
@Composable
private fun GroupsTabContent(
    groups: List<GroupEntity>,
    onCreateGroup: () -> Unit,
    onOpenGroup: (GroupEntity) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Create Group Action
        item {
            Surface(
                color = AppTheme.colors.cardBackground,
                shape = RoundedCornerShape(14.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF10B981).copy(alpha = 0.4f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onCreateGroup() }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF10B981)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text("إنشاء مجموعة جديدة +", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFF10B981))
                        Text("تواصل جماعي مع كل المتصلين بالشبكة معاً", fontSize = 12.sp, color = Color(0xFF8696A0))
                    }
                }
            }
        }

        if (groups.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 40.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Groups, contentDescription = null, tint = Color(0xFF8696A0), modifier = Modifier.size(40.dp))
                        Spacer(modifier = Modifier.height(10.dp))
                        Text("لا توجد مجموعات بعد", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFFE9EDEF))
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("أنشئ مجموعة لمشاركة الرسائل والصور مع الجميع", fontSize = 12.sp, color = Color(0xFF8696A0))
                    }
                }
            }
        } else {
            items(groups, key = { it.groupId }) { group ->
                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = AppTheme.colors.cardBackground),
                    border = androidx.compose.foundation.BorderStroke(0.5.dp, Color(0xFF1F2C34)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenGroup(group) }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(46.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF133827))
                                .border(1.dp, Color(0xFF10B981), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Groups, contentDescription = null, tint = Color(0xFF10B981), modifier = Modifier.size(24.dp))
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(group.groupName, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color(0xFFE9EDEF))
                            Text(
                                text = group.description.ifBlank { "مجموعة محلية مفتوحة للجميع" },
                                fontSize = 12.sp,
                                color = Color(0xFF8696A0),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Icon(Icons.Default.Chat, contentDescription = null, tint = Color(0xFF10B981), modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
    }
}

// -------------------------------------------------------------
// CALLS TAB (Fast Voice Call Speed-Dial)
// -------------------------------------------------------------
@Composable
private fun CallsTabContent(
    contacts: List<ContactEntity>,
    onVoiceCall: (ContactEntity) -> Unit,
    onScanClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "المكالمات الصوتية المحلية المباشرة",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFE9EDEF)
            )

            IconButton(onClick = onScanClick, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Refresh, contentDescription = "تحديث", tint = Color(0xFF10B981))
            }
        }

        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "تحدث بصوت واضح ومشفّر مباشرة عبر Wi-Fi و Mesh بدون إنترنت أو استهلاك رصيد.",
            fontSize = 12.sp,
            color = Color(0xFF8696A0),
            lineHeight = 17.sp
        )

        Spacer(modifier = Modifier.height(14.dp))

        if (contacts.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .size(70.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF133827)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Call, contentDescription = null, tint = Color(0xFF10B981), modifier = Modifier.size(34.dp))
                    }
                    Spacer(modifier = Modifier.height(14.dp))
                    Text("لا توجد أجهزة متصلة للمكالمات حالياً", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFFE9EDEF))
                    Spacer(modifier = Modifier.height(6.dp))
                    Text("قم بربط الهاتفين بنفس الشبكة أو تفعيل P2P Mesh للاتصال المباشر", fontSize = 12.sp, color = Color(0xFF8696A0), textAlign = TextAlign.Center)
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = onScanClick,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("فحص الشبكة", fontWeight = FontWeight.Bold)
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(contacts, key = { it.deviceId }) { contact ->
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = AppTheme.colors.cardBackground),
                        border = androidx.compose.foundation.BorderStroke(0.5.dp, Color(0xFF1F2C34)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AvatarView(
                                name = contact.customNickname ?: contact.displayName,
                                avatarPath = contact.avatarPath,
                                avatarColorIndex = contact.avatarColorIndex,
                                isOnline = contact.isOnline,
                                size = 44.dp
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = contact.customNickname ?: contact.displayName,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFE9EDEF)
                                )
                                Text(
                                    text = if (contact.isOnline) "متاح للمكالمات الصوتية" else "غير متصل",
                                    fontSize = 12.sp,
                                    color = if (contact.isOnline) Color(0xFF10B981) else Color(0xFF8696A0)
                                )
                            }

                            IconButton(
                                onClick = { onVoiceCall(contact) },
                                modifier = Modifier
                                    .size(42.dp)
                                    .clip(CircleShape)
                                    .background(if (contact.isOnline) Color(0xFF10B981) else Color(0xFF1F2C34))
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Call,
                                    contentDescription = "مكالمة صوتية",
                                    tint = if (contact.isOnline) Color.White else Color(0xFF8696A0),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// -------------------------------------------------------------
// Timestamp Formatter (WhatsApp-like)
// -------------------------------------------------------------
private fun formatChatTimestamp(timestamp: Long): String {
    if (timestamp <= 0L) return ""
    val now = System.currentTimeMillis()
    val diff = now - timestamp
    val calNow = Calendar.getInstance().apply { timeInMillis = now }
    val calMsg = Calendar.getInstance().apply { timeInMillis = timestamp }

    val isSameDay = calNow.get(Calendar.YEAR) == calMsg.get(Calendar.YEAR) &&
            calNow.get(Calendar.DAY_OF_YEAR) == calMsg.get(Calendar.DAY_OF_YEAR)

    val isYesterday = calNow.get(Calendar.YEAR) == calMsg.get(Calendar.YEAR) &&
            calNow.get(Calendar.DAY_OF_YEAR) - calMsg.get(Calendar.DAY_OF_YEAR) == 1

    return when {
        diff < 60_000L -> "الآن"
        isSameDay -> {
            val sdf = SimpleDateFormat("h:mm a", Locale("ar"))
            sdf.format(Date(timestamp))
        }
        isYesterday -> "أمس"
        diff < 7 * 24 * 3600_000L -> {
            val sdf = SimpleDateFormat("EEEE", Locale("ar"))
            sdf.format(Date(timestamp))
        }
        else -> {
            val sdf = SimpleDateFormat("d/M/yy", Locale("ar"))
            sdf.format(Date(timestamp))
        }
    }
}
