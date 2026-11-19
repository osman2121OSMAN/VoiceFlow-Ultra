package com.example.voiceflow.service

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Rect
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.InputMethodManager
import com.example.voiceflow.ai.GeminiAiTextRefiner
import com.example.voiceflow.ai.RefinedTextResult
import com.example.voiceflow.core.DictationSessionEngine
import com.example.voiceflow.core.DictationState
import com.example.voiceflow.core.FocusVisibilityCoordinator
import com.example.voiceflow.core.FocusedFieldSnapshot
import com.example.voiceflow.core.NodeValidator
import com.example.voiceflow.core.RectSnapshot
import com.example.voiceflow.core.SchedulerGateway
import com.example.voiceflow.core.TextInsertResult
import com.example.voiceflow.overlay.OverlayController
import com.example.voiceflow.overlay.OverlayPositionStore
import com.example.voiceflow.settings.AndroidNotificationStreamGateway
import com.example.voiceflow.settings.AppCueSoundPlayer
import com.example.voiceflow.settings.NotificationVolumeController
import com.example.voiceflow.settings.SoundSettingsStore
import com.example.voiceflow.speech.AndroidSpeechGateway
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

class VoiceFlowAccessibilityService : AccessibilityService() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scheduledRunnables = mutableMapOf<String, Runnable>()

    private lateinit var overlayController: OverlayController
    private lateinit var positionStore: OverlayPositionStore
    private lateinit var soundSettingsStore: SoundSettingsStore
    private lateinit var notificationVolumeController: NotificationVolumeController
    private lateinit var appCueSoundPlayer: AppCueSoundPlayer
    private lateinit var speechGateway: AndroidSpeechGateway
    private lateinit var textInserter: TextInserter
    private lateinit var sessionEngine: DictationSessionEngine
    private lateinit var focusCoordinator: FocusVisibilityCoordinator
    private lateinit var textRefiner: GeminiAiTextRefiner

    private var activeTargetNode: AccessibilityNodeInfo? = null
    private var screenOffReceiverRegistered: Boolean = false
    private var wasListeningState: Boolean = false

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                focusCoordinator.resetAll()
            }
        }
    }

    private val scheduler = object : SchedulerGateway {
        override fun postDelayed(token: String, delayMs: Long, action: () -> Unit) {
            cancel(token)
            val runnable = Runnable {
                scheduledRunnables.remove(token)
                action()
            }
            scheduledRunnables[token] = runnable
            mainHandler.postDelayed(runnable, delayMs)
        }

        override fun cancel(token: String) {
            scheduledRunnables.remove(token)?.let { mainHandler.removeCallbacks(it) }
        }

        override fun cancelAll() {
            scheduledRunnables.values.forEach { mainHandler.removeCallbacks(it) }
            scheduledRunnables.clear()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this

        val windowManager = getSystemService(WindowManager::class.java)
        positionStore = OverlayPositionStore(this)
        soundSettingsStore = SoundSettingsStore(this)
        notificationVolumeController = NotificationVolumeController(
            AndroidNotificationStreamGateway(getSystemService(AudioManager::class.java)),
        )
        appCueSoundPlayer = AppCueSoundPlayer()
        textRefiner = GeminiAiTextRefiner()
        overlayController = OverlayController(
            context = this,
            windowManager = windowManager,
            onCancelTapped = { sessionEngine.resetToIdle() },
            onCenterPillTapped = { sessionEngine.onCenterPillTapped() },
            onCheckTapped = { handleCheckTapped() },
            positionStore = positionStore,
            imeRectProvider = { findImeBounds() },
        )

        speechGateway = AndroidSpeechGateway(
            context = this,
            onRmsChanged = { sid, rms -> sessionEngine.onRmsChanged(sid, rms) },
            onPartial = { sid, text -> sessionEngine.onPartialResults(sid, text) },
            onFinal = { sid, text -> sessionEngine.onResults(sid, text) },
            onEndOfSpeechCallback = { sid -> sessionEngine.onEndOfSpeech(sid) },
            onError = { sid, code -> sessionEngine.onSpeechError(sid, code) },
        )

        textInserter = TextInserter(
            context = this,
            resolveTargetNode = { resolveCurrentInputNodeForInsertion() },
            expectedPackageProvider = { focusCoordinator.activeField?.packageName },
        )

        sessionEngine = DictationSessionEngine(
            speechGateway = speechGateway,
            textTargetGateway = textInserter,
            scheduler = scheduler,
            onStateChanged = { newState ->
                val isNowListening = newState is DictationState.Listening
                if (!wasListeningState && isNowListening) {
                    wasListeningState = true
                    val settings = soundSettingsStore.refreshAndGet()
                    notificationVolumeController.onListeningStarted(settings.muteSystemRecognizerStream)
                    appCueSoundPlayer.playListeningStartCue(settings)
                } else if (wasListeningState && !isNowListening) {
                    wasListeningState = false
                    notificationVolumeController.onListeningStopped()
                    appCueSoundPlayer.playListeningStopCue(soundSettingsStore.getCached())
                }
                if (overlayController.isShowing) {
                    overlayController.renderState(newState)
                }
            },
            onDismissRequested = {
                focusCoordinator.onOverlayManuallyDismissed()
            },
        )

        focusCoordinator = FocusVisibilityCoordinator(
            scheduler = scheduler,
            isOverlayShowing = { overlayController.isShowing },
            isDictationActive = { !isIdleOrDone() },
            resolveCurrentSnapshot = { resolveCurrentSnapshotAndPassword(event = null) },
            isImeVisibleNow = {
                val ime = findImeBounds()
                ime != null && !ime.isEmpty
            },
            onShowOverlay = { snapshot ->
                soundSettingsStore.refreshAndGet()
                val node = findCurrentFocusedInput(event = null)
                if (node != null && node.isEditable) {
                    activeTargetNode = node
                }
                val imeRect = findImeBounds()
                overlayController.show(
                    imeRect = imeRect,
                    fieldRect = snapshot.bounds,
                    state = sessionEngine.currentState,
                )
            },
            onHideOverlay = { _ ->
                soundSettingsStore.refreshAndGet()
                wasListeningState = false
                notificationVolumeController.onListeningStopped()
                if (::sessionEngine.isInitialized) {
                    sessionEngine.onOverlayHidden()
                }
                if (::overlayController.isInitialized) {
                    overlayController.hide()
                }
                activeTargetNode = null
            },
        )

        registerReceiver(screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
        screenOffReceiverRegistered = true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val eventPkg = event.packageName?.toString()
        val isImePkg = eventPkg != null && isImePackage(eventPkg)

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                val (snapshot, isPassword) = resolveCurrentSnapshotAndPassword(event)
                if (snapshot != null && !isPassword) {
                    findCurrentFocusedInput(event)?.let { activeTargetNode = it }
                }
                focusCoordinator.onFocusOrContentEvent(
                    snapshot = snapshot,
                    isPassword = isPassword,
                    isExplicitFocusOrClick = true,
                    isImePackage = isImePkg,
                )
            }
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                val (snapshot, isPassword) = resolveCurrentSnapshotAndPassword(event)
                if (snapshot != null && !isPassword) {
                    findCurrentFocusedInput(event)?.let { activeTargetNode = it }
                }
                // Only treat clicks on eligible fields or password fields as focus changes;
                // clicking random non-editable areas is evaluated via stable hide if focus actually leaves.
                focusCoordinator.onFocusOrContentEvent(
                    snapshot = snapshot,
                    isPassword = isPassword,
                    isExplicitFocusOrClick = snapshot != null || isPassword,
                    isImePackage = isImePkg,
                )
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                if (isImePkg) return
                val currentPkg = focusCoordinator.activeField?.packageName
                if (currentPkg != null && eventPkg != null && eventPkg != currentPkg) {
                    // Ignore status bar / systemui / background package content updates.
                    return
                }
                val (snapshot, isPassword) = resolveCurrentSnapshotAndPassword(event = null)
                if (snapshot != null && !isPassword) {
                    findCurrentFocusedInput(event = null)?.let { activeTargetNode = it }
                }
                focusCoordinator.onFocusOrContentEvent(
                    snapshot = snapshot,
                    isPassword = isPassword,
                    isExplicitFocusOrClick = false,
                    isImePackage = false,
                )
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                focusCoordinator.onWindowStateChanged(
                    newPackageName = eventPkg,
                    isImePackage = isImePkg,
                    isFullScreenWindow = event.isFullScreen,
                    ownPackageName = packageName,
                )
            }
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                val imeRect = findImeBounds()
                focusCoordinator.onWindowsChanged(
                    imeVisibleNow = imeRect != null && !imeRect.isEmpty,
                )
            }
        }
    }

    override fun onInterrupt() {
        wasListeningState = false
        if (::notificationVolumeController.isInitialized) {
            notificationVolumeController.onListeningStopped()
        }
        if (::focusCoordinator.isInitialized) {
            focusCoordinator.resetAll()
        }
    }

    override fun onDestroy() {
        instance = null
        wasListeningState = false
        if (::notificationVolumeController.isInitialized) {
            notificationVolumeController.onListeningStopped()
        }
        if (screenOffReceiverRegistered) {
            try {
                unregisterReceiver(screenOffReceiver)
            } catch (_: Exception) {
                // Ignore if already unregistered.
            }
            screenOffReceiverRegistered = false
        }
        scheduler.cancelAll()
        if (::focusCoordinator.isInitialized) {
            focusCoordinator.resetAll()
        }
        super.onDestroy()
    }

    private fun resolveCurrentSnapshotAndPassword(
        event: AccessibilityEvent?,
    ): Pair<FocusedFieldSnapshot?, Boolean> {
        val node = findCurrentFocusedInput(event)
        if (NodeValidator.isPasswordNode(node)) {
            return null to true
        }
        val snapshot = NodeValidator.toSnapshot(node)
        return snapshot to false
    }

    private fun findCurrentFocusedInput(event: AccessibilityEvent?): AccessibilityNodeInfo? {
        val source = if (
            event != null &&
            (event.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED ||
                event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED)
        ) {
            try {
                event.source
            } catch (_: Exception) {
                null
            }
        } else {
            null
        }
        if (source != null && (source.isEditable || NodeValidator.isPasswordNode(source))) {
            return source
        }

        val direct = try {
            findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        } catch (_: Exception) {
            null
        }
        if (direct != null) return direct

        val activeRootFocus = try {
            rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        } catch (_: Exception) {
            null
        }
        if (activeRootFocus != null) return activeRootFocus

        // Fallback across application windows for third-party apps / WebViews.
        val winList = try {
            windows
        } catch (_: Exception) {
            null
        } ?: return null

        for (win in winList) {
            if (win.type == AccessibilityWindowInfo.TYPE_APPLICATION) {
                val winFocus = try {
                    win.root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                } catch (_: Exception) {
                    null
                }
                if (winFocus != null) return winFocus
            }
        }

        // Preserve focus across third-party apps where findFocus() temporarily lags
        // while the tracked editable node itself still reports refreshed focus.
        val tracked = activeTargetNode
        if (tracked != null) {
            val refreshed = try {
                tracked.refresh()
            } catch (_: Exception) {
                false
            }
            if (refreshed &&
                tracked.isFocused &&
                tracked.isVisibleToUser &&
                (tracked.isEditable || NodeValidator.isPasswordNode(tracked))
            ) {
                return tracked
            }
        }
        return null
    }

    private fun resolveCurrentInputNodeForInsertion(): AccessibilityNodeInfo? {
        val currentFocus = findCurrentFocusedInput(event = null)
        if (currentFocus != null && (currentFocus.isEditable || NodeValidator.isPasswordNode(currentFocus))) {
            return currentFocus
        }
        return activeTargetNode
    }

    private fun findImeBounds(): RectSnapshot? {
        val winList = try {
            windows
        } catch (_: Exception) {
            return null
        } ?: return null

        val imeWindow = winList.firstOrNull {
            it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD
        } ?: return null

        val rect = Rect()
        imeWindow.getBoundsInScreen(rect)
        if (rect.isEmpty || rect.height() <= 0) return null
        return RectSnapshot(rect.left, rect.top, rect.right, rect.bottom)
    }

    private fun isImePackage(pkg: String): Boolean {
        val imm = getSystemService(InputMethodManager::class.java)
        if (imm != null && imm.enabledInputMethodList.any { it.packageName == pkg }) {
            return true
        }
        val winList = try {
            windows
        } catch (_: Exception) {
            null
        } ?: return false
        return winList.any {
            it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD &&
                it.root?.packageName?.toString() == pkg
        }
    }

    private fun handleCheckTapped() {
        val current = sessionEngine.currentState
        if (current !is DictationState.Listening) {
            sessionEngine.onCheckTapped()
            return
        }

        val sid = current.sessionId
        val rawText = sessionEngine.finalizeAccumulatedText()
        sessionEngine.resetToIdle()

        if (rawText.isBlank()) {
            overlayController.renderState(DictationState.Idle(noticeMessage = DictationSessionEngine.MSG_NO_SPEECH_YET))
            return
        }

        overlayController.renderState(
            DictationState.Processing(
                sessionId = sid,
                previewText = rawText,
                statusMessage = "Yapay zeka ile düzeltiliyor... ✦",
            ),
        )

        CoroutineScope(Dispatchers.Main).launch {
            val refined = try {
                withTimeout(35_000L) {
                    when (val res = textRefiner.refineText(rawText)) {
                        is RefinedTextResult.Success -> res.refinedText
                        is RefinedTextResult.Fallback -> res.rawText
                    }
                }
            } catch (_: Exception) {
                rawText
            }

            if (refined.isBlank()) {
                overlayController.renderState(DictationState.Idle(noticeMessage = DictationSessionEngine.MSG_NO_SPEECH_YET))
                return@launch
            }

            overlayController.renderState(
                DictationState.Inserting(sessionId = sid, finalText = refined),
            )

            when (val result = textInserter.insertOrCopy(refined)) {
                is TextInsertResult.Inserted -> {
                    overlayController.renderState(
                        DictationState.Done(
                            sessionId = sid,
                            message = "Düzelttim ✓",
                            inserted = true,
                        ),
                    )
                    mainHandler.postDelayed({
                        if (overlayController.isShowing) {
                            overlayController.collapseToLogo(animate = true)
                        }
                    }, 160L)
                }
                is TextInsertResult.BlockedPassword -> {
                    overlayController.renderState(
                        DictationState.Idle(noticeMessage = DictationSessionEngine.MSG_PASSWORD_BLOCKED),
                    )
                }
                is TextInsertResult.CopiedToClipboard -> {
                    overlayController.renderState(
                        DictationState.Done(
                            sessionId = sid,
                            message = "Düzelttim (Panoya kopyalandı) ✓",
                            inserted = false,
                        ),
                    )
                    mainHandler.postDelayed({
                        if (overlayController.isShowing) {
                            overlayController.collapseToLogo(animate = true)
                        }
                    }, 2200L)
                }
            }
        }
    }

    private fun isIdleOrDone(): Boolean {
        return when (sessionEngine.currentState) {
            is DictationState.Idle, is DictationState.Done -> true
            else -> false
        }
    }

    companion object {
        @Volatile
        var instance: VoiceFlowAccessibilityService? = null
            private set

        fun isServiceRunning(): Boolean = instance != null
    }
}
