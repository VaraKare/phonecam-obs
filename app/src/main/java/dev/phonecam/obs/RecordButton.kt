package dev.phonecam.obs

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.Button

/**
 * Start/Stop like a phone camera's record button, with no text: a white ring around a red circle
 * that shrinks into a rounded red square while live. Screen readers hear "Start live" or "Stop live".
 */
class RecordButton(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 4 * density; color = Color.WHITE }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE53935.toInt() }
    private val shape = RectF()
    /** 0 = ready (circle), 1 = live (square). */
    private var progress = 0f
    private var animator: ValueAnimator? = null
    var live = false
        set(value) {
            if (field == value) return
            field = value
            contentDescription = if (value) "Stop live" else "Start live"
            animator?.cancel()
            animator = ValueAnimator.ofFloat(progress, if (value) 1f else 0f).apply {
                duration = 220; interpolator = DecelerateInterpolator()
                addUpdateListener { progress = it.animatedValue as Float; invalidate() }
                start()
            }
        }

    init {
        contentDescription = "Start live"; isClickable = true; isFocusable = true
    }

    override fun getAccessibilityClassName(): CharSequence = Button::class.java.name

    override fun setEnabled(enabled: Boolean) { super.setEnabled(enabled); alpha = if (enabled) 1f else 0.4f }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f; val cy = height / 2f
        val outer = minOf(width, height) / 2f - ring.strokeWidth / 2
        canvas.drawCircle(cx, cy, outer, ring)
        // Circle of radius 0.78 of the ring to a square of half-side 0.42 with small corners.
        val half = outer * (0.78f - 0.36f * progress)
        val corner = half * (1f - 0.75f * progress)
        shape.set(cx - half, cy - half, cx + half, cy + half)
        canvas.drawRoundRect(shape, corner, corner, fill)
    }
}
