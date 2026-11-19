package com.example.voiceflow.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.example.R
import com.example.voiceflow.core.DictationState
import com.example.voiceflow.core.OverlayPositionCalculator
import com.example.voiceflow.core.RectSnapshot

class OverlayController(
    private val context: Context,
    private val windowManager: WindowManager,
    private val onCancelTapped: () -> Unit,
    private val onCenterPillTapped: () -> Unit,
    private val onCheckTapped: () -> Unit,
    private val positionStore: OverlayPositionStore? = null,
    private val imeRectProvider: () -> RectSnapshot? = { null },
) {
    private val density = context.resources.displayMetrics.density
    private var rootView: DraggableWindowWrapper? = null

    // Aşama 1 - Küçük yuvarlak logo (kapalı durum)
    private var logoButton: CircleIconButton? = null

    // Aşama 2 - Dikte kutusu (açık durum)
    private var boxContainer: LinearLayout? = null
    private var previewContainer: LinearLayout? = null
    private var previewScrollView: ScrollView? = null
    private var previewTextView: TextView? = null
    private var waveformPill: WaveformPillView? = null
    private var cancelButton: CircleIconButton? = null
    private var confirmButton: CircleIconButton? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    var isBoxExpanded: Boolean = false
        private set

    val isShowing: Boolean
        get() = rootView != null

    fun show(
        imeRect: RectSnapshot?,
        fieldRect: RectSnapshot?,
        state: DictationState,
    ) {
        if (rootView != null) {
            renderState(state)
            return
        }

        // Bir yazı alanı odaklandığında kontrol yerine küçük, yuvarlak bir logo ile başlar (Aşama 1).
        isBoxExpanded = false

        val logoSize = dp(LOGO_SIZE_DP)
        val saved = positionStore?.getPosition()
        val (initialX, initialY) = if (saved != null) {
            clampTopLeft(saved.x, saved.y, logoSize, logoSize)
        } else {
            computeDefaultTopLeft(imeRect, fieldRect, logoSize, logoSize)
        }

        val builtRoot = buildViewTree()
        val lp = createLayoutParams(initialX, initialY)

        try {
            windowManager.addView(builtRoot, lp)
            rootView = builtRoot
            layoutParams = lp
            renderState(state)
        } catch (_: Exception) {
            rootView = null
            layoutParams = null
        }
    }

    fun expandBox(animate: Boolean = true) {
        val root = rootView ?: return
        val lp = layoutParams ?: return
        if (isBoxExpanded) return

        isBoxExpanded = true
        val logoSize = dp(LOGO_SIZE_DP)
        val boxW = dp(BOX_WIDTH_DP)
        val boxH = dp(BOX_HEIGHT_DP)

        // Kutuyu logonun bulunduğu mevcut merkez noktadan aç
        val currentCenterX = lp.x + logoSize / 2
        val currentCenterY = lp.y + logoSize / 2

        val targetBoxX = currentCenterX - boxW / 2
        val targetBoxY = currentCenterY - boxH / 2
        val (clampedX, clampedY) = clampTopLeft(targetBoxX, targetBoxY, boxW, boxH)

        lp.x = clampedX
        lp.y = clampedY

        logoButton?.visibility = View.GONE
        val box = boxContainer ?: return
        box.visibility = View.VISIBLE

        try {
            windowManager.updateViewLayout(root, lp)
        } catch (_: Exception) {
            // Ignore if concurrently detached.
        }

        positionStore?.savePosition(clampedX, clampedY)

        if (animate && root.isAttachedToWindow) {
            box.alpha = 0f
            box.scaleX = 0.85f
            box.scaleY = 0.85f
            box.pivotX = boxW / 2f
            box.pivotY = boxH / 2f
            box.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(EXPAND_ANIM_MS)
                .setInterpolator(AccelerateDecelerateInterpolator())
                .start()
        } else {
            box.alpha = 1f
            box.scaleX = 1f
            box.scaleY = 1f
        }
    }

    fun collapseToLogo(animate: Boolean = true) {
        val root = rootView ?: return
        val lp = layoutParams ?: return
        if (!isBoxExpanded) return

        isBoxExpanded = false
        val boxW = root.width.takeIf { it > 0 } ?: dp(BOX_WIDTH_DP)
        val boxH = root.height.takeIf { it > 0 } ?: dp(BOX_HEIGHT_DP)
        val logoSize = dp(LOGO_SIZE_DP)

        // Logoyu kutunun bulunduğu mevcut merkez noktaya geri konumlandır
        val currentCenterX = lp.x + boxW / 2
        val currentCenterY = lp.y + boxH / 2

        val targetLogoX = currentCenterX - logoSize / 2
        val targetLogoY = currentCenterY - logoSize / 2
        val (clampedX, clampedY) = clampTopLeft(targetLogoX, targetLogoY, logoSize, logoSize)

        lp.x = clampedX
        lp.y = clampedY

        val box = boxContainer
        val logo = logoButton

        val finishCollapse = {
            box?.visibility = View.GONE
            logo?.visibility = View.VISIBLE
            logo?.alpha = 1f
            logo?.scaleX = 1f
            logo?.scaleY = 1f
            try {
                windowManager.updateViewLayout(root, lp)
            } catch (_: Exception) {
                // Ignore if detached.
            }
            positionStore?.savePosition(clampedX, clampedY)
        }

        if (animate && root.isAttachedToWindow && box != null && logo != null) {
            box.animate()
                .alpha(0f)
                .scaleX(0.85f)
                .scaleY(0.85f)
                .setDuration(COLLAPSE_ANIM_MS)
                .setInterpolator(AccelerateDecelerateInterpolator())
                .withEndAction {
                    finishCollapse()
                }
                .start()
        } else {
            finishCollapse()
        }
    }

    fun renderState(state: DictationState) {
        val tv = previewTextView ?: return
        val pill = waveformPill ?: return
        val scroll = previewScrollView ?: return

        when (state) {
            is DictationState.Idle -> {
                pill.setMode(listening = false, rmsDb = 0f, idleDragAllowed = true)
                tv.text = state.noticeMessage?.takeIf { it.isNotBlank() } ?: HINT_IDLE
                cancelButton?.isEnabled = true
                confirmButton?.isEnabled = true
            }
            is DictationState.Listening -> {
                if (!isBoxExpanded) {
                    expandBox(animate = false)
                }
                pill.setMode(listening = true, rmsDb = state.rmsDb, idleDragAllowed = false)
                tv.text = state.previewText.ifBlank { HINT_LISTENING }
                cancelButton?.isEnabled = true
                confirmButton?.isEnabled = true
            }
            is DictationState.Processing -> {
                if (!isBoxExpanded) {
                    expandBox(animate = false)
                }
                pill.setMode(listening = false, rmsDb = 0f, idleDragAllowed = false)
                tv.text = if (state.previewText.isNotBlank()) {
                    "${state.statusMessage}\n\n${state.previewText}"
                } else {
                    state.statusMessage
                }
                cancelButton?.isEnabled = true
                confirmButton?.isEnabled = false
            }
            is DictationState.Inserting -> {
                if (!isBoxExpanded) {
                    expandBox(animate = false)
                }
                pill.setMode(listening = false, rmsDb = 0f, idleDragAllowed = false)
                tv.text = state.finalText
                cancelButton?.isEnabled = false
                confirmButton?.isEnabled = false
            }
            is DictationState.Done -> {
                pill.setMode(listening = false, rmsDb = 0f, idleDragAllowed = false)
                tv.text = state.message
                cancelButton?.isEnabled = true
                confirmButton?.isEnabled = true
                // Metin onaylanıp eklendikten sonra kutu kapanır ve logo durumuna dönülür.
                rootView?.postDelayed({
                    if (isShowing && isBoxExpanded) {
                        collapseToLogo(animate = true)
                    }
                }, DONE_COLLAPSE_DELAY_MS)
            }
        }

        scroll.post {
            scroll.fullScroll(View.FOCUS_DOWN)
        }
    }

    fun hide() {
        val view = rootView ?: return
        rootView = null
        logoButton = null
        boxContainer = null
        previewContainer = null
        previewScrollView = null
        previewTextView = null
        waveformPill = null
        cancelButton = null
        confirmButton = null
        layoutParams = null
        isBoxExpanded = false
        try {
            windowManager.removeViewImmediate(view)
        } catch (_: Exception) {
            try {
                windowManager.removeView(view)
            } catch (_: Exception) {
                // Ignore if already removed.
            }
        }
    }

    private fun buildViewTree(): DraggableWindowWrapper {
        lateinit var wrapperRef: DraggableWindowWrapper
        val root = DraggableWindowWrapper(
            context = context,
            canDrag = { rootView != null },
            isProtectedActionView = { x, y ->
                if (!isBoxExpanded) {
                    false
                } else {
                    wrapperRef.isPointInChildCircle(cancelButton, x, y) ||
                        wrapperRef.isPointInChildCircle(confirmButton, x, y)
                }
            },
            currentOffsets = {
                val lp = layoutParams
                (lp?.x ?: 0) to (lp?.y ?: 0)
            },
            onDragBy = { desiredX, desiredY ->
                moveWindowClamped(desiredX, desiredY, persist = false)
            },
            onDragFinished = { finalX, finalY ->
                moveWindowClamped(finalX, finalY, persist = true)
            },
        ).also { wrapperRef = it }

        // Aşama 1: Küçük yuvarlak logo (46dp çapında, VoiceFlow moru, dalga ikonu)
        val logoSize = dp(LOGO_SIZE_DP)
        val logoBtn = CircleIconButton(
            context = context,
            fillColor = COLOR_PURPLE_CHECK,
            rippleColor = COLOR_RIPPLE_LIGHT,
            iconRes = R.drawable.ic_vf_logo,
            description = "VoiceFlow dikte kontrolünü aç",
        ) {
            expandBox(animate = true)
        }.apply {
            elevation = dp(4).toFloat()
            isSoundEffectsEnabled = false
            visibility = if (isBoxExpanded) View.GONE else View.VISIBLE
        }
        logoButton = logoBtn

        // Aşama 2: Dikte kutusu (Açık durum, üstte metin önizleme, altta ✕ | dalga | ✓ satırı)
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            clipChildren = false
            clipToPadding = false
            isSoundEffectsEnabled = false
            visibility = if (isBoxExpanded) View.VISIBLE else View.GONE
        }

        val dragHandle = View(context).apply {
            isSoundEffectsEnabled = false
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(3).toFloat()
                setColor(Color.parseColor("#88D4CFE0"))
            }
        }

        val textView = TextView(context).apply {
            setTextColor(Color.parseColor("#F5F2FA"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            setLineSpacing(dp(2).toFloat(), 1f)
            isSoundEffectsEnabled = false
            text = HINT_IDLE
        }

        val scrollView = object : ScrollView(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val maxH = MeasureSpec.makeMeasureSpec(dp(84), MeasureSpec.AT_MOST)
                super.onMeasure(widthMeasureSpec, maxH)
            }
        }.apply {
            isVerticalScrollBarEnabled = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            isSoundEffectsEnabled = false
            addView(
                textView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }

        val previewBox = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            isSoundEffectsEnabled = false
            elevation = dp(6).toFloat()
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(18).toFloat()
                setColor(Color.parseColor("#EC1A1822"))
                setStroke(dp(1), Color.parseColor("#38FFFFFF"))
            }
            setPadding(dp(14), dp(6), dp(14), dp(8))
            addView(
                dragHandle,
                LinearLayout.LayoutParams(dp(38), dp(4)).apply {
                    bottomMargin = dp(5)
                },
            )
            addView(
                scrollView,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }

        val previewLp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            bottomMargin = dp(6)
            gravity = Gravity.CENTER_HORIZONTAL
        }
        textView.maxWidth = dp(276)
        box.addView(previewBox, previewLp)

        // TEK satır: solda ✕ (iptal), ortada dalga kapsülü (dinleme), sağda ✓ (onay)
        val row = object : LinearLayout(context) {
            override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = false

            override fun onTouchEvent(event: MotionEvent): Boolean {
                return wrapperRef.handleDragMotionEvent(event)
            }
        }.apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = null
            clipChildren = false
            clipToPadding = false
            isSoundEffectsEnabled = false
        }

        val btnSize = dp(48)
        val gap = dp(6)

        val closeBtn = CircleIconButton(
            context = context,
            fillColor = COLOR_GRAY_BUTTON,
            rippleColor = COLOR_RIPPLE_DARK,
            iconRes = R.drawable.ic_vf_close,
            description = "Dikteyi iptal et",
        ) {
            onCancelTapped()
            collapseToLogo(animate = true)
        }

        val pill = WaveformPillView(context) {
            onCenterPillTapped()
        }.apply {
            isSoundEffectsEnabled = false
        }

        val checkBtn = CircleIconButton(
            context = context,
            fillColor = COLOR_PURPLE_CHECK,
            rippleColor = COLOR_RIPPLE_LIGHT,
            iconRes = R.drawable.ic_vf_check,
            description = "Metni onayla ve ekle",
        ) {
            onCheckTapped()
        }

        row.addView(closeBtn, LinearLayout.LayoutParams(btnSize, btnSize))
        row.addView(
            pill,
            LinearLayout.LayoutParams(dp(132), btnSize).apply {
                marginStart = gap
                marginEnd = gap
            },
        )
        row.addView(checkBtn, LinearLayout.LayoutParams(btnSize, btnSize))

        box.addView(
            row,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                btnSize,
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            },
        )

        // Tek bir kapsayıcı (root) içine hem Logo hem Kutu yerleştirilir (aynı WindowManager penceresi)
        root.addView(logoBtn, LinearLayout.LayoutParams(logoSize, logoSize))
        root.addView(
            box,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )

        boxContainer = box
        previewContainer = previewBox
        previewScrollView = scrollView
        previewTextView = textView
        waveformPill = pill
        cancelButton = closeBtn
        confirmButton = checkBtn
        return root
    }

    private fun moveWindowClamped(desiredX: Int, desiredY: Int, persist: Boolean) {
        val view = rootView ?: return
        val lp = layoutParams ?: return

        val w = if (isBoxExpanded) dp(BOX_WIDTH_DP) else dp(LOGO_SIZE_DP)
        val h = if (isBoxExpanded) dp(BOX_HEIGHT_DP) else dp(LOGO_SIZE_DP)
        val (clampedX, clampedY) = clampTopLeft(desiredX, desiredY, w, h)
        if (lp.x != clampedX || lp.y != clampedY) {
            lp.x = clampedX
            lp.y = clampedY
            try {
                windowManager.updateViewLayout(view, lp)
            } catch (_: Exception) {
                // Ignore if view was concurrently detached.
            }
        }
        if (persist) {
            positionStore?.savePosition(clampedX, clampedY)
        }
    }

    private fun clampTopLeft(
        desiredX: Int,
        desiredY: Int,
        overlayWidthPx: Int = if (isBoxExpanded) dp(BOX_WIDTH_DP) else dp(LOGO_SIZE_DP),
        overlayHeightPx: Int = if (isBoxExpanded) dp(BOX_HEIGHT_DP) else dp(LOGO_SIZE_DP),
    ): Pair<Int, Int> {
        val metrics = readScreenMetrics()
        return OverlayPositionCalculator.clampWindowTopLeft(
            desiredX = desiredX,
            desiredY = desiredY,
            screenWidthPx = metrics.screenWidthPx,
            screenHeightPx = metrics.screenHeightPx,
            overlayWidthPx = overlayWidthPx,
            overlayHeightPx = overlayHeightPx,
            navBarBottomInsetPx = metrics.navBarBottomInsetPx,
            statusBarTopInsetPx = metrics.statusBarTopInsetPx,
            edgeMarginPx = dp(8),
        )
    }

    private fun computeDefaultTopLeft(
        imeRect: RectSnapshot?,
        fieldRect: RectSnapshot?,
        overlayWidthPx: Int = if (isBoxExpanded) dp(BOX_WIDTH_DP) else dp(LOGO_SIZE_DP),
        overlayHeightPx: Int = if (isBoxExpanded) dp(BOX_HEIGHT_DP) else dp(LOGO_SIZE_DP),
    ): Pair<Int, Int> {
        val metrics = readScreenMetrics()
        return OverlayPositionCalculator.computeDefaultTopLeftPx(
            screenWidthPx = metrics.screenWidthPx,
            screenHeightPx = metrics.screenHeightPx,
            overlayWidthPx = overlayWidthPx,
            overlayHeightPx = overlayHeightPx,
            navBarBottomInsetPx = metrics.navBarBottomInsetPx,
            statusBarTopInsetPx = metrics.statusBarTopInsetPx,
            imeRect = imeRect ?: imeRectProvider(),
            fieldRect = fieldRect,
            gapPx = dp(16),
            compactFieldMaxHeightPx = dp(180),
            minTopClearancePx = dp(150),
            edgeMarginPx = dp(8),
        )
    }

    private fun createLayoutParams(topLeftX: Int, topLeftY: Int): WindowManager.LayoutParams {
        val type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN

        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            flags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = topLeftX
            y = topLeftY
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                fitInsetsTypes = 0
            }
        }
    }

    private data class ScreenMetrics(
        val screenWidthPx: Int,
        val screenHeightPx: Int,
        val navBarBottomInsetPx: Int,
        val statusBarTopInsetPx: Int,
    )

    private fun readScreenMetrics(): ScreenMetrics {
        var screenWidthPx = context.resources.displayMetrics.widthPixels
        var screenHeightPx = context.resources.displayMetrics.heightPixels
        val navResId = context.resources.getIdentifier("navigation_bar_height", "dimen", "android")
        var navBarBottomInsetPx = if (navResId > 0) context.resources.getDimensionPixelSize(navResId) else dp(48)
        val statusResId = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        var statusBarTopInsetPx = if (statusResId > 0) context.resources.getDimensionPixelSize(statusResId) else dp(24)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val metrics = windowManager.maximumWindowMetrics
                screenWidthPx = metrics.bounds.width()
                screenHeightPx = metrics.bounds.height()
                val navInsets = metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.navigationBars())
                navBarBottomInsetPx = navInsets.bottom
                val statusInsets = metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars())
                statusBarTopInsetPx = statusInsets.top
            } catch (_: UnsupportedOperationException) {
                // Fall back to DisplayMetrics on non-visual or test WindowManager wrappers.
            }
        } else {
            @Suppress("DEPRECATION")
            try {
                val realMetrics = android.util.DisplayMetrics()
                windowManager.defaultDisplay?.getRealMetrics(realMetrics)
                if (realMetrics.widthPixels > 0) screenWidthPx = realMetrics.widthPixels
                if (realMetrics.heightPixels > 0) screenHeightPx = realMetrics.heightPixels
            } catch (_: Exception) {
                // Keep resources.displayMetrics fallback.
            }
        }

        return ScreenMetrics(
            screenWidthPx = screenWidthPx,
            screenHeightPx = screenHeightPx,
            navBarBottomInsetPx = navBarBottomInsetPx,
            statusBarTopInsetPx = statusBarTopInsetPx,
        )
    }

    private fun dp(value: Int): Int = (value * density + 0.5f).toInt()

    @SuppressLint("ViewConstructor")
    private class CircleIconButton(
        context: Context,
        fillColor: Int,
        rippleColor: Int,
        iconRes: Int,
        description: String,
        private val onClick: () -> Unit,
    ) : ImageButton(context) {

        private var downInsideCircle = false

        init {
            scaleType = ImageView.ScaleType.CENTER
            contentDescription = description
            isClickable = true
            isFocusable = false
            isSoundEffectsEnabled = false
            setImageDrawable(ContextCompat.getDrawable(context, iconRes))

            val circleShape = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(fillColor)
            }
            background = RippleDrawable(
                ColorStateList.valueOf(rippleColor),
                circleShape,
                circleShape,
            )

            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setOval(0, 0, view.width, view.height)
                }
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (!isEnabled) return true
            val cx = width / 2f
            val cy = height / 2f
            val r = minOf(width, height) / 2f
            val dx = event.x - cx
            val dy = event.y - cy
            val inside = dx * dx + dy * dy <= r * r

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downInsideCircle = inside
                    if (!inside) return false
                    return super.onTouchEvent(event)
                }
                MotionEvent.ACTION_UP -> {
                    val trigger = downInsideCircle && inside
                    downInsideCircle = false
                    if (trigger) {
                        isPressed = false
                        onClick()
                        return true
                    }
                    isPressed = false
                    return false
                }
                MotionEvent.ACTION_CANCEL -> {
                    downInsideCircle = false
                    return super.onTouchEvent(event)
                }
            }
            return super.onTouchEvent(event)
        }

        override fun performClick(): Boolean {
            super.performClick()
            onClick()
            return true
        }
    }

    companion object {
        const val LOGO_SIZE_DP = 46
        const val BOX_WIDTH_DP = 240
        const val BOX_HEIGHT_DP = 104
        const val EXPAND_ANIM_MS = 180L
        const val COLLAPSE_ANIM_MS = 150L
        const val DONE_COLLAPSE_DELAY_MS = 320L

        private const val COLOR_GRAY_BUTTON = 0xFFB8B4C4.toInt()
        private const val COLOR_PURPLE_CHECK = 0xFF5C2D82.toInt()
        private const val COLOR_RIPPLE_DARK = 0x331C1B1F
        private const val COLOR_RIPPLE_LIGHT = 0x44FFFFFF

        const val HINT_IDLE = "Dikte için dokunun"
        const val HINT_LISTENING = "Dinleniyor..."
        const val HINT_PROCESSING = "Tamamlanıyor..."
    }
}
