package com.example.voiceflow.core

import android.text.InputType
import com.example.voiceflow.service.TextInsertionComposer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NodeValidatorAndPositionTest {

    @Test
    fun `password input types are strictly blocked`() {
        assertTrue(
            NodeValidator.isPasswordInputType(
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
            ),
        )
        assertTrue(
            NodeValidator.isPasswordInputType(
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            ),
        )
        assertTrue(
            NodeValidator.isPasswordInputType(
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            ),
        )
        assertTrue(
            NodeValidator.isPasswordInputType(
                InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD,
            ),
        )
        assertFalse(
            NodeValidator.isPasswordInputType(
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            ),
        )
    }

    @Test
    fun `eligibility requires editable visible enabled non-password`() {
        val base = FocusedFieldSnapshot(
            nodeKey = "1|com.whatsapp|input|0,1300,1080,1450",
            packageName = "com.whatsapp",
            isEditable = true,
            isVisible = true,
            isEnabled = true,
            isPassword = false,
            bounds = RectSnapshot(0, 1300, 1080, 1450),
        )
        assertTrue(NodeValidator.isEligible(base))
        assertFalse(NodeValidator.isEligible(base.copy(isPassword = true)))
        assertFalse(NodeValidator.isEligible(base.copy(isEditable = false)))
        assertFalse(NodeValidator.isEligible(base.copy(isVisible = false)))
        assertFalse(NodeValidator.isEligible(base.copy(isEnabled = false)))
    }

    @Test
    fun `overlay clears compact bottom input bar above keyboard`() {
        val screenH = 2400
        val imeRect = RectSnapshot(0, 1500, 1080, 2400)
        val whatsappInput = RectSnapshot(120, 1340, 900, 1480) // 140px tall right above IME

        val margin = OverlayPositionCalculator.computeBottomMarginPx(
            screenHeightPx = screenH,
            navBarBottomInsetPx = 100,
            imeRect = imeRect,
            fieldRect = whatsappInput,
            gapPx = 24,
            compactFieldMaxHeightPx = 320,
            minTopClearancePx = 400,
        )

        // Bottom of overlay should be at 1340 - 24 = 1316 -> margin from bottom = 2400 - 1316 = 1084
        assertEquals(1084, margin)
    }

    @Test
    fun `overlay docks just above keyboard for tall multi-line editor`() {
        val screenH = 2400
        val imeRect = RectSnapshot(0, 1500, 1080, 2400)
        val tallEditor = RectSnapshot(0, 300, 1080, 1490)

        val margin = OverlayPositionCalculator.computeBottomMarginPx(
            screenHeightPx = screenH,
            navBarBottomInsetPx = 100,
            imeRect = imeRect,
            fieldRect = tallEditor,
            gapPx = 24,
            compactFieldMaxHeightPx = 320,
            minTopClearancePx = 400,
        )

        // Docks right above IME top (1500 - 24 = 1476 -> margin = 924)
        assertEquals(924, margin)
    }

    @Test
    fun `text insertion ignores hint placeholder and inserts cleanly at cursor`() {
        val hintResult = TextInsertionComposer.composeTextWithInsertion(
            existingText = "Mesaj",
            isShowingHint = true,
            selectionStart = 0,
            selectionEnd = 0,
            dictatedText = "merhaba",
        )
        assertEquals("merhaba", hintResult.fullText)
        assertEquals(7, hintResult.newCursorOffset)

        val cursorResult = TextInsertionComposer.composeTextWithInsertion(
            existingText = "Selam Ali",
            isShowingHint = false,
            selectionStart = 5,
            selectionEnd = 5,
            dictatedText = "dostum",
        )
        assertEquals("Selam dostum Ali", cursorResult.fullText)
        assertEquals(12, cursorResult.newCursorOffset)
    }

    @Test
    fun `clampOverlayPosition constrains horizontal and vertical bounds above keyboard`() {
        val screenW = 1080
        val screenH = 2400
        val overlayW = 480
        val overlayH = 180
        val imeRect = RectSnapshot(0, 1500, 1080, 2400)

        // maxAbsX = (1080 - 480)/2 - 16 = 284
        // minY = (2400 - 1500 + 24) = 924
        // maxY = (2400 - 80 - 180) = 2140
        val (clampedLeft, clampedLow) = OverlayPositionCalculator.clampOverlayPosition(
            desiredX = -999,
            desiredY = 100,
            screenWidthPx = screenW,
            screenHeightPx = screenH,
            overlayWidthPx = overlayW,
            overlayHeightPx = overlayH,
            navBarBottomInsetPx = 100,
            statusBarTopInsetPx = 80,
            imeRect = imeRect,
            gapPx = 24,
            edgeMarginPx = 16,
        )
        assertEquals(-284, clampedLeft)
        assertEquals(924, clampedLow)

        val (clampedRight, clampedHigh) = OverlayPositionCalculator.clampOverlayPosition(
            desiredX = 999,
            desiredY = 5000,
            screenWidthPx = screenW,
            screenHeightPx = screenH,
            overlayWidthPx = overlayW,
            overlayHeightPx = overlayH,
            navBarBottomInsetPx = 100,
            statusBarTopInsetPx = 80,
            imeRect = imeRect,
            gapPx = 24,
            edgeMarginPx = 16,
        )
        assertEquals(284, clampedRight)
        assertEquals(2140, clampedHigh)
    }

    @Test
    fun `clampOverlayPosition uses navBar inset floor when keyboard is closed`() {
        val (x, y) = OverlayPositionCalculator.clampOverlayPosition(
            desiredX = 45,
            desiredY = 20,
            screenWidthPx = 1080,
            screenHeightPx = 2400,
            overlayWidthPx = 480,
            overlayHeightPx = 180,
            navBarBottomInsetPx = 110,
            statusBarTopInsetPx = 80,
            imeRect = null,
            gapPx = 24,
            edgeMarginPx = 16,
        )
        assertEquals(45, x)
        assertEquals(134, y) // 110 + 24
    }

    @Test
    fun `clampWindowTopLeft keeps freely dragged window inside 16-9 screen safe bounds`() {
        val (clampedMinX, clampedMinY) = OverlayPositionCalculator.clampWindowTopLeft(
            desiredX = -100,
            desiredY = 0,
            screenWidthPx = 1080,
            screenHeightPx = 1920,
            overlayWidthPx = 480,
            overlayHeightPx = 220,
            navBarBottomInsetPx = 96,
            statusBarTopInsetPx = 48,
            edgeMarginPx = 16,
        )
        assertEquals(16, clampedMinX)
        assertEquals(64, clampedMinY) // 48 + 16

        val (clampedMaxX, clampedMaxY) = OverlayPositionCalculator.clampWindowTopLeft(
            desiredX = 2000,
            desiredY = 5000,
            screenWidthPx = 1080,
            screenHeightPx = 1920,
            overlayWidthPx = 480,
            overlayHeightPx = 220,
            navBarBottomInsetPx = 96,
            statusBarTopInsetPx = 48,
            edgeMarginPx = 16,
        )
        assertEquals(1080 - 480 - 16, clampedMaxX)
        assertEquals(1920 - 96 - 220 - 16, clampedMaxY)
    }

    @Test
    fun `computeDefaultTopLeftPx centers horizontally above keyboard on initial open`() {
        val (x, y) = OverlayPositionCalculator.computeDefaultTopLeftPx(
            screenWidthPx = 1080,
            screenHeightPx = 2400,
            overlayWidthPx = 480,
            overlayHeightPx = 200,
            navBarBottomInsetPx = 100,
            statusBarTopInsetPx = 80,
            imeRect = RectSnapshot(0, 1500, 1080, 2400),
            fieldRect = RectSnapshot(0, 300, 1080, 1490),
            gapPx = 24,
            compactFieldMaxHeightPx = 320,
            minTopClearancePx = 400,
            edgeMarginPx = 16,
        )
        assertEquals((1080 - 480) / 2, x)
        // Bottom margin = 2400 - (1500 - 24) = 924 -> top Y = 2400 - 924 - 200 = 1276
        assertEquals(1276, y)
    }
}
