package com.example.ui.components

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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Groups
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.ui.theme.AvatarColors
import com.example.ui.theme.BorderLight
import com.example.ui.theme.PrimaryGreen
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

@Composable
fun CreateGroupDialog(
    onDismiss: () -> Unit,
    onCreate: (name: String, description: String, colorIndex: Int) -> Unit
) {
    var groupName by remember { mutableStateOf("") }
    var groupDescription by remember { mutableStateOf("") }
    var selectedColorIndex by remember { mutableIntStateOf(0) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .clip(RoundedCornerShape(24.dp))
                .testTag("create_group_dialog"),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFE8F5E9)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Groups,
                        contentDescription = null,
                        tint = PrimaryGreen,
                        modifier = Modifier.size(30.dp)
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = "إنشاء مجموعة جديدة",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = PrimaryGreen
                )

                Text(
                    text = "ستظهر المجموعة لجميع المتصلين على نفس شبكة الواي فاي",
                    fontSize = 13.sp,
                    color = TextSecondary,
                    modifier = Modifier.padding(top = 4.dp, bottom = 14.dp)
                )

                OutlinedTextField(
                    value = groupName,
                    onValueChange = {
                        groupName = it
                        errorMessage = null
                    },
                    label = { Text("اسم المجموعة (مثال: العائلة 👨‍👩‍👧‍👦)", fontSize = 14.sp) },
                    textStyle = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TextPrimary),
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = PrimaryGreen,
                        unfocusedBorderColor = BorderLight,
                        focusedLabelColor = PrimaryGreen
                    ),
                    modifier = Modifier.fillMaxWidth().testTag("group_name_input")
                )

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = groupDescription,
                    onValueChange = { groupDescription = it },
                    label = { Text("وصف مختصر للمجموعة (اختياري)", fontSize = 14.sp) },
                    textStyle = TextStyle(fontSize = 15.sp, color = TextPrimary),
                    maxLines = 2,
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = PrimaryGreen,
                        unfocusedBorderColor = BorderLight,
                        focusedLabelColor = PrimaryGreen
                    ),
                    modifier = Modifier.fillMaxWidth().testTag("group_desc_input")
                )

                if (errorMessage != null) {
                    Text(
                        text = errorMessage!!,
                        color = Color(0xFFD32F2F),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Color picker
                Text(
                    text = "لون شارة المجموعة:",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary,
                    modifier = Modifier.align(Alignment.Start).padding(bottom = 6.dp)
                )

                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    itemsIndexed(AvatarColors) { index, (bgColor, _) ->
                        val isSelected = selectedColorIndex == index
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(bgColor)
                                .border(
                                    width = if (isSelected) 3.dp else 1.dp,
                                    color = if (isSelected) PrimaryGreen else BorderLight,
                                    shape = CircleShape
                                )
                                .clickable { selectedColorIndex = index },
                            contentAlignment = Alignment.Center
                        ) {
                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = "Selected",
                                    tint = PrimaryGreen,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                Button(
                    onClick = {
                        if (groupName.trim().isBlank()) {
                            errorMessage = "يرجى كتابة اسم للمجموعة"
                        } else {
                            onCreate(groupName.trim(), groupDescription.trim(), selectedColorIndex)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryGreen),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().height(48.dp).testTag("confirm_create_group_button")
                ) {
                    Text("إنشاء المجموعة الآن ✨", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }

                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.padding(top = 4.dp).height(40.dp)
                ) {
                    Text("إلغاء", fontSize = 15.sp, color = TextSecondary)
                }
            }
        }
    }
}
