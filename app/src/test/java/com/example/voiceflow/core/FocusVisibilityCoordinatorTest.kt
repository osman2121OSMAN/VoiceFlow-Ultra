package com.example.voiceflow.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FocusVisibilityCoordinatorTest {

    private class FakeScheduler : SchedulerGateway {
        val scheduled = linkedMapOf<String, Pair<Long, () -> Unit>>()

        override fun postDelayed(token: String, delayMs: Long, action: () -> Unit) {
            scheduled[token] = delayMs to action
        }

        override fun cancel(token: String) {
            scheduled.remove(token)
        }

        override fun cancelAll() {
            scheduled.clear()
        }

        fun hasToken(token: String): Boolean = scheduled.containsKey(token)

        fun trigger(token: String) {
            scheduled.remove(token)?.second?.invoke()
        }
    }

    private fun sampleField(pkg: String = "com.whatsapp", id: String = "entry") = FocusedFieldSnapshot(
        nodeKey = "10|$pkg|$id",
        packageName = pkg,
        isEditable = true,
        isVisible = true,
        isEnabled = true,
        isPassword = false,
        bounds = RectSnapshot(100, 1340, 900, 1460),
    )

    @Test
    fun `single transient null event does not vanish control and cancels hide when focus returns`() {
        val scheduler = FakeScheduler()
        var showing = false
        var liveSnapshot: FocusedFieldSnapshot? = sampleField()
        var liveIsPassword = false
        var imeVisible = true

        val coordinator = FocusVisibilityCoordinator(
            scheduler = scheduler,
            isOverlayShowing = { showing },
            isDictationActive = { false },
            resolveCurrentSnapshot = { liveSnapshot to liveIsPassword },
            isImeVisibleNow = { imeVisible },
            onShowOverlay = { showing = true },
            onHideOverlay = { showing = false },
        )

        // Show after 300ms debounce
        coordinator.onFocusOrContentEvent(liveSnapshot, isPassword = false, isExplicitFocusOrClick = true, isImePackage = false)
        assertTrue(scheduler.hasToken(FocusVisibilityCoordinator.TOKEN_SHOW_DEBOUNCE))
        assertEquals(300L, scheduler.scheduled[FocusVisibilityCoordinator.TOKEN_SHOW_DEBOUNCE]?.first)
        scheduler.trigger(FocusVisibilityCoordinator.TOKEN_SHOW_DEBOUNCE)
        assertTrue(showing)

        // Single transient null event (e.g. cursor blink / content change) -> schedules 500ms stable hide, stays visible!
        liveSnapshot = null
        coordinator.onFocusOrContentEvent(null, isPassword = false, isExplicitFocusOrClick = false, isImePackage = false)
        assertTrue(showing)
        assertTrue(scheduler.hasToken(FocusVisibilityCoordinator.TOKEN_STABLE_HIDE))
        assertEquals(500L, scheduler.scheduled[FocusVisibilityCoordinator.TOKEN_STABLE_HIDE]?.first)

        // Eligible focus event arrives before 500ms -> cancels stable hide, stays visible!
        liveSnapshot = sampleField()
        coordinator.onFocusOrContentEvent(liveSnapshot, isPassword = false, isExplicitFocusOrClick = false, isImePackage = false)
        assertFalse(scheduler.hasToken(FocusVisibilityCoordinator.TOKEN_STABLE_HIDE))
        assertTrue(showing)
    }

    @Test
    fun `stable absence of eligible field hides after 500ms`() {
        val scheduler = FakeScheduler()
        var showing = false
        var liveSnapshot: FocusedFieldSnapshot? = sampleField()

        val coordinator = FocusVisibilityCoordinator(
            scheduler = scheduler,
            isOverlayShowing = { showing },
            isDictationActive = { false },
            resolveCurrentSnapshot = { liveSnapshot to false },
            isImeVisibleNow = { true },
            onShowOverlay = { showing = true },
            onHideOverlay = { showing = false },
        )

        coordinator.onFocusOrContentEvent(liveSnapshot, isPassword = false, isExplicitFocusOrClick = true, isImePackage = false)
        scheduler.trigger(FocusVisibilityCoordinator.TOKEN_SHOW_DEBOUNCE)
        assertTrue(showing)

        liveSnapshot = null
        coordinator.onFocusOrContentEvent(null, isPassword = false, isExplicitFocusOrClick = false, isImePackage = false)
        assertTrue(showing)

        scheduler.trigger(FocusVisibilityCoordinator.TOKEN_STABLE_HIDE)
        assertFalse(showing)
    }

    @Test
    fun `password field hides immediately and ime window events are ignored`() {
        val scheduler = FakeScheduler()
        var showing = false
        var liveSnapshot: FocusedFieldSnapshot? = sampleField()
        var livePassword = false

        val coordinator = FocusVisibilityCoordinator(
            scheduler = scheduler,
            isOverlayShowing = { showing },
            isDictationActive = { false },
            resolveCurrentSnapshot = { liveSnapshot to livePassword },
            isImeVisibleNow = { true },
            onShowOverlay = { showing = true },
            onHideOverlay = { showing = false },
        )

        coordinator.onFocusOrContentEvent(liveSnapshot, isPassword = false, isExplicitFocusOrClick = true, isImePackage = false)
        scheduler.trigger(FocusVisibilityCoordinator.TOKEN_SHOW_DEBOUNCE)
        assertTrue(showing)

        // IME package window change must be ignored
        coordinator.onWindowStateChanged("com.google.android.inputmethod.latin", isImePackage = true, isFullScreenWindow = false, ownPackageName = "com.example")
        assertTrue(showing)

        // Password field hides immediately
        liveSnapshot = null
        livePassword = true
        coordinator.onFocusOrContentEvent(null, isPassword = true, isExplicitFocusOrClick = true, isImePackage = false)
        assertFalse(showing)
    }

    @Test
    fun `repeated content events for same field do not starve debounce and ime settle shows once`() {
        val scheduler = FakeScheduler()
        var showing = false
        var showCount = 0
        var imeVisible = false
        val field = sampleField()

        val coordinator = FocusVisibilityCoordinator(
            scheduler = scheduler,
            isOverlayShowing = { showing },
            isDictationActive = { false },
            resolveCurrentSnapshot = { field to false },
            isImeVisibleNow = { imeVisible },
            onShowOverlay = {
                showing = true
                showCount++
            },
            onHideOverlay = { showing = false },
        )

        coordinator.onFocusOrContentEvent(field, isPassword = false, isExplicitFocusOrClick = true, isImePackage = false)
        assertEquals(300L, scheduler.scheduled[FocusVisibilityCoordinator.TOKEN_SHOW_DEBOUNCE]?.first)

        // Repeated content event for the same field keeps the existing 300ms debounce intact
        coordinator.onFocusOrContentEvent(field, isPassword = false, isExplicitFocusOrClick = false, isImePackage = false)
        assertEquals(300L, scheduler.scheduled[FocusVisibilityCoordinator.TOKEN_SHOW_DEBOUNCE]?.first)

        // Trigger 300ms debounce while IME is still opening -> waits once for 150ms IME settle
        scheduler.trigger(FocusVisibilityCoordinator.TOKEN_SHOW_DEBOUNCE)
        assertFalse(showing)
        assertEquals(150L, scheduler.scheduled[FocusVisibilityCoordinator.TOKEN_SHOW_DEBOUNCE]?.first)

        // IME opens during settle -> shows ONCE
        imeVisible = true
        scheduler.trigger(FocusVisibilityCoordinator.TOKEN_SHOW_DEBOUNCE)
        assertTrue(showing)
        assertEquals(1, showCount)
    }
}
