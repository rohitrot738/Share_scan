package com.rohit.share_scan

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import androidx.core.view.isInvisible
import androidx.customview.widget.ViewDragHelper
import kotlin.math.abs

/**
 * A card that slides to the left to show the buttons behind it: its first child is the row of buttons (aligned to
 * the end), its second the card. A drag past half the buttons' width, or a fling, leaves it open; anything less lets
 * it spring back. Only the strip the card has uncovered draws the buttons, so a see-through card never shows them.
 */
class SwipeRevealLayout @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : FrameLayout(context, attrs) {
    private lateinit var actions: View
    private lateinit var card: View
    private val minFling = 400 * resources.displayMetrics.density

    /** Called when this one opens, so a list can close the one open before. */
    var onOpened: ((SwipeRevealLayout) -> Unit)? = null

    private val drag = ViewDragHelper.create(this, 1f, object : ViewDragHelper.Callback() {
        override fun tryCaptureView(child: View, pointerId: Int) = child === card

        override fun getViewHorizontalDragRange(child: View) = actions.width

        override fun clampViewPositionHorizontal(child: View, left: Int, dx: Int) = left.coerceIn(-actions.width, 0)

        override fun clampViewPositionVertical(child: View, top: Int, dy: Int) = card.top

        override fun onViewCaptured(capturedChild: View, activePointerId: Int) {
            // The list must not take the gesture for a scroll once the card moves sideways.
            parent?.requestDisallowInterceptTouchEvent(true)
        }

        override fun onViewPositionChanged(changedView: View, left: Int, top: Int, dx: Int, dy: Int) {
            actions.isInvisible = left >= 0
            invalidate()
        }

        override fun onViewReleased(releasedChild: View, xvel: Float, yvel: Float) {
            val open = xvel < -minFling || (xvel <= minFling && card.left < -actions.width / 2)
            drag.settleCapturedViewAt(if (open) -actions.width else 0, card.top)
            if (open) onOpened?.invoke(this@SwipeRevealLayout)
            invalidate()
        }
    })

    /** Open: the card is slid aside and its buttons show. */
    val isOpen: Boolean get() = ::card.isInitialized && card.left < 0

    override fun onFinishInflate() {
        super.onFinishInflate()
        actions = getChildAt(0)
        card = getChildAt(1)
        actions.isInvisible = true
    }

    /** Slides the card back over its buttons; at once when [animate] is false, as a list does on rebinding a row. */
    fun close(animate: Boolean = true) {
        if (!::card.isInitialized || card.left == 0) return
        if (animate && drag.smoothSlideViewTo(card, 0, card.top)) {
            ViewCompat.postInvalidateOnAnimation(this)
        } else {
            drag.abort()
            card.offsetLeftAndRight(-card.left)
            actions.isInvisible = true
            invalidate()
        }
    }

    private var downX = 0f

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        // An open card takes every touch on it: a drag moves it, a tap closes it rather than opening the stock.
        if (ev.actionMasked == MotionEvent.ACTION_DOWN && isOpen && ev.x < card.right) return true
        return drag.shouldInterceptTouchEvent(ev)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) downX = event.x
        drag.processTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP && isOpen && abs(event.x - downX) < drag.touchSlop) close()
        return true
    }

    override fun computeScroll() {
        if (drag.continueSettling(true)) ViewCompat.postInvalidateOnAnimation(this)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        // A layout pass puts the card back at its start; an open card stays where it was.
        val offset = if (::card.isInitialized) card.left else 0
        super.onLayout(changed, left, top, right, bottom)
        if (offset != 0) card.offsetLeftAndRight(offset)
    }

    override fun drawChild(canvas: Canvas, child: View, drawingTime: Long): Boolean {
        if (child !== actions) return super.drawChild(canvas, child, drawingTime)
        val saved = canvas.save()
        canvas.clipRect(card.right, 0, width, height)
        val drawn = super.drawChild(canvas, child, drawingTime)
        canvas.restoreToCount(saved)
        return drawn
    }
}
