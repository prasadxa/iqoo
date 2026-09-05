package com.aasra.tools

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.TextStyle
import java.util.Locale

/**
 * get_time, get_date, set_volume, flashlight (PLAN 6.1). All fully offline.
 *
 * Spoken replies are pre-formatted here (not by the LLM) so the local model
 * stays small: time/date answers are deterministic strings in the user's
 * language. Pass "hi" for Hindi/Hinglish phrasing, anything else for English.
 *
 * Android 13+ note: setStreamVolume on STREAM_MUSIC follows the per-app /
 * spatial-audio routing of the device; we only set the music-stream index,
 * which is what the TTS queue plays on.
 */
class SystemTools(private val context: Context) {

    fun getTime(lang: String = "en"): ToolResult {
        val now = LocalTime.now()
        val h24 = now.hour % 12
        val h12 = if (h24 == 0) 12 else h24
        val spoken = if (lang.startsWith("hi")) {
            "Abhi ${hindiPeriod(now.hour)} ke $h12 bajkar ${now.minute} minute hue hain."
        } else {
            val ampm = if (now.hour < 12) "in the morning" else if (now.hour < 17) "in the afternoon" else "in the evening"
            "It is $h12:${now.minute.toString().padStart(2, '0')} $ampm."
        }
        return ToolResult(true, spoken)
    }

    fun getDate(lang: String = "en"): ToolResult {
        val today = LocalDate.now()
        val spoken = if (lang.startsWith("hi")) {
            val day = HINDI_DAYS[today.dayOfWeek.value % 7]
            "Aaj $day hai, ${today.dayOfMonth} ${HINDI_MONTHS[today.monthValue - 1]} ${today.year}."
        } else {
            val day = today.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
            val month = today.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
            "Today is $day, $month ${today.dayOfMonth}, ${today.year}."
        }
        return ToolResult(true, spoken)
    }

    /** [level] 0 (silent) .. 10 (loudest); coerced. */
    fun setVolume(level: Int): ToolResult {
        val am = context.getSystemService(AudioManager::class.java)
            ?: return ToolResult(false, "I could not reach the volume controls on this phone.")
        val clamped = level.coerceIn(0, 10)
        return try {
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val idx = (clamped / 10f * max).toInt().coerceIn(0, max)
            am.setStreamVolume(AudioManager.STREAM_MUSIC, idx, AudioManager.FLAG_SHOW_UI)
            val spoken = if (clamped == 0) "Sound is off." else "Volume set to $clamped out of 10."
            ToolResult(true, spoken)
        } catch (e: SecurityException) {
            ToolResult(false, "Your phone did not let me change the volume. Please use the side buttons.")
        } catch (e: Exception) {
            ToolResult(false, "I could not change the volume. Please use the side buttons.")
        }
    }

    fun setFlashlight(on: Boolean): ToolResult {
        val cm = context.getSystemService(CameraManager::class.java)
            ?: return ToolResult(false, "This phone has no torch I can switch.")
        return try {
            val id = cm.cameraIdList.firstOrNull { id ->
                cm.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return ToolResult(false, "This phone has no torch I can switch.")
            cm.setTorchMode(id, on)
            ToolResult(true, if (on) "Torch is on." else "Torch is off.")
        } catch (e: SecurityException) {
            // CAMERA permission needed for setTorchMode on some OEMs.
            ToolResult(false, "I need the camera permission to use the torch. Please allow it in Settings.")
        } catch (e: Exception) {
            ToolResult(false, "I could not reach the torch on this phone.")
        }
    }

    companion object {
        private val HINDI_DAYS = arrayOf("Ravivaar", "Somvaar", "Mangalvaar", "Budhvaar", "Guruvaar", "Shukravaar", "Shanivaar")
        private val HINDI_MONTHS = arrayOf(
            "January", "February", "March", "April", "May", "June",
            "July", "August", "September", "October", "November", "December",
        )

        private fun hindiPeriod(hour: Int): String = when (hour) {
            in 4..11 -> "subah"
            in 12..16 -> "dopahar"
            in 17..20 -> "shaam"
            else -> "raat"
        }
    }
}
