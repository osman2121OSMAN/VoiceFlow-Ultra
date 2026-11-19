package com.example.voiceflow.core

import android.speech.SpeechRecognizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DictationSessionEngineTest {

    private class FakeSpeechGateway(
        var micGranted: Boolean = true,
        var available: Boolean = true,
    ) : SpeechGateway {
        val startedSessionIds = mutableListOf<Long>()
        var stopCount = 0
        var cancelDestroyCount = 0

        override fun hasMicrophonePermission(): Boolean = micGranted
        override fun isRecognitionAvailable(): Boolean = available
        override fun startListening(sessionId: Long) {
            startedSessionIds.add(sessionId)
        }

        override fun stopListening() {
            stopCount++
        }

        override fun cancelAndDestroy() {
            cancelDestroyCount++
        }
    }

    private class FakeTextTargetGateway(
        var nextResult: TextInsertResult = TextInsertResult.Inserted,
    ) : TextTargetGateway {
        val insertedTexts = mutableListOf<String>()

        override fun insertOrCopy(text: String): TextInsertResult {
            insertedTexts.add(text)
            return nextResult
        }
    }

    private class FakeScheduler : SchedulerGateway {
        val tasks = linkedMapOf<String, () -> Unit>()
        val delays = mutableMapOf<String, Long>()

        override fun postDelayed(token: String, delayMs: Long, action: () -> Unit) {
            tasks[token] = action
            delays[token] = delayMs
        }

        override fun cancel(token: String) {
            tasks.remove(token)
            delays.remove(token)
        }

        override fun cancelAll() {
            tasks.clear()
            delays.clear()
        }

        fun runAllPending() {
            val copy = tasks.entries.toList()
            tasks.clear()
            copy.forEach { it.value.invoke() }
        }

        fun runToken(token: String) {
            tasks.remove(token)?.invoke()
        }
    }

    @Test
    fun `idle never starts microphone automatically and check in idle inserts nothing`() {
        val speech = FakeSpeechGateway()
        val target = FakeTextTargetGateway()
        val scheduler = FakeScheduler()
        var dismissed = false
        val engine = DictationSessionEngine(speech, target, scheduler, {}, { dismissed = true })

        assertTrue(engine.currentState is DictationState.Idle)
        assertTrue(speech.startedSessionIds.isEmpty())

        engine.onCheckTapped()
        assertTrue(target.insertedTexts.isEmpty())
        assertEquals(DictationSessionEngine.MSG_NO_SPEECH_YET, (engine.currentState as DictationState.Idle).noticeMessage)
        assertTrue(!dismissed)
    }

    @Test
    fun `pauses continue in same session without losing text`() {
        val speech = FakeSpeechGateway()
        val target = FakeTextTargetGateway()
        val scheduler = FakeScheduler()
        val engine = DictationSessionEngine(speech, target, scheduler, {}, {})

        engine.onCenterPillTapped()
        val sid = engine.currentSessionId
        assertEquals(listOf(sid), speech.startedSessionIds)

        // Tapping center pill while already listening does nothing
        engine.onCenterPillTapped()
        assertEquals(1, speech.startedSessionIds.size)

        engine.onPartialResults(sid, "ilk cümle")
        engine.onResults(sid, "ilk cümle")
        assertEquals(150L, scheduler.delays["restart_segment"])
        scheduler.runAllPending()
        assertEquals(2, speech.startedSessionIds.size)
        assertEquals(sid, speech.startedSessionIds[1])

        // 5-second pause causes ERROR_SPEECH_TIMEOUT -> must continue in same session after 150ms
        engine.onSpeechError(sid, SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
        assertEquals(150L, scheduler.delays["restart_segment"])
        scheduler.runAllPending()
        assertEquals(3, speech.startedSessionIds.size)
        assertTrue(engine.currentState is DictationState.Listening)

        engine.onPartialResults(sid, "ikinci cümle")
        engine.onCheckTapped()
        // Flush completes via onResults in Processing
        engine.onResults(sid, "ikinci cümle")

        assertEquals(listOf("ilk cümle ikinci cümle"), target.insertedTexts)
        assertTrue(engine.currentState is DictationState.Done)
    }

    @Test
    fun `onEndOfSpeech without result flushes partial and restarts inside same session after 150ms`() {
        val speech = FakeSpeechGateway()
        val target = FakeTextTargetGateway()
        val scheduler = FakeScheduler()
        val engine = DictationSessionEngine(speech, target, scheduler, {}, {})

        engine.onCenterPillTapped()
        val sid = engine.currentSessionId

        engine.onPartialResults(sid, "merhaba dünya")
        // Engine fires onEndOfSpeech when user pauses 4s, without delivering onResults
        engine.onEndOfSpeech(sid)
        assertEquals(150L, scheduler.delays["restart_segment"])

        scheduler.runToken("restart_segment")
        assertEquals(2, speech.startedSessionIds.size)
        assertEquals(sid, speech.startedSessionIds[1])
        assertTrue(engine.currentState is DictationState.Listening)
        assertEquals("merhaba dünya", (engine.currentState as DictationState.Listening).previewText)

        // Segment 2 adds overlapping tail + new words without duplication
        engine.onPartialResults(sid, "merhaba dünya bugün hava")
        engine.onResults(sid, "merhaba dünya bugün hava")
        scheduler.runToken("restart_segment")
        assertEquals(3, speech.startedSessionIds.size)

        engine.onCheckTapped()
        scheduler.runToken("processing_timeout")
        assertEquals(listOf("merhaba dünya bugün hava"), target.insertedTexts)
    }

    @Test
    fun `onEndOfSpeech followed closely by onResults does not double restart`() {
        val speech = FakeSpeechGateway()
        val target = FakeTextTargetGateway()
        val scheduler = FakeScheduler()
        val engine = DictationSessionEngine(speech, target, scheduler, {}, {})

        engine.onCenterPillTapped()
        val sid = engine.currentSessionId
        engine.onEndOfSpeech(sid)
        engine.onResults(sid, "birinci bölüm")
        scheduler.runToken("restart_segment")
        // Trailing duplicate onEndOfSpeech after segment already completed must be ignored
        engine.onEndOfSpeech(sid)

        assertEquals(2, speech.startedSessionIds.size)
        assertEquals("birinci bölüm", (engine.currentState as DictationState.Listening).previewText)
    }

    @Test
    fun `pauses and silence errors restart seamlessly in 7-24 continuous listening without stopping`() {
        val speech = FakeSpeechGateway()
        val target = FakeTextTargetGateway()
        val scheduler = FakeScheduler()
        val engine = DictationSessionEngine(speech, target, scheduler, {}, {})

        engine.onCenterPillTapped()
        val sid = engine.currentSessionId

        // Many continuous pauses/no-match errors should keep listening indefinitely
        repeat(50) { idx ->
            val err = if (idx % 2 == 0) SpeechRecognizer.ERROR_NO_MATCH else SpeechRecognizer.ERROR_SPEECH_TIMEOUT
            engine.onSpeechError(sid, err)
            assertTrue("Retry ${idx + 1} should stay Listening", engine.currentState is DictationState.Listening)
            scheduler.runToken("restart_segment")
        }
        assertEquals(51, speech.startedSessionIds.size)
        assertTrue(engine.currentState is DictationState.Listening)
    }

    @Test
    fun `check and cancel stop immediately even during 150ms restart window`() {
        val speech = FakeSpeechGateway()
        val target = FakeTextTargetGateway()
        val scheduler = FakeScheduler()
        var dismissed = false
        val engine = DictationSessionEngine(speech, target, scheduler, {}, { dismissed = true })

        engine.onCenterPillTapped()
        val sid1 = engine.currentSessionId
        engine.onResults(sid1, "tamamlanacak metin")
        assertTrue(scheduler.tasks.containsKey("restart_segment"))

        // Tapping check during 150ms inter-segment wait cancels restart and inserts text once
        engine.onCheckTapped()
        assertTrue(!scheduler.tasks.containsKey("restart_segment"))
        scheduler.runToken("processing_timeout")
        assertEquals(listOf("tamamlanacak metin"), target.insertedTexts)

        // Reset and test cancel during 150ms inter-segment wait
        engine.resetToIdle()
        engine.onCenterPillTapped()
        val sid2 = engine.currentSessionId
        engine.onResults(sid2, "iptal metni")
        assertTrue(scheduler.tasks.containsKey("restart_segment"))

        engine.onCancelTapped()
        assertTrue(dismissed)
        assertTrue(scheduler.tasks.isEmpty())
        assertTrue(engine.currentState is DictationState.Idle)
    }

    @Test
    fun `single tap on check starts insertion and double tap is ignored`() {
        val speech = FakeSpeechGateway()
        val target = FakeTextTargetGateway()
        val scheduler = FakeScheduler()
        val engine = DictationSessionEngine(speech, target, scheduler, {}, {})

        engine.onCenterPillTapped()
        val sid = engine.currentSessionId
        engine.onPartialResults(sid, "tek dokunuş testi")

        // First tap transitions to Processing
        engine.onCheckTapped()
        assertTrue(engine.currentState is DictationState.Processing)

        // Second tap must be ignored
        engine.onCheckTapped()
        assertEquals(1, speech.stopCount)

        // Timeout flushes partial text and inserts ONCE
        scheduler.runToken("processing_timeout")
        assertEquals(listOf("tek dokunuş testi"), target.insertedTexts)
    }

    @Test
    fun `cancel invalidates session and ignores late callbacks`() {
        val speech = FakeSpeechGateway()
        val target = FakeTextTargetGateway()
        val scheduler = FakeScheduler()
        var dismissed = false
        val engine = DictationSessionEngine(speech, target, scheduler, {}, { dismissed = true })

        engine.onCenterPillTapped()
        val oldSid = engine.currentSessionId
        engine.onPartialResults(oldSid, "iptal edilecek")

        engine.onCancelTapped()
        assertTrue(dismissed)
        assertTrue(target.insertedTexts.isEmpty())
        assertTrue(engine.currentState is DictationState.Idle)

        // Late callbacks from old session must do nothing
        engine.onPartialResults(oldSid, "geç gelen")
        engine.onResults(oldSid, "geç gelen sonuç")
        engine.onEndOfSpeech(oldSid)
        engine.onSpeechError(oldSid, SpeechRecognizer.ERROR_NO_MATCH)
        scheduler.runAllPending()

        assertTrue(target.insertedTexts.isEmpty())
        assertEquals(1, speech.startedSessionIds.size)
        assertTrue(engine.currentState is DictationState.Idle)
    }

    @Test
    fun `transient errors keep listening continuously without stopping`() {
        val speech = FakeSpeechGateway()
        val target = FakeTextTargetGateway()
        val scheduler = FakeScheduler()
        val engine = DictationSessionEngine(speech, target, scheduler, {}, {})

        engine.onCenterPillTapped()
        val sid = engine.currentSessionId

        engine.onSpeechError(sid, SpeechRecognizer.ERROR_NETWORK)
        scheduler.runAllPending()
        engine.onSpeechError(sid, SpeechRecognizer.ERROR_NETWORK)
        scheduler.runAllPending()
        engine.onSpeechError(sid, SpeechRecognizer.ERROR_NETWORK)
        scheduler.runAllPending()

        assertTrue(engine.currentState is DictationState.Listening)
        assertEquals(4, speech.startedSessionIds.size)
    }
}
