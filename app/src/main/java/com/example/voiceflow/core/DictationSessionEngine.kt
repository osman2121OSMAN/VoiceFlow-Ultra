package com.example.voiceflow.core

import android.speech.SpeechRecognizer

interface SpeechGateway {
    fun hasMicrophonePermission(): Boolean
    fun isRecognitionAvailable(): Boolean
    fun startListening(sessionId: Long)
    fun stopListening()
    fun cancelAndDestroy()
}

sealed interface TextInsertResult {
    data object Inserted : TextInsertResult
    data object BlockedPassword : TextInsertResult
    data class CopiedToClipboard(val reason: String) : TextInsertResult
}

interface TextTargetGateway {
    fun insertOrCopy(text: String): TextInsertResult
}

interface SchedulerGateway {
    fun postDelayed(token: String, delayMs: Long, action: () -> Unit)
    fun cancel(token: String)
    fun cancelAll()
}

class DictationSessionEngine(
    private val speechGateway: SpeechGateway,
    private val textTargetGateway: TextTargetGateway,
    private val scheduler: SchedulerGateway,
    private val onStateChanged: (DictationState) -> Unit,
    private val onDismissRequested: () -> Unit,
) {
    private val merger = SegmentMerger()
    private var activeSessionId: Long = 0L
    private var state: DictationState = DictationState.Idle()
    private var sessionRetries: Int = 0
    private var transientErrorRetries: Int = 0
    private var segmentCompleted: Boolean = false
    private var segmentProducedText: Boolean = false

    val currentState: DictationState
        get() = state

    val currentSessionId: Long
        get() = activeSessionId

    fun finalizeAccumulatedText(): String {
        return merger.finalizeText()
    }

    fun resetToIdle(notice: String? = null) {
        invalidateSession()
        scheduler.cancelAll()
        speechGateway.cancelAndDestroy()
        merger.clear()
        resetSessionCounters()
        updateState(DictationState.Idle(noticeMessage = notice))
    }

    fun onCenterPillTapped() {
        if (state !is DictationState.Idle) return

        if (!speechGateway.hasMicrophonePermission()) {
            updateState(DictationState.Idle(noticeMessage = MSG_MIC_PERMISSION_REQUIRED))
            return
        }
        if (!speechGateway.isRecognitionAvailable()) {
            updateState(DictationState.Idle(noticeMessage = MSG_SPEECH_UNAVAILABLE))
            return
        }

        val newSessionId = ++activeSessionId
        merger.clear()
        resetSessionCounters()
        updateState(
            DictationState.Listening(
                sessionId = newSessionId,
                previewText = "",
                rmsDb = 0f,
            ),
        )
        startSegment(newSessionId)
    }

    fun onCheckTapped() {
        when (val current = state) {
            is DictationState.Idle -> {
                updateState(DictationState.Idle(noticeMessage = MSG_NO_SPEECH_YET))
            }
            is DictationState.Listening -> {
                val sid = current.sessionId
                scheduler.cancel(TOKEN_RESTART_SEGMENT)
                scheduler.cancel(TOKEN_PROCESSING_TIMEOUT)
                val preview = merger.buildPreviewText()
                updateState(
                    DictationState.Processing(
                        sessionId = sid,
                        previewText = preview,
                        statusMessage = "Yapay zeka ile düzeltiliyor... ✦",
                    ),
                )
                speechGateway.stopListening()
                scheduler.postDelayed(TOKEN_PROCESSING_TIMEOUT, PROCESSING_FLUSH_TIMEOUT_MS) {
                    val now = state
                    if (now is DictationState.Processing && now.sessionId == sid) {
                        completeProcessingAndInsert(sid)
                    }
                }
            }
            is DictationState.Processing,
            is DictationState.Inserting,
            is DictationState.Done,
            -> {
                // Guard against double taps on check.
            }
        }
    }

    fun onCancelTapped() {
        invalidateSession()
        scheduler.cancelAll()
        speechGateway.cancelAndDestroy()
        merger.clear()
        resetSessionCounters()
        updateState(DictationState.Idle())
        onDismissRequested()
    }

    fun onOverlayHidden() {
        invalidateSession()
        scheduler.cancelAll()
        speechGateway.cancelAndDestroy()
        merger.clear()
        resetSessionCounters()
        updateState(DictationState.Idle())
    }

    fun onRmsChanged(sessionId: Long, rmsDb: Float) {
        val current = state
        if (current !is DictationState.Listening || sessionId != activeSessionId) return
        val normalized = rmsDb.coerceIn(0f, 12f)
        updateState(current.copy(rmsDb = normalized))
    }

    fun onPartialResults(sessionId: Long, partialText: String) {
        val current = state
        if (current !is DictationState.Listening || sessionId != activeSessionId) return
        if (partialText.isBlank()) return
        segmentProducedText = true
        transientErrorRetries = 0
        merger.updatePartial(partialText)
        updateState(current.copy(previewText = merger.buildPreviewText()))
    }

    fun onEndOfSpeech(sessionId: Long) {
        val current = state
        if (sessionId != activeSessionId || current !is DictationState.Listening) return
        if (segmentCompleted) return

        scheduler.cancel(TOKEN_RESTART_SEGMENT)
        scheduler.postDelayed(TOKEN_RESTART_SEGMENT, SEGMENT_RESTART_DELAY_MS) {
            val now = state
            if (now !is DictationState.Listening || activeSessionId != sessionId || segmentCompleted) {
                return@postDelayed
            }
            segmentCompleted = true
            merger.commitSegment("")
            updateState(now.copy(previewText = merger.buildPreviewText(), rmsDb = 0f))
            startSegment(sessionId)
        }
    }

    fun onResults(sessionId: Long, finalText: String) {
        if (sessionId != activeSessionId) return
        when (val current = state) {
            is DictationState.Listening -> {
                segmentCompleted = true
                if (finalText.isNotBlank()) {
                    segmentProducedText = true
                }
                transientErrorRetries = 0
                merger.commitSegment(finalText)
                val updatedPreview = merger.buildPreviewText()
                updateState(current.copy(previewText = updatedPreview, rmsDb = 0f))
                scheduleNextSegment(sessionId, SEGMENT_RESTART_DELAY_MS)
            }
            is DictationState.Processing -> {
                segmentCompleted = true
                scheduler.cancel(TOKEN_PROCESSING_TIMEOUT)
                merger.commitSegment(finalText)
                completeProcessingAndInsert(sessionId)
            }
            else -> Unit
        }
    }

    fun onSpeechError(sessionId: Long, errorCode: Int) {
        if (sessionId != activeSessionId) return
        when (state) {
            is DictationState.Processing -> {
                segmentCompleted = true
                scheduler.cancel(TOKEN_PROCESSING_TIMEOUT)
                completeProcessingAndInsert(sessionId)
            }
            is DictationState.Listening -> {
                segmentCompleted = true
                handleListeningError(sessionId, errorCode)
            }
            else -> Unit
        }
    }

    private fun handleListeningError(sessionId: Long, errorCode: Int) {
        when (errorCode) {
            SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
            -> {
                merger.commitSegment("")
                val current = state
                if (current is DictationState.Listening) {
                    updateState(current.copy(previewText = merger.buildPreviewText(), rmsDb = 0f))
                }
                scheduleNextSegment(sessionId, SILENCE_RESTART_DELAY_MS)
            }
            SpeechRecognizer.ERROR_NETWORK,
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
            SpeechRecognizer.ERROR_SERVER,
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
            SpeechRecognizer.ERROR_CLIENT,
            -> {
                transientErrorRetries++
                if (transientErrorRetries <= MAX_TRANSIENT_ERROR_RETRIES) {
                    scheduleNextSegment(sessionId, ERROR_RETRY_BASE_MS * transientErrorRetries)
                } else {
                    scheduleNextSegment(sessionId, ERROR_RETRY_BASE_MS)
                }
            }
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                stopListeningWithNotice(MSG_MIC_PERMISSION_REQUIRED)
            }
            SpeechRecognizer.ERROR_AUDIO -> {
                scheduleNextSegment(sessionId, ERROR_RETRY_BASE_MS)
            }
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
            -> {
                stopListeningWithNotice(MSG_LANGUAGE_UNAVAILABLE)
            }
            else -> {
                scheduleNextSegment(sessionId, ERROR_RETRY_BASE_MS)
            }
        }
    }

    private fun startSegment(sessionId: Long) {
        segmentCompleted = false
        segmentProducedText = false
        speechGateway.startListening(sessionId)
    }

    private fun scheduleNextSegment(sessionId: Long, delayMs: Long) {
        scheduler.cancel(TOKEN_RESTART_SEGMENT)
        scheduler.postDelayed(TOKEN_RESTART_SEGMENT, delayMs) {
            val current = state
            if (current is DictationState.Listening && activeSessionId == sessionId) {
                startSegment(sessionId)
            }
        }
    }

    private fun stopListeningWithNotice(notice: String) {
        invalidateSession()
        scheduler.cancel(TOKEN_RESTART_SEGMENT)
        speechGateway.cancelAndDestroy()
        merger.clear()
        resetSessionCounters()
        updateState(DictationState.Idle(noticeMessage = notice))
    }

    private fun completeProcessingAndInsert(sessionId: Long) {
        invalidateSession()
        scheduler.cancel(TOKEN_RESTART_SEGMENT)
        speechGateway.cancelAndDestroy()

        val finalText = merger.finalizeText()
        merger.clear()
        resetSessionCounters()

        if (finalText.isBlank()) {
            updateState(DictationState.Idle(noticeMessage = MSG_NO_SPEECH_YET))
            return
        }

        updateState(DictationState.Inserting(sessionId = sessionId, finalText = finalText))
        when (val result = textTargetGateway.insertOrCopy(finalText)) {
            is TextInsertResult.Inserted -> {
                updateState(
                    DictationState.Done(
                        sessionId = sessionId,
                        message = MSG_INSERTED,
                        inserted = true,
                    ),
                )
                scheduler.postDelayed(TOKEN_DONE_TO_IDLE, DONE_TO_IDLE_DELAY_MS) {
                    val now = state
                    if (now is DictationState.Done && now.sessionId == sessionId) {
                        updateState(DictationState.Idle())
                    }
                }
            }
            is TextInsertResult.BlockedPassword -> {
                updateState(DictationState.Idle(noticeMessage = MSG_PASSWORD_BLOCKED))
            }
            is TextInsertResult.CopiedToClipboard -> {
                updateState(
                    DictationState.Done(
                        sessionId = sessionId,
                        message = result.reason,
                        inserted = false,
                    ),
                )
                scheduler.postDelayed(TOKEN_DONE_TO_IDLE, COPIED_NOTICE_DELAY_MS) {
                    val now = state
                    if (now is DictationState.Done && now.sessionId == sessionId) {
                        updateState(DictationState.Idle())
                    }
                }
            }
        }
    }

    private fun resetSessionCounters() {
        sessionRetries = 0
        transientErrorRetries = 0
        segmentCompleted = false
        segmentProducedText = false
    }

    private fun invalidateSession() {
        activeSessionId++
    }

    private fun updateState(newState: DictationState) {
        state = newState
        onStateChanged(newState)
    }

    companion object {
        const val PROCESSING_FLUSH_TIMEOUT_MS = 220L
        const val SEGMENT_RESTART_DELAY_MS = 150L
        const val SILENCE_RESTART_DELAY_MS = 150L
        const val ERROR_RETRY_BASE_MS = 450L
        const val DONE_TO_IDLE_DELAY_MS = 160L
        const val COPIED_NOTICE_DELAY_MS = 2200L
        const val MAX_TRANSIENT_ERROR_RETRIES = 2

        private const val TOKEN_RESTART_SEGMENT = "restart_segment"
        private const val TOKEN_PROCESSING_TIMEOUT = "processing_timeout"
        private const val TOKEN_DONE_TO_IDLE = "done_to_idle"

        const val MSG_MIC_PERMISSION_REQUIRED = "Mikrofon izni gerekli. Uygulamadan izin verin."
        const val MSG_SPEECH_UNAVAILABLE = "Cihazda konuşma tanıma servisi bulunamadı."
        const val MSG_NO_SPEECH_YET = "Henüz onaylanacak bir konuşma algılanmadı."
        const val MSG_NO_SPEECH_DETECTED = "Konuşma algılanmadı, mikrofon durduruldu."
        const val MSG_SILENCE_STOPPED = "Uzun süreli sessizlik nedeniyle mikrofon durduruldu."
        const val MSG_RECOGNIZER_ERROR = "Ses tanıma başlatılamadı, tekrar dokunun."
        const val MSG_AUDIO_ERROR = "Mikrofon ses kaydı alınamadı."
        const val MSG_LANGUAGE_UNAVAILABLE = "Türkçe konuşma tanıma paketi kullanılamıyor."
        const val MSG_PASSWORD_BLOCKED = "Parola alanlarına metin eklenemez."
        const val MSG_INSERTED = "Metin eklendi."
    }
}
