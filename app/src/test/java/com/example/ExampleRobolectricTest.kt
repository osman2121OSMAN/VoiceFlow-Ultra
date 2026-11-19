package com.example

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.example.ui.theme.VoiceFlowTheme
import com.example.voiceflow.core.DictationState
import com.example.voiceflow.overlay.OverlayController
import com.example.voiceflow.overlay.WaveformPillView
import com.example.voiceflow.ui.OnboardingScreen
import com.example.voiceflow.ui.PermissionSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `app name matches VoiceFlow`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("VoiceFlow", appName)
    }

    @Test
    fun `onboarding renders Turkish permission items and triggers callbacks`() {
        var micClicked = false
        var accessibilityClicked = false
        var settingsClicked = false

        composeRule.setContent {
            VoiceFlowTheme {
                OnboardingScreen(
                    snapshot = PermissionSnapshot(
                        micGranted = false,
                        overlayGranted = true,
                        accessibilityEnabled = false,
                        batteryUnrestricted = false,
                        speechAvailable = true,
                        isXiaomiFamily = true,
                    ),
                    onRequestMic = { micClicked = true },
                    onOpenOverlaySettings = {},
                    onOpenAccessibilitySettings = { accessibilityClicked = true },
                    onOpenXiaomiAutostart = {},
                    onOpenBatterySettings = {},
                    onOpenAppDetails = {},
                    onOpenSettings = { settingsClicked = true },
                )
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithText("VoiceFlow").assertIsDisplayed()
        composeRule.onNodeWithTag("open_settings_button").performClick()
        composeRule.onNodeWithTag("mic_permission_button").performScrollTo().performClick()
        composeRule.onNodeWithTag("accessibility_permission_button").performScrollTo().performClick()
        composeRule.waitForIdle()
        assertTrue(settingsClicked)
        assertTrue(micClicked)
        assertTrue(accessibilityClicked)
    }

    @Test
    fun `overlay builds one view tree with one horizontal row of three controls`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val wm = context.getSystemService(WindowManager::class.java)
        var addedView: View? = null
        var addViewCount = 0
        var updateViewLayoutCount = 0

        val controller = OverlayController(
            context = context,
            windowManager = object : WindowManager by wm {
                override fun addView(view: View, params: ViewGroup.LayoutParams) {
                    addViewCount++
                    addedView = view
                }

                override fun updateViewLayout(view: View, params: ViewGroup.LayoutParams) {
                    updateViewLayoutCount++
                }

                override fun removeViewImmediate(view: View) {
                    addedView = null
                }
            },
            onCancelTapped = {},
            onCenterPillTapped = {},
            onCheckTapped = {},
        )

        controller.show(
            imeRect = null,
            fieldRect = null,
            state = DictationState.Idle(),
        )

        assertEquals(1, addViewCount)
        assertEquals(0, updateViewLayoutCount)
        val root = addedView as LinearLayout
        assertEquals(LinearLayout.VERTICAL, root.orientation)
        assertEquals(2, root.childCount) // 0: logoBtn, 1: boxContainer
        assertEquals(false, root.isSoundEffectsEnabled)

        // Stage 1 (Logo) is active initially; box does NOT open automatically
        val logoBtn = root.getChildAt(0)
        val boxContainer = root.getChildAt(1) as LinearLayout
        assertEquals(View.VISIBLE, logoBtn.visibility)
        assertEquals(View.GONE, boxContainer.visibility)
        assertEquals(false, logoBtn.isSoundEffectsEnabled)

        // Stage 2 (Box) opens on expansion
        controller.expandBox(animate = false)
        assertEquals(View.GONE, logoBtn.visibility)
        assertEquals(View.VISIBLE, boxContainer.visibility)
        assertTrue(controller.isBoxExpanded)

        assertEquals(2, boxContainer.childCount) // 0: preview above, 1: horizontal row
        val row = boxContainer.getChildAt(1) as LinearLayout
        assertEquals(LinearLayout.HORIZONTAL, row.orientation)
        assertEquals(3, row.childCount)
        assertEquals(false, row.isSoundEffectsEnabled)
        assertEquals(false, row.getChildAt(0).isSoundEffectsEnabled)
        assertEquals(false, row.getChildAt(1).isSoundEffectsEnabled)
        assertEquals(false, row.getChildAt(2).isSoundEffectsEnabled)
        assertTrue(row.getChildAt(1) is WaveformPillView)

        // Collapsing returns cleanly to Logo
        controller.collapseToLogo(animate = false)
        assertEquals(View.VISIBLE, logoBtn.visibility)
        assertEquals(View.GONE, boxContainer.visibility)
        assertFalse(controller.isBoxExpanded)
    }
}
