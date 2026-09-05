package com.aasra.companion.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.aasra.companion.pipeline.RunMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.prefsStore by preferencesDataStore(name = "aasra_prefs")

/** Voice choice maps to cloud TTS voices: male -> kabir, female -> riya. */
enum class VoiceChoice { MALE, FEMALE }

/** UI language for prompts and answers. */
enum class AppLanguage { ENGLISH, HINDI, HINGLISH }

data class UserPrefs(
    val onboardingDone: Boolean = false,
    val language: AppLanguage = AppLanguage.HINGLISH,
    val voice: VoiceChoice = VoiceChoice.MALE,
    val userName: String = "",
    /** "Name|Phone" entries, max 3, first is the primary SOS contact. */
    val emergencyContacts: Set<String> = emptySet(),
    val runMode: RunMode = RunMode.HYBRID,
    val speechSpeed: Float = 1.0f,
    val wakeWordEnabled: Boolean = true,
    /** Conversations that touched the cloud this month (from X-RateLimit headers). */
    val cloudUsageCount: Int = 0
)

private object Keys {
    val ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
    val LANGUAGE = stringPreferencesKey("language")
    val VOICE = stringPreferencesKey("voice")
    val USER_NAME = stringPreferencesKey("user_name")
    val EMERGENCY_CONTACTS = stringSetPreferencesKey("emergency_contacts")
    val RUN_MODE = stringPreferencesKey("run_mode")
    val SPEECH_SPEED = floatPreferencesKey("speech_speed")
    val WAKE_WORD = booleanPreferencesKey("wake_word")
    val CLOUD_USAGE = intPreferencesKey("cloud_usage")
}

/** Single owner of all user settings; every screen collects from [prefs]. */
class UserPreferencesRepository(private val context: Context) {

    val prefs: Flow<UserPrefs> = context.prefsStore.data.map { p ->
        UserPrefs(
            onboardingDone = p[Keys.ONBOARDING_DONE] ?: false,
            language = runCatching { AppLanguage.valueOf(p[Keys.LANGUAGE] ?: "") }
                .getOrDefault(AppLanguage.HINGLISH),
            voice = runCatching { VoiceChoice.valueOf(p[Keys.VOICE] ?: "") }
                .getOrDefault(VoiceChoice.MALE),
            userName = p[Keys.USER_NAME] ?: "",
            emergencyContacts = p[Keys.EMERGENCY_CONTACTS] ?: emptySet(),
            runMode = runCatching { RunMode.valueOf(p[Keys.RUN_MODE] ?: "") }
                .getOrDefault(RunMode.HYBRID),
            speechSpeed = p[Keys.SPEECH_SPEED] ?: 1.0f,
            wakeWordEnabled = p[Keys.WAKE_WORD] ?: true,
            cloudUsageCount = p[Keys.CLOUD_USAGE] ?: 0
        )
    }

    suspend fun setOnboardingDone(done: Boolean) {
        context.prefsStore.edit { it[Keys.ONBOARDING_DONE] = done }
    }

    suspend fun setLanguage(language: AppLanguage) {
        context.prefsStore.edit { it[Keys.LANGUAGE] = language.name }
    }

    suspend fun setVoice(voice: VoiceChoice) {
        context.prefsStore.edit { it[Keys.VOICE] = voice.name }
    }

    suspend fun setUserName(name: String) {
        context.prefsStore.edit { it[Keys.USER_NAME] = name }
    }

    suspend fun setEmergencyContacts(contacts: Set<String>) {
        context.prefsStore.edit { it[Keys.EMERGENCY_CONTACTS] = contacts.take(3).toSet() }
    }

    suspend fun setRunMode(mode: RunMode) {
        context.prefsStore.edit { it[Keys.RUN_MODE] = mode.name }
    }

    suspend fun setSpeechSpeed(speed: Float) {
        context.prefsStore.edit { it[Keys.SPEECH_SPEED] = speed.coerceIn(0.5f, 1.5f) }
    }

    suspend fun setWakeWordEnabled(enabled: Boolean) {
        context.prefsStore.edit { it[Keys.WAKE_WORD] = enabled }
    }

    suspend fun incrementCloudUsage() {
        context.prefsStore.edit { it[Keys.CLOUD_USAGE] = (it[Keys.CLOUD_USAGE] ?: 0) + 1 }
    }
}
