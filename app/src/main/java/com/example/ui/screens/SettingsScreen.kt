package com.example.ui.screens
import android.os.PowerManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.DeveloperMode
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.network.NetworkPermissionHelper
import com.example.service.LanBackgroundService
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material3.OutlinedButton
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.example.ui.components.QrIdentityDialog
import com.example.ui.ChatViewModel
import com.example.ui.components.AvatarView
import com.example.ui.components.NetworkStatusCard
import com.example.ui.components.ProfileSetupDialog
import androidx.compose.runtime.DisposableEffect
import com.example.ui.theme.AppTheme
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import com.example.data.network.MeshReadiness
import com.example.ui.components.MeshPreflightCard
import com.example.ui.components.AdvancedSettingsSection
@Composable
fun SettingsScreen(
    viewModel: ChatViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val userProfile by viewModel.userProfile.collectAsState()
    val meshStatus by viewModel.meshStatus.collectAsState()
    val networkDiagnostic by viewModel.networkDiagnostic.collectAsState()
    val appShareState by viewModel.appShareState.collectAsState()
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        viewModel.refreshNetworkAndPermissions()
    }
    var meshReadiness by remember { mutableStateOf(MeshReadiness.check(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                meshReadiness = MeshReadiness.check(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val meshPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        meshReadiness = MeshReadiness.check(context)
        viewModel.refreshNetworkAndPermissions()
        if (meshReadiness.isReady) viewModel.refreshDiscovery()
    }
    val isSubnetScanning by viewModel.isSubnetScanning.collectAsState()
    val subnetScanProgress by viewModel.subnetScanProgress.collectAsState()
    val scanResultNotice by viewModel.scanResultNotice.collectAsState()
    val networkLogs by viewModel.networkLogs.collectAsState()
    var showManualIpDialog by remember { mutableStateOf(false) }
    var showQrDialog by remember { mutableStateOf(false) }
    val qrPayload by remember { mutableStateOf(viewModel.buildMyQrPayload()) }
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val qrScanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        if (result.contents != null) {
            viewModel.addContactFromQr(result.contents!!) { ok, msg ->
                scope.launch { snackbarHostState.showSnackbar(msg) }
            }
        }
    }
    val clipboardManager = LocalClipboardManager.current
    var showEditProfileDialog by remember { mutableStateOf(false) }
    var copiedDeviceIdNotice by remember { mutableStateOf(false) }
    val isBatteryOptIgnored = remember {
        val powerManager = context.getSystemService(android.content.Context.POWER_SERVICE) as? PowerManager
        powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true
    }
    if (showQrDialog && qrPayload != null) {
        QrIdentityDialog(
            displayName = userProfile.displayName,
            deviceId = userProfile.deviceId,
            encodedCard = qrPayload!!,
            onDismiss = { showQrDialog = false }
        )
    }

    if (showManualIpDialog) {
        ManualIpConnectDialog(
            currentSubnet = networkDiagnostic.localIpAddress.substringBeforeLast('.'),
            onDismiss = { showManualIpDialog = false },
            onConnect = { ip ->
                viewModel.connectToManualIp(ip) { _, _ -> showManualIpDialog = false }
            }
        )
    }
    if (showEditProfileDialog) {        ProfileSetupDialog(
            initialName = userProfile.displayName,
            initialAvatarPath = userProfile.avatarPath,
            initialColorIndex = userProfile.avatarColorIndex,
            initialIsDeveloper = userProfile.isDeveloper,
            deviceId = userProfile.deviceId,
            isFirstTime = false,
            onDismiss = { showEditProfileDialog = false },
            onSave = { name, avatarPath, colorIndex, isDev ->
                viewModel.updateUserProfile(name, avatarPath, colorIndex, isDev)
                showEditProfileDialog = false
            }
        )
    }
    Scaffold(
        containerColor = AppTheme.colors.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        modifier = modifier
            .fillMaxSize()
            .testTag("settings_screen")
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .statusBarsPadding()
        ) {
            Surface(
                color = AppTheme.colors.surface,
                shadowElevation = 2.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, AppTheme.colors.border)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.size(44.dp).testTag("settings_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "رجوع",
                            tint = AppTheme.colors.textPrimary
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "الإعدادات والشبكة المتقدمة",
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Bold,
                        color = AppTheme.colors.textPrimary
                    )
                }
            }
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                item { Spacer(modifier = Modifier.height(6.dp)) }
                // 1. Profile & Device Identity
                item {
                    SettingsCard(title = "الملف الشخصي وهوية الجهاز", icon = Icons.Default.Person) {
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    AvatarView(
                                        name = userProfile.displayName,
                                        avatarPath = userProfile.avatarPath,
                                        avatarColorIndex = userProfile.avatarColorIndex,
                                        showOnlineBadge = false,
                                        size = 54.dp
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column {
                                        Text(
                                            text = userProfile.displayName.ifBlank { "مستخدم جديد" },
                                            fontSize = 17.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = AppTheme.colors.textPrimary
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = if (userProfile.isDeveloper) "حساب مطور معتمد ⚡" else "حساب محلي P2P",
                                            fontSize = 13.sp,
                                            color = if (userProfile.isDeveloper) Color(0xFF8B5CF6) else AppTheme.colors.textSecondary
                                        )
                                    }
                                }
                                IconButton(
                                    onClick = { showEditProfileDialog = true },
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(CircleShape)
                                        .background(AppTheme.colors.surfaceVariant)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Edit,
                                        contentDescription = "تعديل الملف الشخصي",
                                        tint = AppTheme.colors.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(14.dp))
                            Surface(
                                color = AppTheme.colors.surfaceVariant,
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                imageVector = Icons.Default.Fingerprint,
                                                contentDescription = null,
                                                tint = AppTheme.colors.primary,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = "معرف الجهاز الثابت (Device ID):",
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = AppTheme.colors.textPrimary
                                            )
                                        }
                                        Text(
                                            text = if (copiedDeviceIdNotice) "تم النسخ ✓" else "نسخ",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (copiedDeviceIdNotice) AppTheme.colors.online else AppTheme.colors.primary,
                                            modifier = Modifier
                                                .clickable {
                                                    clipboardManager.setText(AnnotatedString(userProfile.deviceId))
                                                    copiedDeviceIdNotice = true
                                                }
                                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = userProfile.deviceId,
                                        fontSize = 12.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = AppTheme.colors.textSecondary
                                    )
                                }
                            }
                        }
                    }
                }
                // 2. Network Diagnostics Card
                item {
                    Column(modifier = Modifier.padding(horizontal = 14.dp)) {
                        SettingsCard(title = "التعرف على الأجهزة", icon = Icons.Default.QrCode2) {
                            Column {
                                Text(
                                    text = "امسح رمز الجهاز التاني لإضافته مباشرة، أو اعرض رمزك هو عشان حد تاني يمسحه.",
                                    fontSize = 13.sp,
                                    color = AppTheme.colors.textSecondary
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(
                                        onClick = {
                                            qrScanLauncher.launch(
                                                ScanOptions()
                                                    .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                                                    .setPrompt("صوّر رمز الجهاز")
                                                    .setBeepEnabled(false)
                                            )
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3B82F6)),
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text("مسح QR", fontSize = 13.sp)
                                    }
                                    OutlinedButton(
                                        onClick = { showQrDialog = true },
                                        shape = RoundedCornerShape(10.dp),
                                        enabled = qrPayload != null,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text("عرض رمزي", fontSize = 13.sp)
                                    }
                                }
                            }
                        }
                    }
                }

                // 3. Network Diagnostics Card
                item {
                    AdvancedSettingsSection(
                        networkDiagnostic = networkDiagnostic,
                        networkLogs = networkLogs,
                        isSubnetScanning = isSubnetScanning,
                        subnetScanProgress = subnetScanProgress,
                        scanResultNotice = scanResultNotice,
                        onStartSubnetScan = { viewModel.startSubnetScan() },
                        onManualIpConnect = { showManualIpDialog = true },
                        onRefresh = { viewModel.refreshNetworkAndPermissions() },
                        onClearLogs = { viewModel.clearNetworkLogs() }
                    )
                }
                // 3. Theme Setting
                item {
                    SettingsCard(title = "المظهر والثيم", icon = Icons.Default.Palette) {
                        Column {
                            Text(
                                text = "اختر وضع الثيم المفضل:",
                                fontSize = 14.sp,
                                color = AppTheme.colors.textSecondary,
                                modifier = Modifier.padding(bottom = 10.dp)
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                ThemeOptionChip("تلقائي", Icons.Default.Info, userProfile.themeMode == "SYSTEM", { viewModel.setThemeMode("SYSTEM") }, Modifier.weight(1f))
                                ThemeOptionChip("نهاري", Icons.Default.LightMode, userProfile.themeMode == "LIGHT", { viewModel.setThemeMode("LIGHT") }, Modifier.weight(1f))
                                ThemeOptionChip("ليلي", Icons.Default.DarkMode, userProfile.themeMode == "DARK", { viewModel.setThemeMode("DARK") }, Modifier.weight(1f))
                            }
                        }
                    }
                }
                // 4. Permissions
                item {
                    SettingsCard(title = "أذونات النظام والشبكة", icon = Icons.Default.Security) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("فحص صلاح الأذونات", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = AppTheme.colors.textPrimary)
                                Text("الصوت، الأجهزة المجاورة، الإشعارات.", fontSize = 13.sp, color = AppTheme.colors.textSecondary)
                            }
                            Button(
                                onClick = {
                                    permissionLauncher.launch(NetworkPermissionHelper.getRequiredPermissionsList().toTypedArray())
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = AppTheme.colors.primary),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("فحص", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                        }
                    }
                }
                // 5. Mesh Mode
                item {
                    SettingsCard(title = "شبكة الربط المتشعبة (P2P Mesh)", icon = Icons.Default.Hub) {
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("تفعيل وضع الشبكة المتشعبة", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = AppTheme.colors.textPrimary)
                                    Text("توصيل الرسائل تلقائياً عبر الأجهزة المجاورة بدون راوتر.", fontSize = 13.sp, color = AppTheme.colors.textSecondary)
                                }
                                Switch(
                                    checked = userProfile.isMeshModeEnabled,
                                    onCheckedChange = { viewModel.toggleMeshMode(it) },
                                    colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = AppTheme.colors.primary)
                                )
                            }
                            if (userProfile.isMeshModeEnabled) {
                                Spacer(modifier = Modifier.height(10.dp))
                                MeshPreflightCard(
                                    readiness = meshReadiness,
                                    onRequestPermissions = {
                                        meshPermissionLauncher.launch(MeshReadiness.requiredPermissions())
                                    },
                                    onDisable = { viewModel.toggleMeshMode(false) },
                                    onRefresh = { meshReadiness = MeshReadiness.check(context) }
                                )
                                if (meshReadiness.isReady) {
                                    Spacer(modifier = Modifier.height(10.dp))
                                    Surface(
                                        color = AppTheme.colors.primaryContainer,
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(10.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text("الأجهزة المتصلة: ${meshStatus.activeMeshNodes}", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = AppTheme.colors.primary)
                                            Text("رسائل مُرحّلة: ${meshStatus.totalPacketsRelayed}", fontSize = 12.sp, color = AppTheme.colors.primary)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                // 6. Share Application File
                item {
                    SettingsCard(title = "مشاركة التطبيق مع الأصدقاء", icon = Icons.Default.Share) {
                        Column {
                            Text(
                                text = "الإصدار المثبت: ${viewModel.userPrefs.appVersionName}",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = AppTheme.colors.textPrimary
                            )
                            Text(
                                text = "أرسل ملف تثبيت التطبيق لأي هاتف مجاور ليتصل معك بدون إنترنت.",
                                fontSize = 13.sp,
                                color = AppTheme.colors.textSecondary
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            if (appShareState.isPreparing) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    CircularProgressIndicator(
                                        color = AppTheme.colors.primary,
                                        modifier = Modifier.size(20.dp),
                                        strokeWidth = 2.dp
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("جاري تحضير ملف التطبيق...", fontSize = 13.sp, color = AppTheme.colors.textSecondary)
                                }
                            } else {
                                Button(
                                    onClick = {
                                        viewModel.prepareAppForSharing()
                                        viewModel.shareApp()
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = AppTheme.colors.primary),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.fillMaxWidth().height(48.dp)
                                ) {
                                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("مشاركة ملف التطبيق (APK)", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                }
                            }
                            if (appShareState.errorMessage != null) {
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = appShareState.errorMessage!!,
                                    fontSize = 12.sp,
                                    color = AppTheme.colors.error
                                )
                            }
                        }
                    }
                }
                // 7. Battery Settings
                item {
                    SettingsCard(title = "استمرار الخدمة في الخلفية", icon = Icons.Default.BatteryChargingFull) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("إعدادات تحسين البطارية", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = AppTheme.colors.textPrimary)
                                Text("تأكد من عدم تقييد النظام للتطبيق لضمان رنين المكالمات.", fontSize = 13.sp, color = AppTheme.colors.textSecondary)
                            }
                            if (isBatteryOptIgnored) {
                                Surface(color = AppTheme.colors.primaryContainer, shape = RoundedCornerShape(12.dp)) {
                                    Row(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Check, contentDescription = null, tint = AppTheme.colors.primary, modifier = Modifier.size(16.dp))
                                        Text("مفعّل", color = AppTheme.colors.primary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                    }
                                }
                            } else {
                                Button(
                                    onClick = { LanBackgroundService.openBatterySettings(context) },
                                    colors = ButtonDefaults.buttonColors(containerColor = AppTheme.colors.primary),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text("فتح الإعدادات", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
                // 8. Sound & Haptics
                item {
                    SettingsCard(title = "التنبيهات والاهتزاز", icon = Icons.Default.Notifications) {
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.VolumeUp, contentDescription = null, tint = AppTheme.colors.primary)
                                    Spacer(Modifier.width(10.dp))
                                    Text("أصوات الرسائل", fontSize = 15.sp, color = AppTheme.colors.textPrimary)
                                }
                                Switch(
                                    checked = userProfile.isSoundEnabled,
                                    onCheckedChange = { viewModel.toggleSound(it) },
                                    colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = AppTheme.colors.primary)
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Vibration, contentDescription = null, tint = AppTheme.colors.primary)
                                    Spacer(Modifier.width(10.dp))
                                    Text("الاهتزاز", fontSize = 15.sp, color = AppTheme.colors.textPrimary)
                                }
                                Switch(
                                    checked = userProfile.isHapticEnabled,
                                    onCheckedChange = { viewModel.toggleHaptic(it) },
                                    colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = AppTheme.colors.primary)
                                )
                            }
                        }
                    }
                }
                // 9. Developer Mode
                item {
                    SettingsCard(title = "وضع المطور", icon = Icons.Default.DeveloperMode) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("وضع المطور", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = AppTheme.colors.textPrimary)
                                Text("عرض السجلات والبيانات الفنية.", fontSize = 13.sp, color = AppTheme.colors.textSecondary)
                            }
                            Switch(
                                checked = userProfile.isDeveloper,
                                onCheckedChange = { viewModel.toggleDeveloperMode(it) },
                                colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Color(0xFF8B5CF6))
                            )
                        }
                    }
                }
                item { Spacer(modifier = Modifier.height(20.dp)) }
            }
        }
    }
}
@Composable
private fun SettingsCard(title: String, icon: ImageVector, content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = AppTheme.colors.cardBackground),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, AppTheme.colors.border, RoundedCornerShape(16.dp))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(AppTheme.colors.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(icon, contentDescription = null, tint = AppTheme.colors.primary, modifier = Modifier.size(20.dp))
                }
                Spacer(modifier = Modifier.width(10.dp))
                Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = AppTheme.colors.textPrimary)
            }
            content()
        }
    }
}
@Composable
private fun ThemeOptionChip(label: String, icon: ImageVector, isSelected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        color = if (isSelected) AppTheme.colors.primary else AppTheme.colors.surfaceVariant,
        shape = RoundedCornerShape(10.dp),
        modifier = modifier.height(44.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(icon, contentDescription = null, tint = if (isSelected) Color.White else AppTheme.colors.textPrimary, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text(label, fontSize = 12.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium, color = if (isSelected) Color.White else AppTheme.colors.textPrimary, maxLines = 1)
        }
    }
}
