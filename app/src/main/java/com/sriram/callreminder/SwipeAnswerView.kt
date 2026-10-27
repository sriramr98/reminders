package com.sriram.callreminder

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.view.*
import android.view.animation.DecelerateInterpolator

/** Touch requires a deliberate full swipe; accessibility/keyboard users can activate normally. */
class SwipeAnswerView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val phone = context.getDrawable(android.R.drawable.sym_action_call)!!.mutate().apply { setTint(Color.WHITE) }
    private var drag = 0f
    private var startX = 0f
    private var tracking = false
    private var answered = false
    private var shimmer = 0f
    private var reset: ValueAnimator? = null
    private val pulse = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1400; repeatCount = ValueAnimator.INFINITE; repeatMode = ValueAnimator.REVERSE
        addUpdateListener { shimmer = it.animatedValue as Float; invalidate() }
    }
    private val radius get() = height / 2f - 6 * density
    private val travel get() = (width - height).toFloat().coerceAtLeast(1f)
    init {
        isClickable = true; isFocusable = true
        contentDescription = "Answer meeting reminder. Swipe right to answer, or activate with accessibility."
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); pulse.start() }
    override fun onDetachedFromWindow() { pulse.cancel(); reset?.cancel(); super.onDetachedFromWindow() }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        paint.color = Color.rgb(29, 57, 53)
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), height / 2f, height / 2f, paint)
        val fade = (1 - drag / travel).coerceIn(0f, 1f)
        paint.color = Color.argb((220 * fade).toInt(), 213, 240, 229)
        paint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        paint.textSize = 16 * resources.displayMetrics.scaledDensity
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText("slide to answer", width / 2f + 13 * density, height / 2f - (paint.ascent() + paint.descent()) / 2, paint)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 2.2f * density; paint.strokeCap = Paint.Cap.ROUND
        paint.color = Color.argb(((95 + shimmer * 140) * fade).toInt(), 153, 225, 185)
        val x = width - 29 * density
        val y = height / 2f
        canvas.drawLine(x - 5 * density, y - 6 * density, x + density, y, paint)
        canvas.drawLine(x + density, y, x - 5 * density, y + 6 * density, paint)
        paint.style = Paint.Style.FILL
        val center = height / 2f + drag
        paint.color = Color.rgb(56, 193, 118)
        canvas.drawCircle(center, height / 2f, radius, paint)
        val icon = (13 * density).toInt()
        phone.setBounds(center.toInt() - icon, height / 2 - icon, center.toInt() + icon, height / 2 + icon)
        phone.draw(canvas)
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled || answered) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (event.x > height || event.y !in 0f..height.toFloat()) return false
                reset?.cancel(); startX = event.x; tracking = true
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> if (tracking) {
                drag = (event.x - startX).coerceIn(0f, travel); invalidate(); return true
            }
            MotionEvent.ACTION_UP -> if (tracking) {
                tracking = false
                parent?.requestDisallowInterceptTouchEvent(false)
                if (drag >= travel * .85f) performClick() else returnToStart()
                return true
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> {
                tracking = false; parent?.requestDisallowInterceptTouchEvent(false); returnToStart(); return true
            }
        }
        return tracking
    }
    private fun returnToStart() {
        reset = ValueAnimator.ofFloat(drag, 0f).apply {
            duration = 220; interpolator = DecelerateInterpolator()
            addUpdateListener { drag = it.animatedValue as Float; invalidate() }; start()
        }
    }
    override fun performClick(): Boolean {
        if (answered || !isEnabled) return false
        answered = true; drag = travel; invalidate()
        performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        return super.performClick()
    }
}
