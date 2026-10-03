package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.data.local.ContactEntity
import com.example.data.local.GroupEntity
import com.example.ui.ChatViewModel
import com.example.ui.components.AvatarView
import com.example.ui.components.ChatBubble
import com.example.ui.components.ContactEditDialog
import com.example.ui.components.DeveloperBadge
import com.example.ui.components.ImageViewerDialog
import com.example.ui.components.TransferProgressCard
import com.example.ui.theme.AppTheme
import com.example.data.network.FeedbackUtils
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun ChatScreen(
    contact: ContactEntity?,
    group: GroupEntity?,
    viewModel: ChatViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val messages by viewModel.activeConversationMessages.collectAsState()
    val isSending by viewModel.isSending.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val activeTransfers by viewModel.activeTransfers.collectAsState()
    val recordingState by viewModel.recordingState.collectAsState()
    val playbackState by viewModel.playbackState.collectAsState()

    val lastOutgoing = remember(messages, contact?.deviceId) {
        messages.lastOrNull { it.isFromMe }
    }
    val meshBannerHops = remember(lastOutgoing, contact?.deviceId) {
        if (contact != null && lastOutgoing?.isMeshRelayed == true) lastOutgoing.meshHops else 0
    }

    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()

    var textInput by remember { mutableStateOf("") }
    var selectedPhotoForViewer by remember { mutableStateOf<String?>(null) }
    var showEditContactDialog by remember { mutableStateOf(false) }
    var showOptionsMenu by remember { mutableStateOf(false) }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }
    var showAttachmentMenu by remember { mutableStateOf(false) }

    // Slide-to-cancel drag tracking state
    var dragOffsetX by remember { mutableFloatStateOf(0f) }
    var isCancelTriggered by remember { mutableStateOf(false) }

    // Audio recording permission launcher
    val audioRecordPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            FeedbackUtils.vibrateTick(context)
            viewModel.startVoiceRecording()
        } else {
            scope.launch {
                snackbarHostState.showSnackbar("يلزم منح إذن الميكروفون لتسجيل وإرسال الرسائل الصوتية")
            }
        }
    }

    // Photo picker launcher
    val photoLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.sendPhotoUri(uri)
        }
    }

    // Camera picture launcher
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicturePreview()
    ) { bitmap: Bitmap? ->
        if (bitmap != null) {
            viewModel.sendPhotoBitmap(bitmap)
        }
    }

    // Video picker launcher
    val videoLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.sendVideoUri(uri)
        }
    }

    // Generic document/file picker launcher
    val fileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.sendFileUri(uri)
        }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    LaunchedEffect(errorMessage) {
        if (errorMessage != null) {
            snackbarHostState.showSnackbar(errorMessage!!)
            viewModel.clearErrorMessage()
        }
    }

    // لو الرسالة الصوتية اتفكّت من الملفات أو فشل الـ codec، اللاعب كان بيقف
    // بصمت من غير أي سبب. بنطلّع سبب حقيقي للمستخدم.
    LaunchedEffect(playbackState.errorMessage) {
        val playbackError = playbackState.errorMessage
        if (playbackError != null) {
            snackbarHostState.showSnackbar(playbackError)
            viewModel.clearPlaybackError()
        }
    }

    // Photo viewer dialog
    if (selectedPhotoForViewer != null) {
        ImageViewerDialog(
            photoPath = selectedPhotoForViewer!!,
            onDismiss = { selectedPhotoForViewer = null }
        )
    }

    // Edit contact dialog (Nickname & custom picture)
    if (showEditContactDialog && contact != null) {
        ContactEditDialog(
            contact = contact,
            onDismiss = { showEditContactDialog = false },
            onSave = { nickname, avatarPath ->
                viewModel.updateContactCustomization(contact.deviceId, nickname, avatarPath)
                showEditContactDialog = false
            }
        )
    }

    // Clear Conversation confirm dialog
    if (showDeleteConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = false },
            title = { Text("مسح المحادثة من جهازك؟", fontWeight = FontWeight.Bold, color = AppTheme.colors.textPrimary) },
            text = {
                Text(
                    "سيتم مسح سجل الرسائل لهذه المحادثة من جهازك فقط. لن يتم مسحها من جهاز الطرف الآخر.",
                    color = AppTheme.colors.textSecondary,
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (contact != null) {
                            viewModel.clearConversation(contact.deviceId)
                        } else if (group != null) {
                            viewModel.clearConversation(group.groupId)
                        }
                        showDeleteConfirmDialog = false
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFEF4444))
                ) {
                    Text("حذف من عندي", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmDialog = false }) {
                    Text("إلغاء", color = AppTheme.colors.textSecondary)
                }
            },
            containerColor = AppTheme.colors.dialogBackground
        )
    }

    val titleText = when {
        contact != null -> contact.customNickname ?: contact.displayName
        group != null -> group.groupName
        else -> "المحادثة"
    }

    // Pulse animation for active voice recording
    val infiniteTransition = rememberInfiniteTransition(label = "rec_pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(600),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    // Chevron slide animation
    val slideAnim by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = -12f,
        animationSpec = infiniteRepeatable(
            animation = tween(800),
            repeatMode = RepeatMode.Reverse
        ),
        label = "slide_chevron"
    )

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = AppTheme.colors.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        modifier = modifier
            .fillMaxSize()
            .testTag("chat_screen")
    ) { _ ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
                .background(AppTheme.colors.background)
        ) {
            // Top Bar
            Surface(
                color = AppTheme.colors.surface,
                shadowElevation = 2.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .border(width = 1.dp, color = AppTheme.colors.border)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 6.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier
                            .size(44.dp)
                            .testTag("chat_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "رجوع",
                            tint = AppTheme.colors.textPrimary,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(2.dp))

                    if (contact != null) {
                        AvatarView(
                            name = titleText,
                            avatarPath = contact.avatarPath,
                            avatarColorIndex = contact.avatarColorIndex,
                            isOnline = contact.isOnline,
                            size = 44.dp,
                            modifier = Modifier.clickable { showEditContactDialog = true }
                        )
                    } else if (group != null) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(AppTheme.colors.primaryContainer)
                                .border(1.5.dp, AppTheme.colors.primary, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Groups,
                                contentDescription = "Group",
                                tint = AppTheme.colors.primary,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(10.dp))

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clickable(enabled = contact != null) {
                                showEditContactDialog = true
                            }
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = titleText,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = AppTheme.colors.textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (contact?.isDeveloper == true) {
                                Spacer(modifier = Modifier.width(4.dp))
                                DeveloperBadge(isSmall = true)
                            }
                        }

                        if (contact != null) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(7.dp)
                                        .clip(CircleShape)
                                        .background(if (contact.isOnline) AppTheme.colors.online else AppTheme.colors.offline)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = if (contact.isOnline) "متصل الآن" else "غير متصل",
                                    fontSize = 12.sp,
                                    color = if (contact.isOnline) AppTheme.colors.online else AppTheme.colors.offline,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        } else if (group != null) {
                            Text(
                                text = "مجموعة محلية مفتوحة للجميع",
                                fontSize = 12.sp,
                                color = AppTheme.colors.textSecondary
                            )
                        }
                    }

                    // Direct Voice Call Button (Only for 1-to-1 chats)
                    if (contact != null) {
                        IconButton(
                            onClick = { viewModel.startVoiceCall(contact) },
                            modifier = Modifier
                                .size(44.dp)
                                .testTag("start_voice_call_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Call,
                                contentDescription = "مكالمة صوتية",
                                tint = AppTheme.colors.primary,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }

                    // More Options Menu
                    Box {
                        IconButton(
                            onClick = { showOptionsMenu = true },
                            modifier = Modifier.size(44.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = "المزيد",
                                tint = AppTheme.colors.textPrimary,
                                modifier = Modifier.size(24.dp)
                            )
                        }

                        DropdownMenu(
                            expanded = showOptionsMenu,
                            onDismissRequest = { showOptionsMenu = false },
                            modifier = Modifier.background(AppTheme.colors.dialogBackground)
                        ) {
                            if (contact != null) {
                                DropdownMenuItem(
                                    text = { Text("تعديل الاسم والصورة", color = AppTheme.colors.textPrimary) },
                                    leadingIcon = {
                                        Icon(Icons.Default.Edit, contentDescription = null, tint = AppTheme.colors.primary)
                                    },
                                    onClick = {
                                        showOptionsMenu = false
                                        showEditContactDialog = true
                                    }
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("حذف المحادثة من عندي", color = Color(0xFFEF4444)) },
                                leadingIcon = {
                                    Icon(Icons.Default.DeleteSweep, contentDescription = null, tint = Color(0xFFEF4444))
                                },
                                onClick = {
                                    showOptionsMenu = false
                                    showDeleteConfirmDialog = true
                                }
                            )
                        }
                    }
                }
            }

            // MESH Transport Banner
            if (meshBannerHops > 0) {
                Surface(
                    color = Color(0xFF0F291E),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Wifi,
                            contentDescription = null,
                            tint = Color(0xFF10B981),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (meshBannerHops > 1)
                                "يتصل عبر MESH — عبر $meshBannerHops أجهزة"
                            else "يتصل عبر MESH",
                            fontSize = 12.sp,
                            color = Color(0xFF10B981)
                        )
                    }
                }
            }

            // Active File Transfers Banner
            if (activeTransfers.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(AppTheme.colors.surfaceVariant)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    activeTransfers.values.forEach { transfer ->
                        TransferProgressCard(
                            transfer = transfer,
                            onCancel = { viewModel.cancelTransfer(transfer.transferId) }
                        )
                    }
                }
            }

            // Messages Container
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(AppTheme.colors.background)
            ) {
                if (messages.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Surface(
                            color = AppTheme.colors.cardBackground,
                            shape = RoundedCornerShape(16.dp),
                            shadowElevation = 1.dp,
                            modifier = Modifier
                                .fillMaxWidth(0.9f)
                                .border(1.dp, AppTheme.colors.border, RoundedCornerShape(16.dp))
                        ) {
                            Column(
                                modifier = Modifier.padding(20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(54.dp)
                                        .clip(CircleShape)
                                        .background(AppTheme.colors.primaryContainer),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.Send,
                                        contentDescription = null,
                                        tint = AppTheme.colors.primary,
                                        modifier = Modifier.size(28.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = "ابدأ المحادثة المشفرة",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = AppTheme.colors.textPrimary
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "محادثة مشفرة تماماً عبر الشبكة المحلية (LAN) مع دعم الصوت والفيديو والملفات.",
                                    fontSize = 13.sp,
                                    color = AppTheme.colors.textSecondary,
                                    textAlign = TextAlign.Center,
                                    lineHeight = 18.sp
                                )
                            }
                        }
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(top = 12.dp, bottom = 12.dp)
                    ) {
                        items(messages, key = { it.id }) { message ->
                            ChatBubble(
                                message = message,
                                onPhotoClick = { path -> selectedPhotoForViewer = path },
                                onPlayAudio = { path -> viewModel.toggleAudioPlayback(path) },
                                playbackState = playbackState
                            )
                        }
                    }
                }
            }

            // Attachment Picker Panel (Expandable)
            AnimatedVisibility(
                visible = showAttachmentMenu,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Surface(
                    color = AppTheme.colors.surface,
                    shadowElevation = 8.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, AppTheme.colors.border)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "مشاركة وسائط أو ملفات",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = AppTheme.colors.textPrimary
                            )
                            IconButton(
                                onClick = { showAttachmentMenu = false },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "إغلاق",
                                    tint = AppTheme.colors.textSecondary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceAround
                        ) {
                            AttachmentOptionItem(
                                title = "فيديو / فيلم",
                                icon = Icons.Default.Movie,
                                backgroundColor = Color(0xFF10B981),
                                onClick = {
                                    showAttachmentMenu = false
                                    videoLauncher.launch("video/*")
                                }
                            )

                            AttachmentOptionItem(
                                title = "ملف / مستند",
                                icon = Icons.Default.Folder,
                                backgroundColor = Color(0xFF3B82F6),
                                onClick = {
                                    showAttachmentMenu = false
                                    fileLauncher.launch("*/*")
                                }
                            )

                            AttachmentOptionItem(
                                title = "معرض الصور",
                                icon = Icons.Default.Image,
                                backgroundColor = Color(0xFF8B5CF6),
                                onClick = {
                                    showAttachmentMenu = false
                                    photoLauncher.launch("image/*")
                                }
                            )

                            AttachmentOptionItem(
                                title = "كاميرا",
                                icon = Icons.Default.CameraAlt,
                                backgroundColor = Color(0xFFF59E0B),
                                onClick = {
                                    showAttachmentMenu = false
                                    cameraLauncher.launch(null)
                                }
                            )
                        }
                    }
                }
            }

            // Bottom Input Bar & Voice Recorder
            Surface(
                color = AppTheme.colors.surface,
                shadowElevation = 6.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .border(width = 1.dp, color = AppTheme.colors.border)
            ) {
                if (recordingState.isRecording) {
                    // Active Voice Recording Bar with Slide-to-Cancel Visuals
                    val recDurationSeconds = recordingState.durationMs / 1000
                    val formattedRecDuration = String.format(Locale.getDefault(), "%02d:%02d", recDurationSeconds / 60, recDurationSeconds % 60)

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(14.dp)
                                    .scale(pulseScale)
                                    .clip(CircleShape)
                                    .background(if (isCancelTriggered) Color(0xFFEF4444) else AppTheme.colors.primary)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "تسجيل: $formattedRecDuration",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isCancelTriggered) Color(0xFFEF4444) else AppTheme.colors.primary
                            )
                        }

                        // Sliding gesture hint
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.offset(x = slideAnim.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ChevronLeft,
                                contentDescription = null,
                                tint = if (isCancelTriggered) Color(0xFFEF4444) else AppTheme.colors.textSecondary,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = if (isCancelTriggered) "أفلت للإلغاء" else "اسحب لليسار للإلغاء",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = if (isCancelTriggered) Color(0xFFEF4444) else AppTheme.colors.textSecondary
                            )
                        }

                        // Actions (Cancel or Send)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            IconButton(
                                onClick = {
                                    FeedbackUtils.vibrateTick(context)
                                    viewModel.cancelVoiceRecording()
                                    isCancelTriggered = false
                                },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = "إلغاء التسجيل",
                                    tint = Color(0xFFEF4444),
                                    modifier = Modifier.size(22.dp)
                                )
                            }

                            IconButton(
                                onClick = {
                                    FeedbackUtils.vibrateTick(context)
                                    viewModel.stopVoiceRecordingAndSend()
                                    isCancelTriggered = false
                                },
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .background(AppTheme.colors.primary)
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.Send,
                                    contentDescription = "إرسال التسجيل",
                                    tint = Color.White,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                    }
                } else {
                    // Normal Text & Media Input Row
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // Send Button or Voice Note Button
                        if (textInput.isNotBlank()) {
                            Box(
                                modifier = Modifier
                                    .size(46.dp)
                                    .shadow(elevation = 2.dp, shape = CircleShape, spotColor = AppTheme.colors.primary)
                                    .clip(CircleShape)
                                    .background(AppTheme.colors.primary)
                                    .clickable(enabled = !isSending) {
                                        val textToSend = textInput
                                        textInput = ""
                                        viewModel.sendTextMessage(textToSend)
                                    }
                                    .testTag("send_message_button"),
                                contentAlignment = Alignment.Center
                            ) {
                                if (isSending) {
                                    CircularProgressIndicator(
                                        color = Color.White,
                                        strokeWidth = 2.5.dp,
                                        modifier = Modifier.size(22.dp)
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.Send,
                                        contentDescription = "إرسال",
                                        tint = Color.White,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                            }
                        } else {
                            // Mic Button for Voice Note with Hold-to-Record & Slide-to-Cancel
                            Box(
                                modifier = Modifier
                                    .size(46.dp)
                                    .shadow(elevation = 2.dp, shape = CircleShape, spotColor = AppTheme.colors.primary)
                                    .clip(CircleShape)
                                    .background(AppTheme.colors.primary)
                                    .pointerInput(Unit) {
                                        detectTapGestures(
                                            onLongPress = {
                                                val hasAudioPerm = ContextCompat.checkSelfPermission(
                                                    context,
                                                    Manifest.permission.RECORD_AUDIO
                                                ) == PackageManager.PERMISSION_GRANTED

                                                if (hasAudioPerm) {
                                                    FeedbackUtils.vibrateTick(context)
                                                    viewModel.startVoiceRecording()
                                                } else {
                                                    audioRecordPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                                }
                                            },
                                            onTap = {
                                                val hasAudioPerm = ContextCompat.checkSelfPermission(
                                                    context,
                                                    Manifest.permission.RECORD_AUDIO
                                                ) == PackageManager.PERMISSION_GRANTED

                                                if (hasAudioPerm) {
                                                    FeedbackUtils.vibrateTick(context)
                                                    viewModel.startVoiceRecording()
                                                } else {
                                                    audioRecordPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                                }
                                            }
                                        )
                                    }
                                    .testTag("record_voice_button"),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Mic,
                                    contentDescription = "تسجيل صوتي (اضغط مطولاً)",
                                    tint = Color.White,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }

                        // Text Input Field
                        OutlinedTextField(
                            value = textInput,
                            onValueChange = { textInput = it },
                            placeholder = {
                                Text(
                                    text = "اكتب رسالتك هنا...",
                                    fontSize = 15.sp,
                                    color = AppTheme.colors.textSecondary
                                )
                            },
                            textStyle = TextStyle(
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                                color = AppTheme.colors.textPrimary
                            ),
                            maxLines = 4,
                            shape = RoundedCornerShape(24.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = AppTheme.colors.primary,
                                unfocusedBorderColor = AppTheme.colors.border,
                                focusedContainerColor = AppTheme.colors.surfaceVariant,
                                unfocusedContainerColor = AppTheme.colors.surfaceVariant
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("chat_input_field")
                        )

                        // Attachment Button
                        IconButton(
                            onClick = { showAttachmentMenu = !showAttachmentMenu },
                            modifier = Modifier
                                .size(42.dp)
                                .clip(CircleShape)
                                .background(if (showAttachmentMenu) AppTheme.colors.primary.copy(alpha = 0.15f) else AppTheme.colors.surfaceVariant)
                                .testTag("attachment_menu_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.AttachFile,
                                contentDescription = "إرفاق ملفات وفيديوهات",
                                tint = if (showAttachmentMenu) AppTheme.colors.primary else AppTheme.colors.textPrimary,
                                modifier = Modifier.size(22.dp)
                            )
                        }

                        // Quick Camera Button
                        IconButton(
                            onClick = { cameraLauncher.launch(null) },
                            modifier = Modifier
                                .size(42.dp)
                                .clip(CircleShape)
                                .background(AppTheme.colors.surfaceVariant)
                                .testTag("camera_action_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.CameraAlt,
                                contentDescription = "التقاط صورة",
                                tint = AppTheme.colors.primary,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AttachmentOptionItem(
    title: String,
    icon: ImageVector,
    backgroundColor: Color,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .shadow(elevation = 2.dp, shape = CircleShape)
                .clip(CircleShape)
                .background(backgroundColor),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = Color.White,
                modifier = Modifier.size(26.dp)
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = title,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = AppTheme.colors.textPrimary
        )
    }
}
