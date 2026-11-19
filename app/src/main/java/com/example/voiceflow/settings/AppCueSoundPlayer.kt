package com.example.voiceflow.settings

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.sin

/**
 * Plays optional app-owned dictation cue chimes ONLY when "Dikte sesleri" is ON
 * and "Ses seviyesi" is > 0%. Plays nothing when disabled or volume == 0.
 */
class AppCueSoundPlayer {

    fun playListeningStartCue(settings: VoiceFlowSoundSettings) {
        playToneIfEnabled(settings, frequencyHz = 660.0, durationMs = 45)
    }

    fun playListeningStopCue(settings: VoiceFlowSoundSettings) {
        playToneIfEnabled(settings, frequencyHz = 520.0, durationMs = 45)
    }

    internal fun shouldPlaySound(settings: VoiceFlowSoundSettings): Boolean {
        return settings.dictationSoundsEnabled && settings.appSoundVolumePercent > 0
    }

    private fun playToneIfEnabled(
        settings: VoiceFlowSoundSettings,
        frequencyHz: Double,
        durationMs: Int,
    ) {
        if (!shouldPlaySound(settings)) return
        val gain = (settings.appSoundVolumePercent / 100f).coerceIn(0f, 1f) * MAX_SOFT_GAIN
        Thread {
            try {
                val sampleRate = 22_050
                val sampleCount = (sampleRate * durationMs) / 1000
                val pcm = ShortArray(sampleCount)
                val fadeSamples = (sampleCount / 5).coerceAtLeast(1)
                for (i in 0 until sampleCount) {
                    val env = when {
                        i < fadeSamples -> i.toFloat() / fadeSamples
                        i > sampleCount - fadeSamples -> (sampleCount - i).toFloat() / fadeSamples
                        else -> 1f
                    }
                    val angle = 2.0 * PI * i * frequencyHz / sampleRate
                    pcm[i] = (sin(angle) * Short.MAX_VALUE * gain * env).toInt().toShort()
                }

                val attrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                val format = AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
                val track = AudioTrack.Builder()
                    .setAudioAttributes(attrs)
                    .setAudioFormat(format)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .setBufferSizeInBytes(pcm.size * 2)
                    .build()

                track.write(pcm, 0, pcm.size)
                track.play()
                Thread.sleep(durationMs.toLong() + 35L)
                track.stop()
                track.release()
            } catch (_: Exception) {
                // Ignore audio output errors in background or headless environments.
            }
        }.start()
    }

    companion object {
        private const val MAX_SOFT_GAIN = 0.28f
    }
}
