package com.example.ui.components

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.network.ImageUtils
import com.example.ui.theme.AppTheme
import com.example.ui.theme.AvatarColors

/**
 * Onboarding and Profile Setup screen/dialog.
 * Follows an intuitive, user-friendly profile creation flow (Name + Photo/Avatar),
 * but with the app's modern P2P Cobalt/Indigo visual identity and offline-first device key transparency.
 */
@Composable
fun ProfileSetupDialog(
    initialName: String,
    initialAvatarPath: String?,
    initialColorIndex: Int,
    initialIsDeveloper: Boolean = false,
    deviceId: String = "",
    isFirstTime: Boolean = false,
    onDismiss: () -> Unit,
    onSave: (name: String, avatarPath: String?, colorIndex: Int, isDev: Boolean) -> Unit
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    var name by remember { mutableStateOf(initialName) }
    var avatarPath by remember { mutableStateOf(initialAvatarPath) }
    var selectedColorIndex by remember { mutableIntStateOf(initialColorIndex) }

    var copiedNotice by remember { mutableStateOf(false) }

    // Camera launcher
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicturePreview()
    ) { bitmap: Bitmap? ->
        if (bitmap != null) {
            val savedFile = ImageUtils.saveBitmapToFile(context, bitmap, "profile_")
            if (savedFile != null) {
                avatarPath = savedFile.absolutePath
            }
        }
    }

    // Gallery launcher
    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            val base64 = ImageUtils.uriToCompressedBase64(context, uri, 600, 80)
            if (base64 != null) {
                val file = ImageUtils.base64ToImageFile(context, base64, "profile_")
                if (file != null) {
                    avatarPath = file.absolutePath
                }
            }
        }
    }

    Dialog(
        onDismissRequest = {
            if (!isFirstTime) onDismiss()
        },
        properties = DialogProperties(
            dismissOnClickOutside = !isFirstTime,
            usePlatformDefaultWidth = false
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .background(AppTheme.colors.background)
                .statusBarsPadding()
                .imePadding()
                .testTag("profile_setup_screen"),
            color = AppTheme.colors.background
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Top Action Bar / Close / Back
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    if (!isFirstTime) {
                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "إغلاق",
                                tint = AppTheme.colors.textPrimary
                            )
                        }
                    } else {
                        Spacer(modifier = Modifier.size(40.dp))
                    }

                    Text(
                        text = if (isFirstTime) "إنشاء حسابك المحلي" else "الملف الشخصي",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = AppTheme.colors.textPrimary
                    )

                    Spacer(modifier = Modifier.size(40.dp))
                }

                Spacer(modifier = Modifier.height(16.dp))

                // MAIN PROFILE ONBOARDING / SETUP FLOW

                // Header Introduction & Offline Badge
                    Surface(
                        color = AppTheme.colors.primaryContainer,
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.padding(bottom = 16.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.WifiOff,
                                contentDescription = null,
                                tint = AppTheme.colors.primary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "حساب محلي 100% أوفلاين بدون إنترنت",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = AppTheme.colors.primary
                            )
                        }
                    }

                    Text(
                        text = if (isFirstTime) "معلومات ملفك الشخصي" else "تعديل الملف الشخصي",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = AppTheme.colors.textPrimary,
                        textAlign = TextAlign.Center
                    )

                    Text(
                        text = "اختر اسماً وصورة اختيارية للظهور لأصدقائك عبر شبكة الواي فاي والمش المحلية.",
                        fontSize = 14.sp,
                        color = AppTheme.colors.textSecondary,
                        textAlign = TextAlign.Center,
                        lineHeight = 20.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // AVATAR PREVIEW with Interactive Edit Badge
                    Box(
                        contentAlignment = Alignment.BottomEnd,
                        modifier = Modifier.padding(4.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(110.dp)
                                .clip(CircleShape)
                                .border(3.dp, AppTheme.colors.primary.copy(alpha = 0.3f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            AvatarView(
                                name = name.ifBlank { "أ" },
                                avatarPath = avatarPath,
                                avatarColorIndex = selectedColorIndex,
                                showOnlineBadge = false,
                                size = 110.dp
                            )
                        }

                        // Floating camera badge
                        Surface(
                            shape = CircleShape,
                            color = AppTheme.colors.primary,
                            shadowElevation = 4.dp,
                            modifier = Modifier
                                .size(36.dp)
                                .clickable { galleryLauncher.launch("image/*") }
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.CameraAlt,
                                    contentDescription = "اختيار صورة",
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Photo Options (Camera & Gallery Buttons)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Button(
                            onClick = { cameraLauncher.launch(null) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = AppTheme.colors.surfaceVariant,
                                contentColor = AppTheme.colors.primary
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .height(42.dp)
                                .testTag("profile_camera_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.CameraAlt,
                                contentDescription = "التقاط صورة",
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("التقاط صورة", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }

                        Spacer(modifier = Modifier.width(10.dp))

                        Button(
                            onClick = { galleryLauncher.launch("image/*") },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = AppTheme.colors.surfaceVariant,
                                contentColor = AppTheme.colors.primary
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .height(42.dp)
                                .testTag("profile_gallery_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.PhotoLibrary,
                                contentDescription = "معرض الصور",
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("المعرض", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    if (avatarPath != null) {
                        TextButton(
                            onClick = { avatarPath = null },
                            modifier = Modifier.padding(top = 2.dp)
                        ) {
                            Text(
                                "إزالة الصورة واستخدام لون الحرف",
                                color = AppTheme.colors.error,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Color Monogram Selector
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "أو اختر لونك الرمزي المميز:",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = AppTheme.colors.textPrimary,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )

                        LazyRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            itemsIndexed(AvatarColors) { index, (bgColor, _) ->
                                val isSelected = selectedColorIndex == index
                                Box(
                                    modifier = Modifier
                                        .size(44.dp)
                                        .clip(CircleShape)
                                        .background(bgColor)
                                        .border(
                                            width = if (isSelected) 3.dp else 1.dp,
                                            color = if (isSelected) AppTheme.colors.primary else AppTheme.colors.border,
                                            shape = CircleShape
                                        )
                                        .clickable {
                                            selectedColorIndex = index
                                            avatarPath = null
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (isSelected) {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = "محدد",
                                            tint = AppTheme.colors.primary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(18.dp))

                    // User Display Name Input Field
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "اسمك (الذي سيظهر للآخرين)*",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = AppTheme.colors.textPrimary,
                            modifier = Modifier.padding(bottom = 6.dp)
                        )

                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            placeholder = { Text("أدخل اسمك أو كنيتك...", color = AppTheme.colors.textDisabled) },
                            textStyle = TextStyle(
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = AppTheme.colors.textPrimary
                            ),
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = AppTheme.colors.primary,
                                unfocusedBorderColor = AppTheme.colors.border,
                                focusedContainerColor = AppTheme.colors.surface,
                                unfocusedContainerColor = AppTheme.colors.surface
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("name_input_field")
                        )
                    }

                    Spacer(modifier = Modifier.height(18.dp))

                    // PERSISTENT DEVICE IDENTITY CARD (Addresses user requirement)
                    Card(
                        colors = CardDefaults.cardColors(containerColor = AppTheme.colors.cardBackground),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, AppTheme.colors.border, RoundedCornerShape(16.dp))
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(bottom = 6.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(CircleShape)
                                        .background(AppTheme.colors.primaryContainer),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Fingerprint,
                                        contentDescription = null,
                                        tint = AppTheme.colors.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "المعرّف الرقمي الثابت للجهاز (Device ID)",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = AppTheme.colors.textPrimary
                                )
                            }

                            Text(
                                text = "كود مشفّر فريد وخاص بهاتفك يولد تلقائياً؛ حتى لو حذفت التطبيق وأعدت تثبيته أو غيرت اسمك مستقبلاً، ستظل محادثاتك محفوظة لدى الطرف الآخر وستظهر له كنفس الشخص بدون فقدان السجل.",
                                fontSize = 12.sp,
                                color = AppTheme.colors.textSecondary,
                                lineHeight = 18.sp
                            )

                            if (deviceId.isNotBlank()) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Surface(
                                    color = AppTheme.colors.surfaceVariant,
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            clipboardManager.setText(AnnotatedString(deviceId))
                                            copiedNotice = true
                                        }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = if (deviceId.length > 22) "${deviceId.take(10)}...${deviceId.takeLast(10)}" else deviceId,
                                            fontSize = 12.sp,
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.SemiBold,
                                            color = AppTheme.colors.primary
                                        )
                                        Text(
                                            text = if (copiedNotice) "تم النسخ ✓" else "نسخ الكود",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (copiedNotice) AppTheme.colors.online else AppTheme.colors.textSecondary
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    // Primary Action Button (Create account or save changes)
                    Button(
                        onClick = {
                            val trimmed = name.trim()
                            val finalName = trimmed.ifBlank { "مستخدم جديد" }
                            onSave(finalName, avatarPath, selectedColorIndex, initialIsDeveloper)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = AppTheme.colors.primary),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .testTag("save_profile_button")
                    ) {
                        Text(
                            text = if (isFirstTime) "إنشاء الحساب والمتابعة 🚀" else "حفظ التغييرات ✅",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }

                    if (!isFirstTime) {
                        TextButton(
                            onClick = onDismiss,
                            modifier = Modifier
                                .padding(top = 8.dp)
                                .height(44.dp)
                        ) {
                            Text(
                                text = "إلغاء",
                                fontSize = 15.sp,
                                color = AppTheme.colors.textSecondary,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))
            }
        }
    }
}
