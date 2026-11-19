package com.example.voiceflow.core

import android.text.InputType
import android.view.accessibility.AccessibilityNodeInfo

data class RectSnapshot(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int get() = (right - left).coerceAtLeast(0)
    val height: Int get() = (bottom - top).coerceAtLeast(0)
    val isEmpty: Boolean get() = width <= 0 || height <= 0
}

data class FocusedFieldSnapshot(
    val nodeKey: String,
    val packageName: String,
    val isEditable: Boolean,
    val isVisible: Boolean,
    val isEnabled: Boolean,
    val isPassword: Boolean,
    val bounds: RectSnapshot,
)

object NodeValidator {

    fun isPasswordInputType(inputType: Int): Boolean {
        val klass = inputType and InputType.TYPE_MASK_CLASS
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        return when (klass) {
            InputType.TYPE_CLASS_TEXT -> {
                variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            }
            InputType.TYPE_CLASS_NUMBER -> {
                variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
            }
            else -> false
        }
    }

    fun isEligible(snapshot: FocusedFieldSnapshot?): Boolean {
        if (snapshot == null) return false
        if (!snapshot.isEditable) return false
        if (!snapshot.isVisible) return false
        if (!snapshot.isEnabled) return false
        if (snapshot.isPassword) return false
        if (snapshot.packageName.isBlank()) return false
        return true
    }

    fun isPasswordNode(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        if (node.isPassword) return true
        return isPasswordInputType(node.inputType)
    }

    fun toSnapshot(node: AccessibilityNodeInfo?): FocusedFieldSnapshot? {
        if (node == null) return null
        val rect = android.graphics.Rect()
        node.getBoundsInScreen(rect)
        val pkg = node.packageName?.toString().orEmpty()
        val viewId = node.viewIdResourceName.orEmpty()
        val className = node.className?.toString().orEmpty()
        // Exclude rect.top/bottom so multiline field expansion while typing does not reset the key.
        val key = if (viewId.isNotBlank()) {
            "${node.windowId}|$pkg|$viewId"
        } else {
            "${node.windowId}|$pkg|$className|${rect.left},${rect.right}"
        }
        return FocusedFieldSnapshot(
            nodeKey = key,
            packageName = pkg,
            isEditable = node.isEditable,
            isVisible = node.isVisibleToUser,
            isEnabled = node.isEnabled,
            isPassword = isPasswordNode(node),
            bounds = RectSnapshot(rect.left, rect.top, rect.right, rect.bottom),
        )
    }
}

object OverlayPositionCalculator {

    /**
     * Computes distance in pixels from the bottom of the screen to the bottom edge
     * of the VoiceFlow control row, placing it strictly above the keyboard AND above
     * compact/medium input fields so typed text is never covered.
     */
    fun computeBottomMarginPx(
        screenHeightPx: Int,
        navBarBottomInsetPx: Int,
        imeRect: RectSnapshot?,
        fieldRect: RectSnapshot?,
        gapPx: Int,
        compactFieldMaxHeightPx: Int,
        minTopClearancePx: Int,
    ): Int {
        val hasIme = imeRect != null && !imeRect.isEmpty && imeRect.top < screenHeightPx
        val baseBottomY = if (hasIme) {
            imeRect!!.top
        } else {
            (screenHeightPx - navBarBottomInsetPx).coerceAtLeast(minTopClearancePx)
        }

        val hasField = fieldRect != null && !fieldRect.isEmpty
        val fieldIsCompactInput = hasField &&
            fieldRect!!.height <= compactFieldMaxHeightPx &&
            fieldRect.top - gapPx > minTopClearancePx

        val fieldSitsNearBase = hasField &&
            fieldRect!!.bottom <= baseBottomY + gapPx * 4 &&
            fieldRect.top >= baseBottomY - compactFieldMaxHeightPx - gapPx * 4

        val fieldSitsAboveImpliedKeyboard = hasField &&
            !hasIme &&
            fieldRect!!.bottom < screenHeightPx - navBarBottomInsetPx - compactFieldMaxHeightPx

        val anchorBottomY = if (fieldIsCompactInput && (fieldSitsNearBase || fieldSitsAboveImpliedKeyboard)) {
            minOf(baseBottomY, fieldRect!!.top) - gapPx
        } else {
            baseBottomY - gapPx
        }

        return (screenHeightPx - anchorBottomY).coerceIn(
            navBarBottomInsetPx + gapPx,
            (screenHeightPx - minTopClearancePx).coerceAtLeast(navBarBottomInsetPx + gapPx),
        )
    }

