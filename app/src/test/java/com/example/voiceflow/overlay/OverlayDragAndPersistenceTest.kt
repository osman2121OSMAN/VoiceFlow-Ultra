package com.example.voiceflow.overlay

import android.content.Context
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import com.example.voiceflow.core.DictationState
import com.example.voiceflow.core.RectSnapshot
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w412dp-h915dp-xxhdpi")
class OverlayDragAndPersistenceTest {

    private class RecordingWindowManager(
        delegate: WindowManager,
    ) : WindowManager by delegate {
        var addedView: View? = null
        var lastParams: WindowManager.LayoutParams? = null
        var updateViewLayoutCount: Int = 0

        override fun addView(view: View, params: ViewGroup.LayoutParams) {
            addedView = view
            lastParams = params as WindowManager.LayoutParams
        }

        override fun updateViewLayout(view: View, params: ViewGroup.LayoutParams) {
            updateViewLayoutCount++
            lastParams = params as WindowManager.LayoutParams
        }

        override fun removeViewImmediate(view: View) {
            addedView = null
        }
    }

    private fun layoutOverlayRoot(root: View, maxWidthPx: Int = 1236, maxHeightPx: Int = 2745) {
        val widthSpec = View.MeasureSpec.makeMeasureSpec(maxWidthPx, View.MeasureSpec.AT_MOST)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(maxHeightPx, View.MeasureSpec.AT_MOST)
        root.measure(widthSpec, heightSpec)
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)
    }

    private fun dispatchTouchSequence(
        target: View,
        downX: Float,
        downY: Float,
        moveRawDx: Float,
        moveRawDy: Float,
    ) {
        val downTime = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, downX, downY, 0)
        target.dispatchTouchEvent(down)
        down.recycle()

        if (moveRawDx != 0f || moveRawDy != 0f) {
            val move = MotionEvent.obtain(downTime, downTime + 20L, MotionEvent.ACTION_MOVE, downX + moveRawDx, downY + moveRawDy, 0)
            target.dispatchTouchEvent(move)
            move.recycle()
        }

        val up = MotionEvent.obtain(downTime, downTime + 40L, MotionEvent.ACTION_UP, downX + moveRawDx, downY + moveRawDy, 0)
        target.dispatchTouchEvent(up)
        up.recycle()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun `drag in Stage 1 Logo and Stage 2 Box moves TOP-START window, saves to DataStore, and restores exact spot`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val realWm = context.getSystemService(WindowManager::class.java)
        val recWm = RecordingWindowManager(realWm)

        val tempFile = File.createTempFile("vf_pos_test_top_start", ".preferences_pb")
        tempFile.deleteOnExit()
        val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val dataStore = PreferenceDataStoreFactory.create(scope = storeScope) { tempFile }
        val store = OverlayPositionStore(dataStore, storeScope)

        val draggedX: Int
        val draggedY: Int
        try {
            var cancelClicks = 0
            var pillClicks = 0
            var checkClicks = 0

            val controller = OverlayController(
                context = context,
                windowManager = recWm,
                onCancelTapped = { cancelClicks++ },
                onCenterPillTapped = { pillClicks++ },
                onCheckTapped = { checkClicks++ },
                positionStore = store,
                imeRectProvider = { null },
            )

            controller.show(imeRect = null, fieldRect = null, state = DictationState.Idle())
            assertTrue(recWm.addedView is DraggableWindowWrapper)
            assertEquals(Gravity.TOP or Gravity.START, recWm.lastParams!!.gravity)
            val root = recWm.addedView as DraggableWindowWrapper
            layoutOverlayRoot(root)
            val initialX = recWm.lastParams!!.x
            val initialY = recWm.lastParams!!.y

            // Stage 1: Logo is visible, box is GONE
            val logoBtn = root.getChildAt(0)
            val boxContainer = root.getChildAt(1) as LinearLayout
            assertEquals(View.VISIBLE, logoBtn.visibility)
            assertEquals(View.GONE, boxContainer.visibility)

            // Drag starting from Logo upward & right
            dispatchTouchSequence(
                target = root,
                downX = (logoBtn.left + logoBtn.right) / 2f,
                downY = (logoBtn.top + logoBtn.bottom) / 2f,
                moveRawDx = 80f,
                moveRawDy = -120f, // upward drag decreases top Y
            )

            assertTrue("Expected updateViewLayout during Stage 1 logo drag", recWm.updateViewLayoutCount >= 1)
            draggedX = recWm.lastParams!!.x
            draggedY = recWm.lastParams!!.y
            assertTrue("Expected X to increase on rightward drag (was $initialX, now $draggedX)", draggedX > initialX)
            assertTrue("Expected Y to decrease on upward drag (was $initialY, now $draggedY)", draggedY < initialY)

            // Tap logo to expand to Stage 2 Box
            controller.expandBox(animate = false)
            layoutOverlayRoot(root)
            assertEquals(View.GONE, logoBtn.visibility)
            assertEquals(View.VISIBLE, boxContainer.visibility)
            assertTrue(controller.isBoxExpanded)

            // Drag in Stage 2 from preview box
            val previewBox = boxContainer.getChildAt(0)
            val beforeBoxDragUpdates = recWm.updateViewLayoutCount
            val beforeBoxX = recWm.lastParams!!.x
            dispatchTouchSequence(
                target = root,
                downX = (previewBox.left + previewBox.right) / 2f,
                downY = previewBox.top + 8f,
                moveRawDx = 40f,
                moveRawDy = -30f,
            )
            assertTrue("Expected updateViewLayout during Stage 2 box drag", recWm.updateViewLayoutCount > beforeBoxDragUpdates)
            assertTrue(recWm.lastParams!!.x > beforeBoxX)

            assertEquals(0, cancelClicks)
            assertEquals(0, pillClicks)
            assertEquals(0, checkClicks)

            Thread.sleep(200)
            controller.hide()
        } finally {
            runBlocking {
                storeScope.coroutineContext[Job]?.cancelAndJoin()
            }
        }

        val store2Scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val dataStore2 = PreferenceDataStoreFactory.create(scope = store2Scope) { tempFile }
        val store2 = OverlayPositionStore(dataStore2, store2Scope)
        try {
            val restored = store2.getPosition()
            assertNotNull(restored)

            val recWm2 = RecordingWindowManager(realWm)
            val controller2 = OverlayController(
                context = context,
                windowManager = recWm2,
                onCancelTapped = {},
                onCenterPillTapped = {},
                onCheckTapped = {},
                positionStore = store2,
                imeRectProvider = { null },
            )
            controller2.show(imeRect = null, fieldRect = null, state = DictationState.Idle())
            assertEquals(restored!!.x, recWm2.lastParams!!.x)
            assertEquals(restored.y, recWm2.lastParams!!.y)
        } finally {
            runBlocking {
                store2Scope.coroutineContext[Job]?.cancelAndJoin()
            }
        }
    }

    @Test
    fun `sliding on waveform pill drags window without triggering tap, while short tap starts listening and X-Check never drag`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val realWm = context.getSystemService(WindowManager::class.java)
        val recWm = RecordingWindowManager(realWm)
        var cancelClicks = 0
        var pillClicks = 0
        var checkClicks = 0

        val controller = OverlayController(
            context = context,
            windowManager = recWm,
            onCancelTapped = { cancelClicks++ },
            onCenterPillTapped = { pillClicks++ },
            onCheckTapped = { checkClicks++ },
        )

        controller.show(imeRect = null, fieldRect = null, state = DictationState.Idle())
        // Expand to Stage 2 Box
        controller.expandBox(animate = false)

        val root = recWm.addedView as DraggableWindowWrapper
        layoutOverlayRoot(root)
        val boxContainer = root.getChildAt(1) as LinearLayout
        val row = boxContainer.getChildAt(1) as LinearLayout
        val closeBtn = row.getChildAt(0)
        val pill = row.getChildAt(1) as WaveformPillView
        val checkBtn = row.getChildAt(2)

        val initialUpdates = recWm.updateViewLayoutCount

        // 1) Drag attempt starting inside X button circle -> does not drag
        dispatchTouchSequence(
            target = root,
            downX = row.left + (closeBtn.left + closeBtn.right) / 2f,
            downY = row.top + (closeBtn.top + closeBtn.bottom) / 2f,
            moveRawDx = 70f,
            moveRawDy = -70f,
        )
        assertEquals(initialUpdates, recWm.updateViewLayoutCount)

        // 2) Drag attempt starting inside Check button circle -> does not drag
        dispatchTouchSequence(
            target = root,
            downX = row.left + (checkBtn.left + checkBtn.right) / 2f,
            downY = row.top + (checkBtn.top + checkBtn.bottom) / 2f,
            moveRawDx = 70f,
            moveRawDy = -70f,
        )
        assertEquals(initialUpdates, recWm.updateViewLayoutCount)

        // 3) Slide > touchSlop starting on center of waveform pill -> drags window & does NOT trigger dictation tap!
        dispatchTouchSequence(
            target = root,
            downX = row.left + (pill.left + pill.right) / 2f,
            downY = row.top + (pill.top + pill.bottom) / 2f,
            moveRawDx = 80f,
            moveRawDy = -90f,
        )
        assertTrue("Sliding on pill must drag window", recWm.updateViewLayoutCount > initialUpdates)
        assertEquals(0, pillClicks)

        // 4) Clean short tap on center pill -> triggers listening!
        val beforeTapCount = recWm.updateViewLayoutCount
        dispatchTouchSequence(
            target = root,
            downX = row.left + (pill.left + pill.right) / 2f,
            downY = row.top + (pill.top + pill.bottom) / 2f,
            moveRawDx = 0f,
            moveRawDy = 0f,
        )
        assertEquals(1, pillClicks)
        assertEquals(beforeTapCount, recWm.updateViewLayoutCount)
        assertEquals(0, cancelClicks)
        assertEquals(0, checkClicks)
    }

    @Test
    fun `active listening waveform window is also freely draggable anywhere on screen`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val realWm = context.getSystemService(WindowManager::class.java)
        val recWm = RecordingWindowManager(realWm)

        val controller = OverlayController(
            context = context,
            windowManager = recWm,
            onCancelTapped = {},
            onCenterPillTapped = {},
            onCheckTapped = {},
        )

        controller.show(
            imeRect = RectSnapshot(0, 1500, 1080, 2400),
            fieldRect = null,
            state = DictationState.Listening(sessionId = 1L, previewText = "Merhaba", rmsDb = 5f),
        )
        val root = recWm.addedView as DraggableWindowWrapper
        layoutOverlayRoot(root)
        val initialY = recWm.lastParams!!.y
        val boxContainer = root.getChildAt(1) as LinearLayout
        val row = boxContainer.getChildAt(1) as LinearLayout
        val pill = row.getChildAt(1)

        val initialUpdates = recWm.updateViewLayoutCount
        dispatchTouchSequence(
            target = root,
            downX = row.left + (pill.left + pill.right) / 2f,
            downY = row.top + (pill.top + pill.bottom) / 2f,
            moveRawDx = 75f,
            moveRawDy = -160f,
        )
        assertTrue("Active listening window must move when dragged", recWm.updateViewLayoutCount > initialUpdates)
        assertTrue(recWm.lastParams!!.y < initialY)
    }
}
