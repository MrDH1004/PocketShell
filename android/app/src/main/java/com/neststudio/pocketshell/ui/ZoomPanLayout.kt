package com.neststudio.pocketshell.ui

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * Gallery-style zoom for the terminal: pinch scales the whole picture (1x..[MAX_SCALE]) around the fingers, and
 * while zoomed in one finger drags it around. At 1x touches go to the child unchanged (taps, scrolling, selection).
 * The child's grid and font are untouched, so the PC console keeps its size.
 */
class ZoomPanLayout @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : FrameLayout(context, attrs) {

    var scale = 1f
        private set
    private var tx = 0f
    private var ty = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var downX = 0f
    private var downY = 0f
    private var panning = false
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    /** Called after the zoom level changes (e.g. to show it). */
    var onZoomChanged: ((Float) -> Unit)? = null

    /**
     * Vertical drag beyond the zoomed picture's edge, in child pixels; positive = finger moving up (like
     * GestureDetector's distanceY), i.e. towards newer lines.
     */
    var onOverscrollY: ((Float) -> Unit)? = null

    private val detector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            val next = (scale * d.scaleFactor).coerceIn(1f, MAX_SCALE)
            if (next == scale) return true
            // Keep the content point under the fingers where it is.
            val f = next / scale
            tx = d.focusX - (d.focusX - tx) * f
            ty = d.focusY - (d.focusY - ty) * f
            scale = next
            apply()
            onZoomChanged?.invoke(scale)
            return true
        }
    }).apply { isQuickScaleEnabled = false }

    private val child: View? get() = if (childCount > 0) getChildAt(0) else null

    fun reset() {
        scale = 1f
        tx = 0f
        ty = 0f
        apply()
        onZoomChanged?.invoke(scale)
    }

    /** Zooms to [s] showing the bottom-left part (where the prompt usually is). */
    fun zoomTo(s: Float) {
        scale = s.coerceIn(1f, MAX_SCALE)
        tx = 0f
        ty = height - height * scale
        apply()
        onZoomChanged?.invoke(scale)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x; downY = ev.y; lastX = ev.x; lastY = ev.y
                panning = false
                detector.onTouchEvent(ev)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                detector.onTouchEvent(ev)
                return true // two fingers: zoom, the child gets ACTION_CANCEL
            }
            MotionEvent.ACTION_MOVE -> {
                if (scale > 1f && (abs(ev.x - downX) > slop || abs(ev.y - downY) > slop)) {
                    panning = true
                    lastX = ev.x; lastY = ev.y
                    return true
                }
            }
        }
        return false
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        detector.onTouchEvent(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_POINTER_UP -> {
                // Continue panning with the finger that stays down.
                val keep = if (ev.actionIndex == 0) 1 else 0
                lastX = ev.getX(keep); lastY = ev.getY(keep)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!detector.isInProgress && ev.pointerCount == 1 && scale > 1f) {
                    tx += ev.x - lastX
                    val wanted = ty + (ev.y - lastY)
                    ty = wanted
                    apply() // clamps ty to the picture's edges
                    // Past the top/bottom edge the drag scrolls the terminal (history), in content pixels.
                    val over = wanted - ty
                    if (over != 0f) onOverscrollY?.invoke(-over / scale)
                }
                lastX = ev.x; lastY = ev.y
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> panning = false
        }
        return true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        apply()
    }

    /** Clamps so the zoomed picture always covers the view, then applies it to the child. */
    private fun apply() {
        val c = child ?: return
        val w = width.toFloat()
        val h = height.toFloat()
        tx = tx.coerceIn(w - w * scale, 0f)
        ty = ty.coerceIn(h - h * scale, 0f)
        c.pivotX = 0f
        c.pivotY = 0f
        c.scaleX = scale
        c.scaleY = scale
        c.translationX = tx
        c.translationY = ty
    }

    companion object {
        const val MAX_SCALE = 5f
    }
}
