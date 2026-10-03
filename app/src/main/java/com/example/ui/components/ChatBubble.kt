package com.example.ui.components

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.data.local.ChatMessageEntity
import com.example.data.network.FileUtils
import com.example.data.network.PlaybackState
import com.example.ui.theme.AppTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * كاش ذاكرة وسيط فائق السرعة لمنع إعادة توليد صور مصغرات الفيديوهات أثناء التمرير
 */
object VideoThumbnailCache {
    private val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
    private val cacheSize = (maxMemory / 8).coerceAtLeast(1024 * 8) // تخصيص ثمن الذاكرة كحد أقصى للكاش

    val cache = object : android.util.LruCache<String, Bitmap>(cacheSize) {
        override fun sizeOf(key: String, value: Bitmap): Int {
            return value.byteCount / 1024
        }
    }

    fun get(key: String): Bitmap? = cache.get(key)
    fun put(key: String, bitmap: Bitmap) { cache.put(key, bitmap) }
}

@Composable
fun ChatBubble(
    message: ChatMessageEntity,
    onPhotoClick: (String) -> Unit,
    onPlayAudio: ((String) -> Unit)? = null,
    onSeekAudio: ((Float) -> Unit)? = null,
    playbackState: PlaybackState? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val isFromMe = message.isFromMe

    val alignment = if (isFromMe) Alignment.End else Alignment.Start
    val bubbleColor = if (isFromMe) AppTheme.colors.bubbleSender else AppTheme.colors.bubbleReceiver
    val borderColor = if (isFromMe) AppTheme.colors.bubbleSenderBorder else AppTheme.colors.bubbleReceiverBorder
    val primaryTextColor = if (isFromMe) AppTheme.colors.bubbleSenderText else AppTheme.colors.bubbleReceiverText

    val bubbleShape = if (isFromMe) {
        RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 4.dp)
    } else {
        RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 4.dp, bottomEnd = 18.dp)
    }

    val timeFormat = remember { SimpleDateFormat("hh:mm a", Locale.getDefault()) }
    val formattedTime = remember(message.timestamp) { timeFormat.format(Date(message.timestamp)) }

    AnimatedVisibility(
        visible = true,
        enter = fadeIn(tween(250)) + slideInVertically(tween(250)) { it / 4 }
    ) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 4.dp)
                .testTag("chat_bubble_row_${message.id}"),
            horizontalAlignment = alignment
        ) {
            Box(
                modifier = Modifier
                    .widthIn(min = 80.dp, max = 300.dp)
                    .shadow(elevation = 1.dp, shape = bubbleShape)
                    .clip(bubbleShape)
                    .background(bubbleColor)
                    .border(width = 1.dp, color = borderColor, shape = bubbleShape)
                    .padding(horizontal = 10.dp, vertical = 8.dp)
                    .testTag("chat_bubble_content")
            ) {
                Column {
                    // اسم المرسل في الشات الجماعي
                    if (!isFromMe && message.isGroup) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(bottom = 4.dp)
                        ) {
                            Text(
                                text = message.senderName,
                                color = AppTheme.colors.primary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                            if (message.isSenderDeveloper) {
                                Spacer(modifier = Modifier.width(4.dp))
                                DeveloperBadge(isSmall = true)
                            }
                        }
                    } else if (!isFromMe && message.isSenderDeveloper) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(bottom = 4.dp)
                        ) {
                            DeveloperBadge(isSmall = true)
                        }
                    }

                    // معالجة نوع المحتوى
                    when {
                        // 1. التسجيل الصوتي المشفر
                        message.isVoice && !message.filePath.isNullOrBlank() -> {
                            val isThisPlaying = playbackState?.filePath == message.filePath && playbackState.isPlaying
                            val currentProgress = if (playbackState?.filePath == message.filePath) playbackState.progressFraction else 0f
                            val durationText = if (playbackState?.filePath == message.filePath && isThisPlaying) {
                                String.format(Locale.getDefault(), "%02d:%02d", playbackState.currentPositionMs / 1000 / 60, (playbackState.currentPositionMs / 1000) % 60)
                            } else {
                                String.format(Locale.getDefault(), "%02d:%02d", message.audioDurationSeconds / 60, message.audioDurationSeconds % 60)
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(42.dp)
                                        .clip(CircleShape)
                                        .background(AppTheme.colors.primary)
                                        .clickable {
                                            message.filePath.let { path ->
                                                onPlayAudio?.invoke(path)
                                            }
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = if (isThisPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                        contentDescription = if (isThisPlaying) "إيقاف" else "تشغيل",
                                        tint = Color.White,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }

                                Spacer(modifier = Modifier.width(10.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    LinearProgressIndicator(
                                        progress = { currentProgress },
                                        color = AppTheme.colors.primary,
                                        trackColor = AppTheme.colors.border,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(6.dp)
                                            .clip(RoundedCornerShape(3.dp))
                                    )

                                    Spacer(modifier = Modifier.height(4.dp))

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                imageVector = Icons.Default.Mic,
                                                contentDescription = null,
                                                tint = AppTheme.colors.primary,
                                                modifier = Modifier.size(13.dp)
                                            )
                                            Spacer(modifier = Modifier.width(3.dp))
                                            Text(
                                                text = "تسجيل مشفر",
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Medium,
                                                color = AppTheme.colors.textSecondary
                                            )
                                        }

                                        Text(
                                            text = durationText,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = primaryTextColor
                                        )
                                    }
                                }
                            }
                        }

                        // 2. الصور
                        message.isPhoto && !message.photoPath.isNullOrBlank() -> {
                            val file = File(message.photoPath)
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 140.dp, max = 220.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(AppTheme.colors.surfaceVariant)
                                    .clickable { onPhotoClick(message.photoPath) }
                                    .testTag("chat_photo_image")
                            ) {
                                AsyncImage(
                                    model = ImageRequest.Builder(context)
                                        .data(file)
                                        .crossfade(true)
                                        .build(),
                                    contentDescription = "Shared photo",
                                    modifier = Modifier.fillMaxWidth(),
                                    contentScale = ContentScale.Crop
                                )
                            }

                            if (message.text.isNotBlank()) {
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = message.text,
                                    color = primaryTextColor,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Medium,
                                    lineHeight = 22.sp
                                )
                            }
                        }

                        // 3. الفيديو (مع الكاش الذكي فائق السرعة لمنع التقطيع والـ OOM)
                        message.isVideo && !message.filePath.isNullOrBlank() -> {
                            val videoPath = message.filePath
                            var thumbnailBitmap by remember(videoPath) {
                                mutableStateOf(VideoThumbnailCache.get(videoPath))
                            }

                            LaunchedEffect(videoPath) {
                                if (thumbnailBitmap == null) {
                                    withContext(Dispatchers.IO) {
                                        val cached = VideoThumbnailCache.get(videoPath)
                                        if (cached != null) {
                                            thumbnailBitmap = cached
                                        } else {
                                            val rawBitmap = FileUtils.getVideoThumbnail(videoPath)
                                            if (rawBitmap != null) {
                                                // تقليص أبعاد الإطار لـ 640px كحد أقصى لحماية الرام تماماً
                                                val scaled = if (rawBitmap.width > 640 || rawBitmap.height > 640) {
                                                    val ratio = rawBitmap.width.toFloat() / rawBitmap.height.toFloat()
                                                    val (w, h) = if (ratio > 1f) {
                                                        Pair(640, (640 / ratio).toInt())
                                                    } else {
                                                        Pair((640 * ratio).toInt(), 640)
                                                    }
                                                    Bitmap.createScaledBitmap(rawBitmap, w.coerceAtLeast(1), h.coerceAtLeast(1), true)
                                                } else {
                                                    rawBitmap
                                                }
                                                VideoThumbnailCache.put(videoPath, scaled)
                                                thumbnailBitmap = scaled
                                            }
                                        }
                                    }
                                }
                            }

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(170.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color(0xFF1E293B))
                                    .clickable {
                                        FileUtils.openFileWithSystemApp(context, message.filePath, message.mimeType)
                                    }
                                    .testTag("chat_video_player_box"),
                                contentAlignment = Alignment.Center
                            ) {
                                if (thumbnailBitmap != null) {
                                    AsyncImage(
                                        model = thumbnailBitmap,
                                        contentDescription = "Video Thumbnail",
                                        modifier = Modifier.fillMaxWidth(),
                                        contentScale = ContentScale.Crop
                                    )
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(170.dp)
                                            .background(
                                                Brush.verticalGradient(
                                                    colors = listOf(Color.Transparent, Color(0x99000000))
                                                )
                                            )
                                    )
                                }

                                Box(
                                    modifier = Modifier
                                        .size(54.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xCC10B981))
                                        .border(2.dp, Color.White, CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.PlayArrow,
                                        contentDescription = "Play Video",
                                        tint = Color.White,
                                        modifier = Modifier.size(36.dp)
                                    )
                                }

                                Row(
                                    modifier = Modifier
                                        .align(Alignment.BottomStart)
                                        .fillMaxWidth()
                                        .padding(horizontal = 8.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Default.Videocam,
                                            contentDescription = null,
                                            tint = Color.White,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = message.fileName ?: "فيديو",
                                            color = Color.White,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.widthIn(max = 140.dp)
                                        )
                                    }

                                    if (message.fileSize > 0) {
                                        Text(
                                            text = FileUtils.formatFileSize(message.fileSize),
                                            color = Color.White.copy(alpha = 0.9f),
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                }
                            }

                            if (message.text.isNotBlank()) {
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = message.text,
                                    color = primaryTextColor,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }

                        // 4. المستندات والملفات العامة
                        message.isFile && !message.filePath.isNullOrBlank() -> {
                            val fileName = message.fileName ?: "ملف مرفق"
                            val extension = fileName.substringAfterLast('.', "").uppercase()
                            val (fileIcon, iconColor) = getFileIconAndColor(extension)

                            Surface(
                                color = if (isFromMe) AppTheme.colors.surfaceVariant else AppTheme.colors.surfaceElevated,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        FileUtils.openFileWithSystemApp(context, message.filePath, message.mimeType)
                                    }
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(44.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(iconColor.copy(alpha = 0.15f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = fileIcon,
                                            contentDescription = null,
                                            tint = iconColor,
                                            modifier = Modifier.size(26.dp)
                                        )
                                    }

                                    Spacer(modifier = Modifier.width(10.dp))

                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = fileName,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = AppTheme.colors.textPrimary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            if (extension.isNotBlank()) {
                                                Text(
                                                    text = extension,
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = iconColor
                                                )
                                                Spacer(modifier = Modifier.width(6.dp))
                                            }
                                            if (message.fileSize > 0) {
                                                Text(
                                                    text = FileUtils.formatFileSize(message.fileSize),
                                                    fontSize = 11.sp,
                                                    color = AppTheme.colors.textSecondary
                                                )
                                            }
                                        }
                                    }

                                    Icon(
                                        imageVector = Icons.Default.OpenInNew,
                                        contentDescription = "Open",
                                        tint = AppTheme.colors.textSecondary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }

                            if (message.text.isNotBlank()) {
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = message.text,
                                    color = primaryTextColor,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }

                        // 5. الرسائل النصية العادية
                        else -> {
                            Text(
                                text = message.text,
                                color = primaryTextColor,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Normal,
                                lineHeight = 22.sp,
                                modifier = Modifier.testTag("message_text")
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    // وقت الرسالة ومؤشر القراءة
                    Row(
                        modifier = Modifier.align(Alignment.End),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.End
                    ) {
                        Text(
                            text = formattedTime,
                            color = AppTheme.colors.textSecondary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )

                        if (isFromMe) {
                            Spacer(modifier = Modifier.width(4.dp))
                            ReceiptIndicator(status = message.status)
                        }
                    }
                }
            }
        }
    }
}

private fun getFileIconAndColor(extension: String): Pair<ImageVector, Color> {
    return when (extension.lowercase()) {
        "pdf" -> Pair(Icons.Default.PictureAsPdf, Color(0xFFE11D48))
        "zip", "rar", "7z", "tar", "gz" -> Pair(Icons.Default.FolderZip, Color(0xFFF59E0B))
        "mp3", "m4a", "wav", "aac", "ogg", "flac" -> Pair(Icons.Default.MusicNote, Color(0xFF8B5CF6))
        "mp4", "mkv", "avi", "mov", "webm" -> Pair(Icons.Default.PlayCircle, Color(0xFF10B981))
        "doc", "docx", "txt", "rtf" -> Pair(Icons.Default.Description, Color(0xFF2563EB))
        "xls", "xlsx", "csv" -> Pair(Icons.Default.Description, Color(0xFF059669))
        "ppt", "pptx" -> Pair(Icons.Default.Description, Color(0xFFEA580C))
        else -> Pair(Icons.Default.Description, Color(0xFF64748B))
    }
}