    fun clampOverlayPosition(
        desiredX: Int,
        desiredY: Int,
        screenWidthPx: Int,
        screenHeightPx: Int,
        overlayWidthPx: Int,
        overlayHeightPx: Int,
        navBarBottomInsetPx: Int,
        statusBarTopInsetPx: Int,
        imeRect: RectSnapshot?,
        gapPx: Int,
        edgeMarginPx: Int,
    ): Pair<Int, Int> {
        val maxAbsX = ((screenWidthPx - overlayWidthPx) / 2 - edgeMarginPx).coerceAtLeast(0)
        val clampedX = desiredX.coerceIn(-maxAbsX, maxAbsX)

        val hasIme = imeRect != null && !imeRect.isEmpty && imeRect.top < screenHeightPx
        val minY = if (hasIme) {
            (screenHeightPx - imeRect!!.top + gapPx).coerceAtLeast(navBarBottomInsetPx + gapPx)
        } else {
            navBarBottomInsetPx + gapPx
        }
        val maxY = (screenHeightPx - statusBarTopInsetPx - overlayHeightPx).coerceAtLeast(minY)
        val clampedY = desiredY.coerceIn(minY, maxY)

        return clampedX to clampedY
    }

    fun computeDefaultTopLeftPx(
        screenWidthPx: Int,
        screenHeightPx: Int,
        overlayWidthPx: Int,
        overlayHeightPx: Int,
        navBarBottomInsetPx: Int,
        statusBarTopInsetPx: Int,
        imeRect: RectSnapshot?,
        fieldRect: RectSnapshot?,
        gapPx: Int,
        compactFieldMaxHeightPx: Int,
        minTopClearancePx: Int,
        edgeMarginPx: Int,
    ): Pair<Int, Int> {
        val bottomMarginPx = computeBottomMarginPx(
            screenHeightPx = screenHeightPx,
            navBarBottomInsetPx = navBarBottomInsetPx,
            imeRect = imeRect,
            fieldRect = fieldRect,
            gapPx = gapPx,
            compactFieldMaxHeightPx = compactFieldMaxHeightPx,
            minTopClearancePx = minTopClearancePx,
        )
        val centeredX = (screenWidthPx - overlayWidthPx) / 2
        val desiredY = screenHeightPx - bottomMarginPx - overlayHeightPx
        return clampWindowTopLeft(
            desiredX = centeredX,
            desiredY = desiredY,
            screenWidthPx = screenWidthPx,
            screenHeightPx = screenHeightPx,
            overlayWidthPx = overlayWidthPx,
            overlayHeightPx = overlayHeightPx,
            navBarBottomInsetPx = navBarBottomInsetPx,
            statusBarTopInsetPx = statusBarTopInsetPx,
            edgeMarginPx = edgeMarginPx,
        )
    }

    fun clampWindowTopLeft(
        desiredX: Int,
        desiredY: Int,
        screenWidthPx: Int,
        screenHeightPx: Int,
        overlayWidthPx: Int,
        overlayHeightPx: Int,
        navBarBottomInsetPx: Int,
        statusBarTopInsetPx: Int,
        edgeMarginPx: Int,
    ): Pair<Int, Int> {
        val minX = edgeMarginPx
        val maxX = (screenWidthPx - overlayWidthPx - edgeMarginPx).coerceAtLeast(minX)
        val clampedX = desiredX.coerceIn(minX, maxX)

        val minY = statusBarTopInsetPx + edgeMarginPx
        val maxY = (screenHeightPx - navBarBottomInsetPx - overlayHeightPx - edgeMarginPx).coerceAtLeast(minY)
        val clampedY = desiredY.coerceIn(minY, maxY)

        return clampedX to clampedY
    }
}
