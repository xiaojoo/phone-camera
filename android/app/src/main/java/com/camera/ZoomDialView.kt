package com.camera

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/*
 * 画面上的变焦拨盘：上半圆弧，沿弧拖动改倍率，左端是这颗镜头的下限、右端是上限。
 *
 * 只认角度不认半径，所以手指偏出弧线一点也能拖，不用瞄准。
 *
 * 按下去只把圆点换成按下的绿，不长不小不加光圈。
 */
class ZoomDialView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    interface Listener {
        fun onProgress(progress: Float)
        fun onCommit()
    }

    private val density = resources.displayMetrics.density

    private fun dp(v: Float) = v * density
    private fun sp(v: Float) = v * density

    private val radius = dp(58f)
    private val knobRadius = dp(10f)

    /*
     * 端点标签的基线。圆点在端点上时它的下沿在 cy+knobRadius，
     * 11sp 字的墨迹上沿在基线上方约 8dp——留到 24dp 才真的不挨着。
     */
    private val endLabelY = dp(24f)
    private val labelSpace = endLabelY + dp(4f)

    private val trackWidth = dp(3f)
    private val tickLength = dp(8f)

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = trackWidth
        strokeCap = Paint.Cap.ROUND
        color = 0xFF343A44.toInt()
    }

    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = trackWidth
        strokeCap = Paint.Cap.ROUND
        color = 0xFF4CAF50.toInt()
    }

    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
        color = 0xFF626B77.toInt()
    }

    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFF4CAF50.toInt()
    }

    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF1F4F8.toInt()
        textSize = sp(15f)
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.MONOSPACE
        setShadowLayer(dp(4f), 0f, 0f, Color.BLACK)
    }

    private val endPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        /*
         * 端点标签压在实时画面上，亮天空和暗墙都会遇到：
         * 用次级灰在亮背景上就看不清了，所以给主文字色加黑投影。
         */
        color = 0xFFF1F4F8.toInt()
        textSize = sp(11f)
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.MONOSPACE
        setShadowLayer(dp(4f), 0f, 0f, Color.BLACK)
    }

    private val arc = RectF()

    var listener: Listener? = null

    var zoomMin = 1f
        private set

    var zoomMax = 1f
        private set

    var progress = 0f
        private set

    var isDragging = false
        private set

    /* 没有变焦区间的镜头（上下限相等）直接把触摸让出去，不装成能用的样子 */
    var zoomCapable = true
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    fun setRange(min: Float, max: Float) {
        zoomMin = min
        zoomMax = max.coerceAtLeast(min)
        zoomCapable = zoomMax - zoomMin > 0.01f
        invalidate()
    }

    /* 外部同步（接口改的、换镜头夹过的），不回触发监听 */
    fun syncProgress(value: Float) {
        val next = value.coerceIn(0f, 1f)

        if (next == progress) return

        progress = next
        invalidate()
    }

    fun ratioAt(progress: Float): Float = zoomMin + (zoomMax - zoomMin) * progress

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val wantW = 2f * radius + 2f * (knobRadius + dp(2f))
        val wantH = radius + knobRadius + labelSpace

        setMeasuredDimension(
            resolveSize(wantW.toInt(), widthMeasureSpec),
            resolveSize(wantH.toInt(), heightMeasureSpec)
        )
    }

    private fun centerX() = width / 2f
    private fun centerY() = height - labelSpace

    private fun angleOf(progress: Float) = 180.0 + 180.0 * progress

    override fun onDraw(canvas: Canvas) {
        val cx = centerX()
        val cy = centerY()

        arc.set(cx - radius, cy - radius, cx + radius, cy + radius)

        val muted = if (zoomCapable) 0xFF343A44.toInt() else 0xFF22262D.toInt()
        trackPaint.color = muted
        canvas.drawArc(arc, 180f, 180f, false, trackPaint)

        if (zoomCapable) drawTicks(canvas, cx, cy)

        if (progress > 0f) {
            canvas.drawArc(
                arc,
                180f,
                (180f * progress),
                false,
                progressPaint
            )
        }

        val angle = Math.toRadians(angleOf(progress))
        val kx = cx + radius * cos(angle).toFloat()
        val ky = cy + radius * sin(angle).toFloat()

        knobPaint.color =
            if (!zoomCapable) 0xFF626B77.toInt()
            else if (isDragging) 0xFF43943F.toInt()
            else 0xFF4CAF50.toInt()

        canvas.drawCircle(kx, ky, knobRadius, knobPaint)

        val value = ratioAt(progress)

        canvas.drawText(
            formatRatio(value),
            cx,
            cy - radius * 0.42f,
            valuePaint
        )

        canvas.drawText(formatRatio(zoomMin), cx - radius, cy + endLabelY, endPaint)
        canvas.drawText(formatRatio(zoomMax), cx + radius, cy + endLabelY, endPaint)
    }

    /* 整数就写 1x、10x，带小数才写 2.4x；Locale 显式给 US，别被区域设置换成逗号 */
    private fun formatRatio(value: Float): String {
        val rounded = Math.round(value).toFloat()

        return if (Math.abs(value - rounded) < 0.05f) "${rounded.toInt()}x"
        else String.format(Locale.US, "%.1fx", value)
    }

    /* 整数倍率的地方打一根短刻度，拖的时候有个可对的位置 */
    private fun drawTicks(canvas: Canvas, cx: Float, cy: Float) {
        val span = zoomMax - zoomMin

        if (span <= 0f) return

        val first = Math.ceil(zoomMin.toDouble()).toInt()
        val last = Math.floor(zoomMax.toDouble()).toInt()

        var step = 1
        if (last - first > 12) step = if (last - first > 30) 5 else 2

        var ratio = first
        while (ratio <= last) {
            if (ratio > zoomMin + 0.01f && ratio < zoomMax - 0.01f) {
                val angle = Math.toRadians(angleOf((ratio - zoomMin) / span))
                val cos = cos(angle).toFloat()
                val sin = sin(angle).toFloat()

                canvas.drawLine(
                    cx + cos * (radius - tickLength / 2f),
                    cy + sin * (radius - tickLength / 2f),
                    cx + cos * (radius + tickLength / 2f),
                    cy + sin * (radius + tickLength / 2f),
                    tickPaint
                )
            }

            ratio += step
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!zoomCapable) return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                isDragging = true
                parent?.requestDisallowInterceptTouchEvent(true)
                apply(event)
                invalidate()
            }

            MotionEvent.ACTION_MOVE -> apply(event)

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                if (event.actionMasked == MotionEvent.ACTION_UP) apply(event)

                isDragging = false
                listener?.onCommit()
                invalidate()
            }

            else -> return false
        }

        return true
    }

    private fun apply(event: MotionEvent) {
        val next = progressOf(event.x - centerX(), event.y - centerY())

        if (next == progress) return

        progress = next
        listener?.onProgress(next)
        invalidate()
    }

    private fun progressOf(dx: Float, dy: Float): Float {
        val angle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble()))

        /* 拖到中线以下按左右端收，不给一个跳来跳去的数 */
        if (angle > 0.0) return if (dx >= 0f) 1f else 0f

        return ((angle + 180.0) / 180.0).toFloat().coerceIn(0f, 1f)
    }
}
