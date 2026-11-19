package com.example.voiceflow.service

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import com.example.voiceflow.core.NodeValidator
import com.example.voiceflow.core.TextInsertResult
import com.example.voiceflow.core.TextTargetGateway

data class ComposedInsertion(
    val fullText: String,
    val newCursorOffset: Int,
)

object TextInsertionComposer {

    fun composeTextWithInsertion(
        existingText: String,
        isShowingHint: Boolean,
        selectionStart: Int,
        selectionEnd: Int,
        dictatedText: String,
    ): ComposedInsertion {
        val cleanDictation = dictatedText.trim()
        if (cleanDictation.isEmpty()) {
            return ComposedInsertion(existingText, existingText.length)
        }

        if (isShowingHint || existingText.isEmpty()) {
            return ComposedInsertion(cleanDictation, cleanDictation.length)
        }

        val hasValidSelection =
            selectionStart in 0..existingText.length &&
                selectionEnd in selectionStart..existingText.length

        if (hasValidSelection) {
            val before = existingText.substring(0, selectionStart)
            val after = existingText.substring(selectionEnd)
            val leadingSpace =
                if (before.isNotEmpty() && !before.last().isWhitespace() && !cleanDictation.first().isWhitespace()) {
                    " "
                } else {
                    ""
                }
            val trailingSpace =
                if (after.isNotEmpty() && !after.first().isWhitespace() && !cleanDictation.last().isWhitespace()) {
                    " "
                } else {
                    ""
                }
            val combined = before + leadingSpace + cleanDictation + trailingSpace + after
            val cursor = before.length + leadingSpace.length + cleanDictation.length
            return ComposedInsertion(combined, cursor)
        }

        val separator = if (!existingText.last().isWhitespace()) " " else ""
        val combined = existingText + separator + cleanDictation
        return ComposedInsertion(combined, combined.length)
    }
}

class TextInserter(
    private val context: Context,
    private val resolveTargetNode: () -> AccessibilityNodeInfo?,
    private val expectedPackageProvider: () -> String?,
) : TextTargetGateway {

    override fun insertOrCopy(text: String): TextInsertResult {
        val cleanText = text.trim()
        if (cleanText.isEmpty()) {
            return TextInsertResult.CopiedToClipboard(MSG_EMPTY)
        }

        val node = resolveTargetNode()
        if (node == null || !node.refresh()) {
            return copyToClipboard(cleanText, MSG_COPIED_FIELD_UNAVAILABLE)
        }

        if (NodeValidator.isPasswordNode(node)) {
            return TextInsertResult.BlockedPassword
        }

        val expectedPkg = expectedPackageProvider()
        val nodePkg = node.packageName?.toString()
        if (expectedPkg != null && nodePkg != null && nodePkg != expectedPkg) {
            return copyToClipboard(cleanText, MSG_COPIED_APP_CHANGED)
        }

        if (!node.isEditable || !node.isVisibleToUser || !node.isEnabled) {
            return copyToClipboard(cleanText, MSG_COPIED_FIELD_UNAVAILABLE)
        }

        val existing = node.text?.toString().orEmpty()
        val composed = TextInsertionComposer.composeTextWithInsertion(
            existingText = existing,
            isShowingHint = node.isShowingHintText,
            selectionStart = node.textSelectionStart,
            selectionEnd = node.textSelectionEnd,
            dictatedText = cleanText,
        )

        val args = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                composed.fullText,
            )
        }
        val setTextSuccess = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (setTextSuccess) {
            val selArgs = Bundle().apply {
                putInt(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT,
                    composed.newCursorOffset,
                )
                putInt(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT,
                    composed.newCursorOffset,
                )
            }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selArgs)
            return TextInsertResult.Inserted
        }

        return copyToClipboard(cleanText, MSG_COPIED_DIRECT_INSERT_FAILED)
    }

    private fun copyToClipboard(text: String, reason: String): TextInsertResult {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        clipboard?.setPrimaryClip(ClipData.newPlainText("VoiceFlow", text))
        Toast.makeText(context, reason, Toast.LENGTH_SHORT).show()
        return TextInsertResult.CopiedToClipboard(reason)
    }

    companion object {
        const val MSG_EMPTY = "Eklenecek metin bulunamadı."
        const val MSG_COPIED_FIELD_UNAVAILABLE = "Hedef alan bulunamadı, metin panoya kopyalandı."
        const val MSG_COPIED_APP_CHANGED = "Aktif uygulama değişti, metin panoya kopyalandı."
        const val MSG_COPIED_DIRECT_INSERT_FAILED = "Alana doğrudan yazılamadı, metin panoya kopyalandı."
    }
}
