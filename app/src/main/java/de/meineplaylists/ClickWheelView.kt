package de.meineplaylists

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min

/**
 * Klickrad: Drehen = scrollen (oder Lautstärke), Tippen oben = MENU, links/rechts = zurück/weiter,
 * unten = Play/Pause, Mitte = Auswählen.
 */
class ClickWheelView(ctx: Context, attrs: AttributeSet?) : View(ctx, attrs) {

    interface Listener {
        fun onWheelScroll(steps: Int)
        fun onMenu()
        fun onPrev()
        fun onNext()
        fun onPlayPause()
        fun onSelect()
    }

    var listener: Listener? = null

    private val dp = resources.displayMetrics.density
    private val wheelPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.2f * dp; color = 0x30FFFFFF
    }
    private val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val centerRim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.5f * dp; color = 0x55000000
    }
    private val pressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0x18FFFFFF }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFE8E8EC.toInt(); textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); letterSpacing = 0.08f
    }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE8E8EC.toInt(); style = Paint.Style.FILL }

    private var cx = 0f
    private var cy = 0f
    private var rOuter = 0f
    private var rInner = 0f
    private val arcRect = RectF()

    // Touch-Zustand
    private var lastAngle = 0.0
    private var accum = 0.0
    private var totalRotation = 0.0
    private var downInCenter = false
    private var active = false
    private var pressedZone = NONE

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        var side = min(w, (330 * dp).toInt())
        if (MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.UNSPECIFIED) {
            side = min(side, MeasureSpec.getSize(heightMeasureSpec))
        }
        setMeasuredDimension(w, side)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        cx = w / 2f
        cy = h / 2f
        rOuter = min(w, h) / 2f - 3 * dp
        rInner = rOuter * 0.37f
        labelPaint.textSize = rOuter * 0.115f
        wheelPaint.shader = LinearGradient(
            0f, cy - rOuter, 0f, cy + rOuter,
            0xFF232326.toInt(), 0xFF151517.toInt(), Shader.TileMode.CLAMP
        )
        centerPaint.shader = LinearGradient(
            0f, cy - rInner, 0f, cy + rInner,
            0xFF55565C.toInt(), 0xFF3A3B40.toInt(), Shader.TileMode.CLAMP
        )
        pressPaint.strokeWidth = rOuter - rInner
        val mid = (rOuter + rInner) / 2
        arcRect.set(cx - mid, cy - mid, cx + mid, cy + mid)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawCircle(cx, cy, rOuter, wheelPaint)
        canvas.drawCircle(cx, cy, rOuter, rimPaint)
        if (pressedZone in 0..3) {
            val start = when (pressedZone) { NEXT -> -45f; PLAY -> 45f; PREV -> 135f; else -> 225f }
            canvas.drawArc(arcRect, start, 90f, false, pressPaint)
        }
        canvas.drawCircle(cx, cy, rInner, centerPaint)
        canvas.drawCircle(cx, cy, rInner, centerRim)
        if (pressedZone == CENTER) canvas.drawCircle(cx, cy, rInner, Paint().apply { color = 0x22000000 })

        val labelR = (rOuter + rInner) / 2
        canvas.drawText("MENU", cx, cy - labelR + labelPaint.textSize * 0.36f, labelPaint)
        val s = rOuter * 0.065f
        drawSkip(canvas, cx + labelR, cy, s, forward = true)
        drawSkip(canvas, cx - labelR, cy, s, forward = false)
        drawPlayPause(canvas, cx, cy + labelR, s)
    }

    private fun triangle(x: Float, y: Float, s: Float, right: Boolean): Path = Path().apply {
        if (right) { moveTo(x, y - s); lineTo(x + s * 1.3f, y); lineTo(x, y + s) }
        else { moveTo(x, y - s); lineTo(x - s * 1.3f, y); lineTo(x, y + s) }
        close()
    }

    private fun drawSkip(c: Canvas, x: Float, y: Float, s: Float, forward: Boolean) {
        val bar = s * 0.32f
        if (forward) {
            c.drawPath(triangle(x - s * 1.5f, y, s, true), iconPaint)
            c.drawPath(triangle(x - s * 0.2f, y, s, true), iconPaint)
            c.drawRect(x + s * 1.15f, y - s, x + s * 1.15f + bar, y + s, iconPaint)
        } else {
            c.drawPath(triangle(x + s * 1.5f, y, s, false), iconPaint)
            c.drawPath(triangle(x + s * 0.2f, y, s, false), iconPaint)
            c.drawRect(x - s * 1.15f - bar, y - s, x - s * 1.15f, y + s, iconPaint)
        }
    }

    private fun drawPlayPause(c: Canvas, x: Float, y: Float, s: Float) {
        c.drawPath(triangle(x - s * 1.4f, y, s * 0.9f, true), iconPaint)
        val bw = s * 0.34f
        c.drawRect(x + s * 0.35f, y - s * 0.9f, x + s * 0.35f + bw, y + s * 0.9f, iconPaint)
        c.drawRect(x + s * 0.95f, y - s * 0.9f, x + s * 0.95f + bw, y + s * 0.9f, iconPaint)
    }

    private fun angleOf(x: Float, y: Float) = Math.toDegrees(atan2((y - cy).toDouble(), (x - cx).toDouble()))

    private fun zoneOf(angle: Double): Int = when {
        angle >= -45 && angle < 45 -> NEXT
        angle >= 45 && angle < 135 -> PLAY
        angle >= -135 && angle < -45 -> MENU
        else -> PREV
    }

    private fun tick() {
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val r = hypot((e.x - cx).toDouble(), (e.y - cy).toDouble())
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (r > rOuter + 12 * dp) return false
                active = true
                downInCenter = r < rInner
                lastAngle = angleOf(e.x, e.y)
                accum = 0.0
                totalRotation = 0.0
                pressedZone = if (downInCenter) CENTER else zoneOf(lastAngle)
                parent?.requestDisallowInterceptTouchEvent(true)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!active || downInCenter || r < rInner * 0.6) return true
                val angle = angleOf(e.x, e.y)
                var d = angle - lastAngle
                if (d > 180) d -= 360
                if (d < -180) d += 360
                lastAngle = angle
                accum += d
                totalRotation += kotlin.math.abs(d)
                if (totalRotation > 10 && pressedZone != NONE) { pressedZone = NONE; invalidate() }
                while (accum >= STEP) { accum -= STEP; tick(); listener?.onWheelScroll(1) }
                while (accum <= -STEP) { accum += STEP; tick(); listener?.onWheelScroll(-1) }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (active && totalRotation <= 10) {
                    val zone = if (downInCenter) CENTER else zoneOf(angleOf(e.x, e.y))
                    tick()
                    when (zone) {
                        CENTER -> listener?.onSelect()
                        MENU -> listener?.onMenu()
                        NEXT -> listener?.onNext()
                        PREV -> listener?.onPrev()
                        PLAY -> listener?.onPlayPause()
                    }
                }
                reset()
                return true
            }
            MotionEvent.ACTION_CANCEL -> { reset(); return true }
        }
        return true
    }

    private fun reset() {
        active = false
        pressedZone = NONE
        invalidate()
    }

    companion object {
        private const val STEP = 20.0 // Grad pro Scroll-Schritt
        private const val NONE = -1
        private const val NEXT = 0
        private const val PLAY = 1
        private const val PREV = 2
        private const val MENU = 3
        private const val CENTER = 4
    }
}
