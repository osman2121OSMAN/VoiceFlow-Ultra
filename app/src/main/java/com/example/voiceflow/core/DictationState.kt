package com.example.voiceflow.core

sealed interface DictationState {
    data class Idle(
        val noticeMessage: String? = null,
    ) : DictationState

    data class Listening(
        val sessionId: Long,
        val previewText: String,
        val rmsDb: Float,
    ) : DictationState

    data class Processing(
        val sessionId: Long,
        val previewText: String,
        val statusMessage: String = "Yapay zeka ile düzeltiliyor... ✦",
    ) : DictationState

    data class Inserting(
        val sessionId: Long,
        val finalText: String,
    ) : DictationState

    data class Done(
        val sessionId: Long,
        val message: String,
        val inserted: Boolean,
    ) : DictationState
}
