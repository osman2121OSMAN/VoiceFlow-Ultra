package com.example

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.theme.VoiceFlowTheme
import com.example.voiceflow.settings.SoundSettingsStore
import com.example.voiceflow.settings.VoiceFlowSoundSettings
import com.example.voiceflow.ui.OnboardingScreen
import com.example.voiceflow.ai.configureAppCheck
import com.example.voiceflow.ui.PermissionHelper
import com.example.voiceflow.ui.SettingsScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureAppCheck(this, intent)
        enableEdgeToEdge()
        setContent {
            VoiceFlowTheme {
                val context = LocalContext.current
                val lifecycleOwner = LocalLifecycleOwner.current
                val soundSettingsStore = remember(context) { SoundSettingsStore(context) }
                val soundSettings by soundSettingsStore.settingsFlow.collectAsStateWithLifecycle(
                    initialValue = VoiceFlowSoundSettings(),
                )
                var showSettings by rememberSaveable { mutableStateOf(false) }

                var snapshot by remember {
                    mutableStateOf(PermissionHelper.readSnapshot(context))
                }

                val micLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestPermission(),
                ) {
                    snapshot = PermissionHelper.readSnapshot(context)
                }

                DisposableEffect(lifecycleOwner) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_RESUME) {
                            snapshot = PermissionHelper.readSnapshot(context)
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose {
                        lifecycleOwner.lifecycle.removeObserver(observer)
                    }
                }

                if (showSettings) {
                    SettingsScreen(
                        settings = soundSettings,
                        onDictationSoundsToggle = { enabled ->
                            soundSettingsStore.setDictationSoundsEnabled(enabled)
                        },
                        onVolumePercentChange = { percent ->
                            soundSettingsStore.setAppSoundVolumePercent(percent)
                        },
                        onMuteSystemRecognizerToggle = { enabled ->
                            soundSettingsStore.setMuteSystemRecognizerStream(enabled)
                        },
                        onBack = { showSettings = false },
                    )
                } else {
                    OnboardingScreen(
                        snapshot = snapshot,
                        onRequestMic = { micLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                        onOpenOverlaySettings = { PermissionHelper.openOverlaySettings(context) },
                        onOpenAccessibilitySettings = { PermissionHelper.openAccessibilitySettings(context) },
                        onOpenXiaomiAutostart = { PermissionHelper.openXiaomiAutostart(context) },
                        onOpenBatterySettings = { PermissionHelper.openBatteryOptimizationSettings(context) },
                        onOpenAppDetails = { PermissionHelper.openAppDetails(context) },
                        onOpenSettings = { showSettings = true },
                    )
                }
            }
        }
    }
}
