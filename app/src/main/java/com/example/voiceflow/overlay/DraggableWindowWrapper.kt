package com.example.voiceflow.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.LinearLayout

/**
 * Wraps the dictation UI inside a single View tree and lets the user freely drag and drop
 * the floating window anywhere on screen (via TOP|START LayoutParams x/y) by grabbing the
 * drag handle, preview area, row background, or sliding on the pill — in IDLE and LISTENING —
 * while keeping X/Check button taps crisp and accurate.
 */
@SuppressLint("ViewConstructor")
class DraggableWindowWrapper(
    context: Context,
    private val canDrag: () -> Boolean,
    private val isProtectedActionView: (rawX: Float, rawY: Float) -> Boolean = { _, _ -> false },
    private val currentOffsets: () -> Pair<Int, Int>,
    private val onDragBy: (desiredX: Int, desiredY: Int) -> Unit,
    private val onDragFinished: (finalX: Int, finalY: Int) -> Unit,
) : LinearLayout(context) {

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downRawX = 0f
    private var downRawY = 0f
    private var startX = 0
    private var startY = 0
    private var isDragging = false
    private var protectedTouch = false
    private val screenLoc = IntArray(2)

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        clipChildren = false
        clipToPadding = false
        isSoundEffectsEnabled = false
    }

    internal fun isPointInChildCircle(child: View?, wrapperX: Float, wrapperY: Float): Boolean {
        if (child == null || child.visibility != View.VISIBLE || child.width <= 0 || child.height <= 0) return false
        var left = child.left.toFloat()
        var top = child.top.toFloat()
        var currentParent = child.parent
        while (currentParent is View && currentParent !== this) {
            left += currentParent.left - currentParent.scrollX
            top += currentParent.top - currentParent.scrollY
            currentParent = currentParent.parent
        }
        val cx = left + child.width / 2f
        val cy = top + child.height / 2f
        val r = minOf(child.width, child.height) / 2f
        val dx = wrapperX - cx
        val dy = wrapperY - cy
        return dx * dx + dy * dy <= r * r
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (!canDrag()) return false

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                protectedTouch = isProtectedActionView(ev.x, ev.y)
                downRawX = ev.rawX
                downRawY = ev.rawY
                val (curX, curY) = currentOffsets()
                startX = curX
                startY = curY
                isDragging = false
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                if (protectedTouch) return false
                val dx = ev.rawX - downRawX
                val dy = ev.rawY - downRawY
                if (dx * dx + dy * dy >= touchSlop * touchSlop) {
                    isDragging = true
                    setDragVisualActive(true)
                    return true
                }
                return false
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL,
            -> {
                protectedTouch = false
                isDragging = false
                setDragVisualActive(false)
                return false
            }
        }
        return false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean = handleDragMotionEvent(event)

    internal fun handleDragMotionEvent(event: MotionEvent): Boolean {
        if (!canDrag()) {
            isDragging = false
            setDragVisualActive(false)
            return true
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                protectedTouch = isProtectedActionView(event.x, event.y)
                downRawX = event.rawX
                downRawY = event.rawY
                val (curX, curY) = currentOffsets()
                startX = curX
                startY = curY
                isDragging = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (protectedTouch) return true
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                if (!isDragging && (dx * dx + dy * dy >= touchSlop * touchSlop)) {
                    isDragging = true
                    setDragVisualActive(true)
                }
                if (isDragging) {
                    val desiredX = startX + dx.toInt()
                    val desiredY = startY + dy.toInt()
                    onDragBy(desiredX, desiredY)
                }
                return true
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL,
            -> {
                if (isDragging) {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    val finalX = startX + dx.toInt()
                    val finalY = startY + dy.toInt()
                    onDragFinished(finalX, finalY)
                }
                isDragging = false
                protectedTouch = false
                setDragVisualActive(false)
                return true
            }
        }
        return true
    }

    private fun setDragVisualActive(active: Boolean) {
        val targetScale = if (active) 1.03f else 1f
        if (scaleX != targetScale) {
            scaleX = targetScale
            scaleY = targetScale
        }
    }
}
