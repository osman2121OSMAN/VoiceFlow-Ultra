package com.example.voiceflow.core

class FocusVisibilityCoordinator(
    private val scheduler: SchedulerGateway,
    private val isOverlayShowing: () -> Boolean,
    private val isDictationActive: () -> Boolean,
    private val resolveCurrentSnapshot: () -> Pair<FocusedFieldSnapshot?, Boolean>,
    private val isImeVisibleNow: () -> Boolean,
    private val onShowOverlay: (FocusedFieldSnapshot) -> Unit,
    private val onHideOverlay: (clearDismissedKey: Boolean) -> Unit,
) {
    var activeField: FocusedFieldSnapshot? = null
        private set
    var dismissedNodeKey: String? = null
        private set
    var wasImeVisible: Boolean = false
        private set

    private var isStableHideScheduled = false
    private var isImeCloseScheduled = false
    private var pendingShowNodeKey: String? = null
    private var hasWaitedForImeSettle = false

    fun onOverlayManuallyDismissed() {
        dismissedNodeKey = activeField?.nodeKey
        cancelShowDebounce()
        cancelStableHide()
        cancelImeCloseCheck()
        wasImeVisible = false
        onHideOverlay(false)
    }

    fun resetAll() {
        cancelShowDebounce()
        cancelStableHide()
        cancelImeCloseCheck()
        activeField = null
        wasImeVisible = false
        hasWaitedForImeSettle = false
        onHideOverlay(false)
    }

    fun onFocusOrContentEvent(
        snapshot: FocusedFieldSnapshot?,
        isPassword: Boolean,
        isExplicitFocusOrClick: Boolean,
        isImePackage: Boolean,
    ) {
        if (isImePackage) return

        if (isPassword) {
            dismissedNodeKey = null
            resetAll()
            return
        }

        if (snapshot != null && NodeValidator.isEligible(snapshot)) {
            // Eligible field is focused right now: cancel any pending stable-hide check.
            cancelStableHide()

            if (isExplicitFocusOrClick && snapshot.nodeKey == dismissedNodeKey) {
                dismissedNodeKey = null
            }
            if (snapshot.nodeKey == dismissedNodeKey) {
                return
            }

            if (isOverlayShowing() && activeField?.nodeKey == snapshot.nodeKey) {
                // Keep showing without moving or resetting while the same field stays focused.
                activeField = snapshot
                return
            }

            // Do not reset debounce countdown if already pending for the same field.
            if (pendingShowNodeKey == snapshot.nodeKey) {
                return
            }

            pendingShowNodeKey = snapshot.nodeKey
            hasWaitedForImeSettle = false
            scheduler.postDelayed(TOKEN_SHOW_DEBOUNCE, SHOW_DEBOUNCE_MS) {
                pendingShowNodeKey = null
                evaluateAndShow(fallbackSnapshot = snapshot)
            }
        } else if (isOverlayShowing()) {
            // Do not hide on a single null/non-editable event; wait for stable 500ms absence.
            scheduleStableHideIfNeeded()
        }
    }

    fun onWindowStateChanged(
        newPackageName: String?,
        isImePackage: Boolean,
        isFullScreenWindow: Boolean,
        ownPackageName: String,
    ) {
        if (newPackageName == null || isImePackage) return

        val (currentSnapshot, isPassword) = resolveCurrentSnapshot()
        if (isPassword) {
            dismissedNodeKey = null
            resetAll()
            return
        }

        if (currentSnapshot != null && NodeValidator.isEligible(currentSnapshot)) {
            cancelStableHide()
            onFocusOrContentEvent(
                snapshot = currentSnapshot,
                isPassword = false,
                isExplicitFocusOrClick = false,
                isImePackage = false,
            )
            return
        }

        val currentPkg = activeField?.packageName
        if (newPackageName == ownPackageName && currentPkg != null && currentPkg != ownPackageName) {
            dismissedNodeKey = null
            resetAll()
            return
        }

        if (currentPkg != null && newPackageName != currentPkg) {
            if (isFullScreenWindow && !isDictationActive()) {
                dismissedNodeKey = null
                resetAll()
            } else if (isOverlayShowing()) {
                scheduleStableHideIfNeeded()
            }
        }
    }

    fun onWindowsChanged(imeVisibleNow: Boolean) {
        val (currentSnapshot, isPassword) = resolveCurrentSnapshot()
        if (isPassword) {
            dismissedNodeKey = null
            resetAll()
            return
        }

        if (imeVisibleNow) {
            wasImeVisible = true
            cancelImeCloseCheck()
        } else if (wasImeVisible) {
            if (!isImeCloseScheduled) {
                isImeCloseScheduled = true
                scheduler.postDelayed(TOKEN_IME_CLOSE_CHECK, IME_CLOSE_CONFIRM_MS) {
                    isImeCloseScheduled = false
                    if (!isImeVisibleNow()) {
                        wasImeVisible = false
                        dismissedNodeKey = null
                        resetAll()
                    }
                }
            }
        }

        if (currentSnapshot != null && NodeValidator.isEligible(currentSnapshot)) {
            cancelStableHide()
        } else if (isOverlayShowing()) {
            scheduleStableHideIfNeeded()
        }
    }

    private fun evaluateAndShow(fallbackSnapshot: FocusedFieldSnapshot?) {
        val (liveSnapshot, isPassword) = resolveCurrentSnapshot()
        if (isPassword) {
            dismissedNodeKey = null
            resetAll()
            return
        }

        val candidate = if (liveSnapshot != null && NodeValidator.isEligible(liveSnapshot)) {
            liveSnapshot
        } else if (fallbackSnapshot != null && NodeValidator.isEligible(fallbackSnapshot)) {
            if (activeField == null || fallbackSnapshot.packageName == activeField?.packageName) {
                fallbackSnapshot
            } else {
                null
            }
        } else {
            null
        }

        if (candidate == null) {
            return
        }

        if (candidate.nodeKey == dismissedNodeKey) {
            return
        }

        // Give IME one brief extra 150ms chance to appear before locking window position,
        // so the first show() docks strictly above the keyboard without ever moving later.
        if (!isOverlayShowing() && !isImeVisibleNow() && !hasWaitedForImeSettle) {
            hasWaitedForImeSettle = true
            pendingShowNodeKey = candidate.nodeKey
            scheduler.postDelayed(TOKEN_SHOW_DEBOUNCE, IME_SETTLE_DELAY_MS) {
                pendingShowNodeKey = null
                evaluateAndShow(fallbackSnapshot = candidate)
            }
            return
        }

        cancelStableHide()

        val previousKey = activeField?.nodeKey
        if (isOverlayShowing() && previousKey == candidate.nodeKey) {
            activeField = candidate
            return
        }

        if (isOverlayShowing() && previousKey != candidate.nodeKey) {
            onHideOverlay(false)
        }

        activeField = candidate
        if (isImeVisibleNow()) {
            wasImeVisible = true
        }
        // Never hide on the same event that showed the control.
        onShowOverlay(candidate)
    }

    private fun scheduleStableHideIfNeeded() {
        if (isStableHideScheduled) return
        isStableHideScheduled = true
        scheduler.postDelayed(TOKEN_STABLE_HIDE, STABLE_HIDE_MS) {
            isStableHideScheduled = false
            val (liveSnapshot, isPassword) = resolveCurrentSnapshot()
            if (isPassword) {
                dismissedNodeKey = null
                resetAll()
                return@postDelayed
            }
            if (liveSnapshot != null && NodeValidator.isEligible(liveSnapshot)) {
                activeField = liveSnapshot
                return@postDelayed
            }
            resetAll()
        }
    }

    private fun cancelShowDebounce() {
        pendingShowNodeKey = null
        hasWaitedForImeSettle = false
        scheduler.cancel(TOKEN_SHOW_DEBOUNCE)
    }

    private fun cancelStableHide() {
        isStableHideScheduled = false
        scheduler.cancel(TOKEN_STABLE_HIDE)
    }

    private fun cancelImeCloseCheck() {
        isImeCloseScheduled = false
        scheduler.cancel(TOKEN_IME_CLOSE_CHECK)
    }

    companion object {
        const val SHOW_DEBOUNCE_MS = 300L
        const val IME_SETTLE_DELAY_MS = 150L
        const val STABLE_HIDE_MS = 500L
        const val IME_CLOSE_CONFIRM_MS = 200L

        const val TOKEN_SHOW_DEBOUNCE = "vf_show_debounce"
        const val TOKEN_STABLE_HIDE = "vf_stable_hide"
        const val TOKEN_IME_CLOSE_CHECK = "vf_ime_close_check"
    }
}
