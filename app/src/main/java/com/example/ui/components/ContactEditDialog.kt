package com.example.ui.components

import android.graphics.Bitmap
import android.net.Uri
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.local.ContactEntity
import com.example.data.network.ImageUtils
import com.example.ui.theme.AvatarColors
import com.example.ui.theme.BorderLight
import com.example.ui.theme.PrimaryGreen
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

@Composable
fun ContactEditDialog(
    contact: ContactEntity,
    onDismiss: () -> Unit,
    onSave: (nickname: String?, avatarPath: String?) -> Unit
) {
    val context = LocalContext.current
    var nickname by remember { mutableStateOf(contact.customNickname ?: contact.displayName) }
    var avatarPath by remember { mutableStateOf(contact.avatarPath) }
    var selectedColorIndex by remember { mutableIntStateOf(contact.avatarColorIndex) }

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicturePreview()
    ) { bitmap: Bitmap? ->
        if (bitmap != null) {
            val savedFile = ImageUtils.saveBitmapToFile(context, bitmap, "contact_${contact.deviceId}_")
            if (savedFile != null) {
                avatarPath = savedFile.absolutePath
            }
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            val base64 = ImageUtils.uriToCompressedBase64(context, uri, 600, 80)
            if (base64 != null) {
                val file = ImageUtils.base64ToImageFile(context, base64, "contact_${contact.deviceId}_")
                if (file != null) {
                    avatarPath = file.absolutePath
                }
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .clip(RoundedCornerShape(28.dp))
                .testTag("contact_edit_dialog"),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "تخصيص صورة واسم جهة الاتصال",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Black,
                    color = PrimaryGreen
                )

                Text(
                    text = "يمكنك تعيين صورة خاصة أو كنية تميز بها هذا الشخص",
                    fontSize = 15.sp,
                    color = TextSecondary,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(top = 4.dp, bottom = 18.dp)
                )

                AvatarView(
                    name = nickname.ifBlank { contact.displayName },
                    avatarPath = avatarPath,
                    avatarColorIndex = selectedColorIndex,
                    isOnline = contact.isOnline,
                    size = 96.dp
                )

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    Button(
                        onClick = { cameraLauncher.launch(null) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF1F8E9)),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .height(56.dp)
                            .testTag("contact_camera_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.CameraAlt,
                            contentDescription = "التقاط صورة",
                            tint = PrimaryGreen,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("التقاط صورة", color = PrimaryGreen, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Button(
                        onClick = { galleryLauncher.launch("image/*") },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF1F8E9)),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .height(56.dp)
                            .testTag("contact_gallery_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.PhotoLibrary,
                            contentDescription = "المعرض",
                            tint = PrimaryGreen,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("من المعرض", color = PrimaryGreen, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                }

                if (avatarPath != null) {
                    TextButton(
                        onClick = { avatarPath = null },
                        modifier = Modifier.padding(top = 8.dp)
                    ) {
                        Text("إعادة التعيين للحرف الملون", color = Color(0xFFD32F2F), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                OutlinedTextField(
                    value = nickname,
                    onValueChange = { nickname = it },
                    label = { Text("اسم / كنية جهة الاتصال", fontSize = 16.sp) },
                    textStyle = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold, color = TextPrimary),
                    singleLine = true,
                    shape = RoundedCornerShape(18.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = PrimaryGreen,
                        unfocusedBorderColor = BorderLight,
                        focusedLabelColor = PrimaryGreen
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("contact_nickname_input")
                )

                Spacer(modifier = Modifier.height(24.dp))

                Button(
                    onClick = {
                        onSave(nickname.ifBlank { null }, avatarPath)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryGreen),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(60.dp)
                        .testTag("save_contact_customization_button")
                ) {
                    Text("حفظ التخصيص", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }

                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .height(48.dp)
                ) {
                    Text("إلغاء", fontSize = 18.sp, color = TextSecondary, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
