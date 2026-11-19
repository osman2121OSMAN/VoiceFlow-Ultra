package com.example.voiceflow.settings

import android.content.Context
import android.media.AudioManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import com.example.ui.theme.VoiceFlowTheme
import com.example.voiceflow.ui.SettingsScreen
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SoundSettingsAndVolumeTest {

    @get:Rule
    val composeRule = createComposeRule()

    private class FakeNotificationStreamGateway(
        var currentVolume: Int = 5,
        var minVolume: Int = 0,
    ) : NotificationStreamGateway {
        val history = mutableListOf<Int>()

        override fun getNotificationVolume(): Int = currentVolume
        override fun getMinNotificationVolume(): Int = minVolume
        override fun setNotificationVolume(volume: Int) {
            currentVolume = volume
            history.add(volume)
        }
    }

    @Test
    fun `SoundSettingsStore persists switches and volume slider with DataStore and reads cleanly`() {
        val tempFile = File.createTempFile("vf_sound_test", ".preferences_pb")
        tempFile.deleteOnExit()

        val scope1 = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val ds1 = PreferenceDataStoreFactory.create(scope = scope1) { tempFile }
            val store1 = SoundSettingsStore(ds1, scope1)
            val initial = store1.refreshAndGet()
            assertFalse(initial.dictationSoundsEnabled)
            assertEquals(VoiceFlowSoundSettings.DEFAULT_VOLUME_PERCENT, initial.appSoundVolumePercent)
            assertFalse(initial.muteSystemRecognizerStream)

            store1.setDictationSoundsEnabled(true)
            store1.setAppSoundVolumePercent(85)
            store1.setMuteSystemRecognizerStream(true)
            Thread.sleep(200)
        } finally {
            runBlocking { scope1.coroutineContext[Job]?.cancelAndJoin() }
        }

        val scope2 = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val ds2 = PreferenceDataStoreFactory.create(scope = scope2) { tempFile }
            val store2 = SoundSettingsStore(ds2, scope2)
            val reloaded = store2.refreshAndGet()
            assertTrue(reloaded.dictationSoundsEnabled)
            assertEquals(85, reloaded.appSoundVolumePercent)
            assertTrue(reloaded.muteSystemRecognizerStream)
        } finally {
            runBlocking { scope2.coroutineContext[Job]?.cancelAndJoin() }
        }
    }

    @Test
    fun `NotificationVolumeController lowers STREAM_NOTIFICATION during listening and restores after`() {
        val gateway = FakeNotificationStreamGateway(currentVolume = 6, minVolume = 0)
        val controller = NotificationVolumeController(gateway)

        // When OFF, volume must never change
        controller.onListeningStarted(muteSystemStream = false)
        assertEquals(6, gateway.currentVolume)
        assertFalse(controller.isCurrentlyLowered)

        // When ON, lowers to min and keeps original saved even if called again mid-session
        controller.onListeningStarted(muteSystemStream = true)
        assertEquals(0, gateway.currentVolume)
        assertTrue(controller.isCurrentlyLowered)
        controller.onListeningStarted(muteSystemStream = true)
        assertEquals(0, gateway.currentVolume)

        // Restore brings back original volume 6
        controller.onListeningStopped()
        assertEquals(6, gateway.currentVolume)
        assertFalse(controller.isCurrentlyLowered)
        assertEquals(listOf(0, 0, 6), gateway.history)
    }

    @Test
    fun `AndroidNotificationStreamGateway interacts safely with AudioManager STREAM_NOTIFICATION`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val am = context.getSystemService(AudioManager::class.java)
        am.setStreamVolume(AudioManager.STREAM_NOTIFICATION, 4, 0)

        val controller = NotificationVolumeController(AndroidNotificationStreamGateway(am))
        controller.onListeningStarted(muteSystemStream = true)
        assertEquals(0, am.getStreamVolume(AudioManager.STREAM_NOTIFICATION))

        controller.onListeningStopped()
        assertEquals(4, am.getStreamVolume(AudioManager.STREAM_NOTIFICATION))
    }

    @Test
    fun `AppCueSoundPlayer plays nothing when Dikte sesleri is OFF or volume is 0`() {
        val player = AppCueSoundPlayer()
        assertFalse(player.shouldPlaySound(VoiceFlowSoundSettings(dictationSoundsEnabled = false, appSoundVolumePercent = 80)))
        assertFalse(player.shouldPlaySound(VoiceFlowSoundSettings(dictationSoundsEnabled = true, appSoundVolumePercent = 0)))
        assertTrue(player.shouldPlaySound(VoiceFlowSoundSettings(dictationSoundsEnabled = true, appSoundVolumePercent = 35)))
    }

    @Test
    fun `SettingsScreen renders all Turkish controls, warning note, and dispatches toggles`() {
        var dictationSoundsToggled: Boolean? = null
        var muteStreamToggled: Boolean? = null
        var backedOut = false

        composeRule.setContent {
            VoiceFlowTheme {
                SettingsScreen(
                    settings = VoiceFlowSoundSettings(
                        dictationSoundsEnabled = false,
                        appSoundVolumePercent = 45,
                        muteSystemRecognizerStream = false,
                    ),
                    onDictationSoundsToggle = { dictationSoundsToggled = it },
                    onVolumePercentChange = {},
                    onMuteSystemRecognizerToggle = { muteStreamToggled = it },
                    onBack = { backedOut = true },
                )
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithText("Ayarlar").assertIsDisplayed()
        composeRule.onNodeWithText("Dikte sesleri").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Ses seviyesi").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("%45").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Sistem tanıyıcı sesini kıs").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Bu, diğer bildirim seslerini de etkiler.").performScrollTo().assertIsDisplayed()

        composeRule.onNodeWithTag("switch_dictation_sounds").performScrollTo().assertIsOff().performClick()
        composeRule.onNodeWithTag("switch_mute_system_recognizer").performScrollTo().assertIsOff().performClick()
        composeRule.onNodeWithTag("settings_back_button").performClick()

        composeRule.waitForIdle()
        assertEquals(true, dictationSoundsToggled)
        assertEquals(true, muteStreamToggled)
        assertTrue(backedOut)
    }
}
