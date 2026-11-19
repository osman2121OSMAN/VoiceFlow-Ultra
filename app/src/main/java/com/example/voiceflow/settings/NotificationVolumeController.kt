package com.example.voiceflow.settings

import android.media.AudioManager
import android.os.Build

interface NotificationStreamGateway {
    fun getNotificationVolume(): Int
    fun getMinNotificationVolume(): Int
    fun setNotificationVolume(volume: Int)
}

class AndroidNotificationStreamGateway(
    private val audioManager: AudioManager?,
) : NotificationStreamGateway {
    override fun getNotificationVolume(): Int {
        return try {
            audioManager?.getStreamVolume(AudioManager.STREAM_NOTIFICATION) ?: 0
        } catch (_: Exception) {
            0
        }
    }

    override fun getMinNotificationVolume(): Int {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && audioManager != null) {
                audioManager.getStreamMinVolume(AudioManager.STREAM_NOTIFICATION)
            } else {
                0
            }
        } catch (_: Exception) {
            0
        }
    }

    override fun setNotificationVolume(volume: Int) {
        val am = audioManager ?: return
        try {
            am.setStreamVolume(AudioManager.STREAM_NOTIFICATION, volume, 0)
        } catch (_: SecurityException) {
            // Some devices block muting notification stream under Do Not Disturb; fall back to 1.
            if (volume == 0) {
                try {
                    am.setStreamVolume(AudioManager.STREAM_NOTIFICATION, 1, 0)
                } catch (_: Exception) {
                    // Best effort only.
                }
            }
        } catch (_: Exception) {
            // Best effort only.
        }
    }
}

/**
 * Lowers STREAM_NOTIFICATION during active listening when "Sistem tanıyıcı sesini kıs" is ON
 * and restores the pre-listening volume immediately when listening ends or overlay closes.
 */
class NotificationVolumeController(
    private val gateway: NotificationStreamGateway,
) {
    private var savedNotificationVolume: Int? = null

    val isCurrentlyLowered: Boolean
        get() = savedNotificationVolume != null

    fun onListeningStarted(muteSystemStream: Boolean) {
        if (!muteSystemStream) {
            restoreIfLowered()
            return
        }
        if (savedNotificationVolume == null) {
            savedNotificationVolume = gateway.getNotificationVolume()
        }
        val minVol = gateway.getMinNotificationVolume()
        gateway.setNotificationVolume(minVol)
    }

    fun onListeningStopped() {
        restoreIfLowered()
    }

    private fun restoreIfLowered() {
        val original = savedNotificationVolume ?: return
        savedNotificationVolume = null
        gateway.setNotificationVolume(original)
    }
}
