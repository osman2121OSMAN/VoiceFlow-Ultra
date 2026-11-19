package com.example.voiceflow.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

private val Context.voiceFlowSoundDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "voiceflow_sound_prefs",
)

data class VoiceFlowSoundSettings(
    val dictationSoundsEnabled: Boolean = false,
    val appSoundVolumePercent: Int = DEFAULT_VOLUME_PERCENT,
    val muteSystemRecognizerStream: Boolean = false,
) {
    companion object {
        const val DEFAULT_VOLUME_PERCENT = 60
    }
}

class SoundSettingsStore(
    private val dataStore: DataStore<Preferences>,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    constructor(context: Context) : this(context.applicationContext.voiceFlowSoundDataStore)

    @Volatile
    private var cachedSettings: VoiceFlowSoundSettings = VoiceFlowSoundSettings()

    val settingsFlow: Flow<VoiceFlowSoundSettings> = dataStore.data.map { prefs ->
        fromPreferences(prefs)
    }

    init {
        scope.launch {
            refreshFromDataStore()
        }
    }

    fun getCached(): VoiceFlowSoundSettings = cachedSettings

    /**
     * Reads current DataStore values synchronously (bounded by a short timeout so
     * overlay open/close paths on main thread remain responsive).
     */
    fun refreshAndGet(): VoiceFlowSoundSettings {
        runBlocking(Dispatchers.IO) {
            withTimeoutOrNull(150L) {
                refreshFromDataStore()
            }
        }
        return cachedSettings
    }

    fun setDictationSoundsEnabled(enabled: Boolean) {
        cachedSettings = cachedSettings.copy(dictationSoundsEnabled = enabled)
        scope.launch {
            dataStore.edit { prefs ->
                prefs[KEY_DICTATION_SOUNDS_ENABLED] = enabled
            }
        }
    }

    fun setAppSoundVolumePercent(percent: Int) {
        val clamped = percent.coerceIn(0, 100)
        cachedSettings = cachedSettings.copy(appSoundVolumePercent = clamped)
        scope.launch {
            dataStore.edit { prefs ->
                prefs[KEY_APP_SOUND_VOLUME_PERCENT] = clamped
            }
        }
    }

    fun setMuteSystemRecognizerStream(enabled: Boolean) {
        cachedSettings = cachedSettings.copy(muteSystemRecognizerStream = enabled)
        scope.launch {
            dataStore.edit { prefs ->
                prefs[KEY_MUTE_SYSTEM_RECOGNIZER_STREAM] = enabled
            }
        }
    }

    private suspend fun refreshFromDataStore() {
        val prefs = dataStore.data.firstOrNull() ?: return
        cachedSettings = fromPreferences(prefs)
    }

    private fun fromPreferences(prefs: Preferences): VoiceFlowSoundSettings {
        return VoiceFlowSoundSettings(
            dictationSoundsEnabled = prefs[KEY_DICTATION_SOUNDS_ENABLED] ?: false,
            appSoundVolumePercent = (prefs[KEY_APP_SOUND_VOLUME_PERCENT]
                ?: VoiceFlowSoundSettings.DEFAULT_VOLUME_PERCENT).coerceIn(0, 100),
            muteSystemRecognizerStream = prefs[KEY_MUTE_SYSTEM_RECOGNIZER_STREAM] ?: false,
        )
    }

    companion object {
        private val KEY_DICTATION_SOUNDS_ENABLED = booleanPreferencesKey("dictation_sounds_enabled")
        private val KEY_APP_SOUND_VOLUME_PERCENT = intPreferencesKey("app_sound_volume_percent")
        private val KEY_MUTE_SYSTEM_RECOGNIZER_STREAM = booleanPreferencesKey("mute_system_recognizer_stream")
    }
}
