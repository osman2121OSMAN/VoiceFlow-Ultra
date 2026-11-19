package com.example.voiceflow.speech

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import com.example.voiceflow.core.SpeechGateway
import java.util.Locale

class AndroidSpeechGateway(
    private val context: Context,
    private val onRmsChanged: (sessionId: Long, rmsDb: Float) -> Unit,
    private val onPartial: (sessionId: Long, text: String) -> Unit,
    private val onFinal: (sessionId: Long, text: String) -> Unit,
    private val onEndOfSpeechCallback: (sessionId: Long) -> Unit = {},
    private val onError: (sessionId: Long, errorCode: Int) -> Unit,
) : SpeechGateway {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var currentBoundSessionId: Long = -1L
    private var currentRecognizerToken: Long = 0L

    override fun hasMicrophonePermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
    }

    override fun isRecognitionAvailable(): Boolean {
        return SpeechRecognizer.isRecognitionAvailable(context)
    }

    override fun startListening(sessionId: Long) {
        runOnMain {
            destroyInternal()
            if (!isRecognitionAvailable()) {
                onError(sessionId, SpeechRecognizer.ERROR_CLIENT)
                return@runOnMain
            }

            currentBoundSessionId = sessionId
            val token = ++currentRecognizerToken

            // Use on-device speech recognizer if available to avoid cloud beeps and Gboard clashes
            val sr = if (SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
                try {
                    SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                } catch (_: Exception) {
                    SpeechRecognizer.createSpeechRecognizer(context)
                }
            } else {
                SpeechRecognizer.createSpeechRecognizer(context)
            }

            recognizer = sr
            sr.setRecognitionListener(createListener(sessionId, token))
            sr.startListening(buildIntent())
        }
    }

    override fun stopListening() {
        runOnMain {
            try {
                recognizer?.stopListening()
            } catch (_: Exception) {
                // Ignore if recognizer is already stopped or destroyed.
            }
        }
    }

    override fun cancelAndDestroy() {
        runOnMain {
            currentBoundSessionId = -1L
            currentRecognizerToken++
            destroyInternal()
        }
    }

    private fun destroyInternal() {
        val sr = recognizer ?: return
        recognizer = null
        try {
            sr.setRecognitionListener(null)
            sr.cancel()
        } catch (_: Exception) {
            // Best-effort cancel.
        }
        try {
            sr.destroy()
        } catch (_: Exception) {
            // Best-effort destroy.
        }
    }

    private fun buildIntent(): Intent {
        val languageTag = Locale.forLanguageTag("tr-TR").toLanguageTag()
        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, languageTag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            // Capture 100% of speech exactly as spoken without masking offensive words
            putExtra(RecognizerIntent.EXTRA_MASK_OFFENSIVE_WORDS, false)
            // Keep listening continuously for up to 5 minutes per segment without timing out
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                300_000L,
            )
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                300_000L,
            )
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS,
                300_000L,
            )
        }
    }

    private fun createListener(
        boundSessionId: Long,
        boundToken: Long,
    ): RecognitionListener {
        return object : RecognitionListener {
            private fun isActiveSegment(): Boolean {
                return boundSessionId == currentBoundSessionId &&
                    boundToken == currentRecognizerToken &&
                    recognizer != null
            }

            override fun onReadyForSpeech(params: Bundle?) = Unit

            override fun onBeginningOfSpeech() = Unit

            override fun onRmsChanged(rmsdB: Float) {
                if (!isActiveSegment()) return
                onRmsChanged(boundSessionId, rmsdB)
            }

            override fun onBufferReceived(buffer: ByteArray?) = Unit

            override fun onEndOfSpeech() {
                if (!isActiveSegment()) return
                onEndOfSpeechCallback(boundSessionId)
            }

            override fun onError(error: Int) {
                if (!isActiveSegment()) return
                onError(boundSessionId, error)
            }

            override fun onResults(results: Bundle?) {
                if (!isActiveSegment()) return
                val text = extractBestText(results)
                onFinal(boundSessionId, text)
            }

            override fun onPartialResults(partialResults: Bundle?) {
                if (!isActiveSegment()) return
                val text = extractBestText(partialResults)
                if (text.isNotBlank()) {
                    onPartial(boundSessionId, text)
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        }
    }

    private fun extractBestText(bundle: Bundle?): String {
        val matches = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        return matches?.firstOrNull()?.trim().orEmpty()
    }

    private inline fun runOnMain(crossinline block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post { block() }
        }
    }
}
