package com.example.voiceflow.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.speech.SpeechRecognizer
import android.text.TextUtils
import androidx.core.content.ContextCompat
import com.example.voiceflow.service.VoiceFlowAccessibilityService

data class PermissionSnapshot(
    val micGranted: Boolean = false,
    val overlayGranted: Boolean = false,
    val accessibilityEnabled: Boolean = false,
    val batteryUnrestricted: Boolean = false,
    val speechAvailable: Boolean = true,
    val isXiaomiFamily: Boolean = false,
) {
    val allRequiredReady: Boolean
        get() = micGranted && overlayGranted && accessibilityEnabled

    val completedCoreCount: Int
        get() = listOf(micGranted, overlayGranted, accessibilityEnabled).count { it }
}

object PermissionHelper {

    fun readSnapshot(context: Context): PermissionSnapshot {
        val mic = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED

        val overlay = Settings.canDrawOverlays(context)
        val accessibility = isAccessibilityServiceEnabled(context)
        val powerManager = context.getSystemService(PowerManager::class.java)
        val batteryUnrestricted = powerManager?.isIgnoringBatteryOptimizations(context.packageName) ?: false
        val speechAvailable = SpeechRecognizer.isRecognitionAvailable(context)

        val manufacturer = Build.MANUFACTURER.orEmpty()
        val isXiaomi = manufacturer.equals("Xiaomi", ignoreCase = true) ||
            manufacturer.equals("Redmi", ignoreCase = true) ||
            manufacturer.equals("POCO", ignoreCase = true)

        return PermissionSnapshot(
            micGranted = mic,
            overlayGranted = overlay,
            accessibilityEnabled = accessibility,
            batteryUnrestricted = batteryUnrestricted,
            speechAvailable = speechAvailable,
            isXiaomiFamily = isXiaomi,
        )
    }

    fun isAccessibilityServiceEnabled(context: Context): Boolean {
        if (VoiceFlowAccessibilityService.isServiceRunning()) return true
        val expectedComponent = ComponentName(context, VoiceFlowAccessibilityService::class.java)
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false

        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabledServices)
        while (splitter.hasNext()) {
            val componentName = ComponentName.unflattenFromString(splitter.next())
            if (componentName == expectedComponent) {
                return true
            }
        }
        return false
    }

    fun openOverlaySettings(context: Context) {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        launchWithFallback(context, intent)
    }

    fun openAccessibilitySettings(context: Context) {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        launchWithFallback(context, intent)
    }

    fun openXiaomiAutostart(context: Context): Boolean {
        val candidates = listOf(
            Intent().setComponent(
                ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity",
                ),
            ),
            Intent().setComponent(
                ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.permissions.PermissionsEditorActivity",
                ),
            ).putExtra("extra_pkgname", context.packageName),
        )
        for (candidate in candidates) {
            candidate.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (tryStartActivity(context, candidate)) return true
        }
        openAppDetails(context)
        return false
    }

    fun openBatteryOptimizationSettings(context: Context): Boolean {
        val candidates = listOf(
            Intent().setComponent(
                ComponentName(
                    "com.miui.powerkeeper",
                    "com.miui.powerkeeper.ui.HiddenAppsConfigActivity",
                ),
            ).apply {
                putExtra("package_name", context.packageName)
                putExtra("package_label", "VoiceFlow")
            },
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
        )
        for (candidate in candidates) {
            candidate.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (tryStartActivity(context, candidate)) return true
        }
        openAppDetails(context)
        return false
    }

    fun openAppDetails(context: Context) {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        tryStartActivity(context, intent)
    }

    private fun launchWithFallback(context: Context, intent: Intent) {
        if (!tryStartActivity(context, intent)) {
            openAppDetails(context)
        }
    }

    private fun tryStartActivity(context: Context, intent: Intent): Boolean {
        return try {
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }
}
