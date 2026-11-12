package app.kiwixreader.ui.books

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Bundle
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.graphics.withSave
import kotlin.math.abs

/**
 * Draws one page: lines [firstLine]..[lastLine] of a section's StaticLayout, plus a footer with the chapter
 * title and progress. Tapping the left/right edge or swiping turns pages; tapping the middle toggles chrome.
 */
class PageView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    interface Listener {
        fun onNextPage()
        fun onPreviousPage()
        fun onCenterTap()
        fun onSizeChanged()
    }

    var listener: Listener? = null

    private var layout: StaticLayout? = null
    private var firstLine = 0
    private var lastLine = -1
    private var footerStart = ""
    private var footerEnd = ""
    private var pageColor = 0

    val footerHeight = dp(28f).toInt()
    private val footerPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 12f, resources.displayMetrics)
    }

    val contentWidth get() = (width - paddingLeft - paddingRight).coerceAtLeast(1)
    val contentHeight get() = (height - paddingTop - paddingBottom - footerHeight).coerceAtLeast(1)

    fun setColors(background: Int, footer: Int) {
        pageColor = background
        footerPaint.color = footer
        invalidate()
    }

    fun show(layout: StaticLayout?, firstLine: Int, lastLine: Int, footerStart: String, footerEnd: String) {
        this.layout = layout
        this.firstLine = firstLine
        this.lastLine = lastLine
        this.footerStart = footerStart
        this.footerEnd = footerEnd
        contentDescription = layout?.let {
            if (lastLine >= firstLine) it.text.subSequence(it.getLineStart(firstLine), it.getLineEnd(lastLine)).toString() else ""
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(pageColor)
        val page = layout
        if (page != null && lastLine >= firstLine) {
            val top = page.getLineTop(firstLine)
            val bottom = page.getLineBottom(lastLine)
            canvas.withSave {
                translate(paddingLeft.toFloat(), (paddingTop - top).toFloat())
                clipRect(0, top, page.width, bottom)
                page.draw(this)
            }
        }
        val baseline = height - paddingBottom - footerHeight / 2f + footerPaint.textSize / 3f
        val endWidth = footerPaint.measureText(footerEnd)
        canvas.drawText(footerEnd, width - paddingRight - endWidth, baseline, footerPaint)
        val available = (contentWidth - endWidth - dp(16f)).coerceAtLeast(0f)
        val start = TextUtils.ellipsize(footerStart, footerPaint, available, TextUtils.TruncateAt.END).toString()
        canvas.drawText(start, paddingLeft.toFloat(), baseline, footerPaint)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (w != oldw || h != oldh) listener?.onSizeChanged()
    }

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            when {
                e.x < width * EDGE -> listener?.onPreviousPage()
                e.x > width * (1 - EDGE) -> listener?.onNextPage()
                else -> listener?.onCenterTap()
            }
            performClick()
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            if (abs(velocityX) < abs(velocityY) || abs(velocityX) < dp(300f)) return false
            if (velocityX < 0) listener?.onNextPage() else listener?.onPreviousPage()
            return true
        }
    })

    @Suppress("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean = gestures.onTouchEvent(event)

    override fun performClick(): Boolean = super.performClick()

    // TalkBack: expose page turns as scroll actions.
    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
        info.isScrollable = true
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean = when (action) {
        AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> {
            listener?.onNextPage()
            true
        }
        AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> {
            listener?.onPreviousPage()
            true
        }
        else -> super.performAccessibilityAction(action, arguments)
    }

    private fun dp(value: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)

    companion object {
        private const val EDGE = 0.3f
    }
}
