package com.example.voiceflow.overlay

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
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

private val Context.voiceFlowOverlayDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "voiceflow_overlay_prefs",
)

data class SavedOverlayPosition(
    val x: Int,
    val y: Int,
)

class OverlayPositionStore(
    private val dataStore: DataStore<Preferences>,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    constructor(context: Context) : this(context.applicationContext.voiceFlowOverlayDataStore)

    @Volatile
    private var cachedPosition: SavedOverlayPosition? = null

    @Volatile
    private var loaded: Boolean = false

    init {
        scope.launch {
            loadFromStore()
        }
    }

    fun getPosition(): SavedOverlayPosition? {
        if (!loaded) {
            runBlocking(Dispatchers.IO) {
                withTimeoutOrNull(500L) {
                    loadFromStore()
                }
            }
        }
        return cachedPosition
    }

    fun savePosition(x: Int, y: Int) {
        val pos = SavedOverlayPosition(x, y)
        cachedPosition = pos
        loaded = true
        scope.launch {
            dataStore.edit { prefs ->
                prefs[KEY_HAS_SAVED_TOP_LEFT] = true
                prefs[KEY_WINDOW_X] = x
                prefs[KEY_WINDOW_Y] = y
            }
        }
    }

    private suspend fun loadFromStore() {
        val prefs = dataStore.data.firstOrNull()
        if (prefs != null && prefs[KEY_HAS_SAVED_TOP_LEFT] == true) {
            val x = prefs[KEY_WINDOW_X] ?: 0
            val y = prefs[KEY_WINDOW_Y] ?: 0
            cachedPosition = SavedOverlayPosition(x, y)
        }
        loaded = true
    }

    companion object {
        private val KEY_HAS_SAVED_TOP_LEFT = booleanPreferencesKey("has_saved_top_left")
        private val KEY_WINDOW_X = intPreferencesKey("saved_window_x")
        private val KEY_WINDOW_Y = intPreferencesKey("saved_window_y")
    }
}
