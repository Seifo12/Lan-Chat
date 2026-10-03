package com.example.data.local

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.provider.Settings
import java.security.MessageDigest
import java.util.UUID

class UserPreferences(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("lan_chat_user_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_DEVICE_ID = "key_device_id"
        private const val KEY_DISPLAY_NAME = "key_display_name"
        private const val KEY_AVATAR_PATH = "key_avatar_path"
        private const val KEY_AVATAR_COLOR_INDEX = "key_avatar_color_index"
        private const val KEY_IS_SETUP_COMPLETED = "key_is_setup_completed"
        private const val KEY_HAPTIC_FEEDBACK = "key_haptic_feedback"
        private const val KEY_SOUND_NOTIFICATION = "key_sound_notification"
        private const val KEY_LARGE_FONT_MODE = "key_large_font_mode"
        private const val KEY_IS_DEVELOPER = "key_is_developer"
        private const val KEY_THEME_MODE = "key_theme_mode"
        private const val KEY_MESH_MODE_ENABLED = "key_mesh_mode_enabled"
    }

    val appVersionCode: Int = 2
    val appVersionName: String = "2.0.0"

    val deviceId: String
        @SuppressLint("HardwareIds")
        get() {
            var id = prefs.getString(KEY_DEVICE_ID, null)
            if (id.isNullOrBlank()) {
                try {
                    val androidId = Settings.Secure.getString(
                        context.contentResolver,
                        Settings.Secure.ANDROID_ID
                    )
                    
                    // بصمة عتادية ثابتة لا تتغير حتى مع مسح بيانات التطبيق (Clear Data)
                    val rawFingerprint = if (!androidId.isNullOrBlank() && androidId != "9774d56d682e549c") {
                        "dev_${androidId}_${Build.MANUFACTURER}_${Build.BRAND}_${Build.BOARD}"
                    } else {
                        "dev_${Build.MANUFACTURER}_${Build.MODEL}_${Build.BRAND}_${Build.HARDWARE}"
                    }
                    
                    val sha = MessageDigest.getInstance("SHA-256")
                    val shaBytes = sha.digest(rawFingerprint.toByteArray(Charsets.UTF_8))
                    id = shaBytes.joinToString("") { "%02x".format(it) }.substring(0, 16)
                } catch (e: Exception) {
                    id = UUID.randomUUID().toString().replace("-", "").substring(0, 16)
                }
                prefs.edit().putString(KEY_DEVICE_ID, id).apply()
            }
            return id
        }

    var displayName: String
        get() = prefs.getString(KEY_DISPLAY_NAME, "أنا (Me)") ?: "أنا (Me)"
        set(value) = prefs.edit().putString(KEY_DISPLAY_NAME, value).apply()

    var isDeveloper: Boolean
        get() = prefs.getBoolean(KEY_IS_DEVELOPER, false)
        set(value) = prefs.edit().putBoolean(KEY_IS_DEVELOPER, value).apply()

    var avatarPath: String?
        get() = prefs.getString(KEY_AVATAR_PATH, null)
        set(value) = prefs.edit().putString(KEY_AVATAR_PATH, value).apply()

    var avatarColorIndex: Int
        get() = prefs.getInt(KEY_AVATAR_COLOR_INDEX, 0)
        set(value) = prefs.edit().putInt(KEY_AVATAR_COLOR_INDEX, value).apply()

    var isSetupCompleted: Boolean
        get() = prefs.getBoolean(KEY_IS_SETUP_COMPLETED, false)
        set(value) = prefs.edit().putBoolean(KEY_IS_SETUP_COMPLETED, value).apply()

    var isHapticEnabled: Boolean
        get() = prefs.getBoolean(KEY_HAPTIC_FEEDBACK, true)
        set(value) = prefs.edit().putBoolean(KEY_HAPTIC_FEEDBACK, value).apply()

    var isSoundEnabled: Boolean
        get() = prefs.getBoolean(KEY_SOUND_NOTIFICATION, true)
        set(value) = prefs.edit().putBoolean(KEY_SOUND_NOTIFICATION, value).apply()

    var isLargeFontMode: Boolean
        get() = prefs.getBoolean(KEY_LARGE_FONT_MODE, true)
        set(value) = prefs.edit().putBoolean(KEY_LARGE_FONT_MODE, value).apply()

    var themeMode: String
        get() = prefs.getString(KEY_THEME_MODE, "DARK") ?: "DARK"
        set(value) = prefs.edit().putString(KEY_THEME_MODE, value).apply()

    var isMeshModeEnabled: Boolean
        get() = prefs.getBoolean(KEY_MESH_MODE_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_MESH_MODE_ENABLED, value).apply()
}