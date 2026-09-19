package com.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Outline
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewOutlineProvider
import android.view.WindowManager
import androidx.camera.view.PreviewView
import kotlin.math.hypot

/*
 * 自绘的悬浮小窗。系统 PiP 的圆角描边和画面圆角对不齐，四角会露出暗边，
 * 这里自己裁圆角、自己定右上角的位置。
 *
 * 画面不是从这里开第二路相机，而是把 MainActivity 那个 Preview 的
 * surfaceProvider 指到 previewView 上。
 */
class FloatingPreview(
    private val context: Context,
    private val onTap: () -> Unit,
) {

    private val windows = context.getSystemService(WindowManager::class.java)
    private val density = context.resources.displayMetrics.density

    private val widthPx = (132 * density).toInt()
    private val heightPx = (176 * density).toInt()
    private val marginPx = (8 * density).toInt()
    private val cornerPx = 16 * density

    private var showing = false

    val previewView = PreviewView(context).apply {
        /* TextureView 跟着视图一起被圆角裁掉，SurfaceView 在部分系统上不吃这个裁剪 */
        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        scaleType = PreviewView.ScaleType.FILL_CENTER
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, cornerPx)
            }
        }
        clipToOutline = true
    }

    private val params = WindowManager.LayoutParams().apply {
        type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
        }
        format = PixelFormat.TRANSLUCENT
        width = widthPx
        height = heightPx
        gravity = Gravity.TOP or Gravity.START
        flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
    }

    private var downRawX = 0f
    private var downRawY = 0f
    private var downParamX = 0
    private var downParamY = 0
    private var dragging = false

    val isShowing: Boolean get() = showing

    init {
        bindDrag()
    }

    fun show() {
        if (showing) return

        params.x = screenWidth() - widthPx - marginPx
        params.y = statusBarHeight() + marginPx

        windows.addView(previewView, params)

        showing = true
    }

    fun hide() {
        if (!showing) return

        runCatching { windows.removeView(previewView) }
        showing = false
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun bindDrag() {
        val slop = ViewConfiguration.get(context).scaledTouchSlop

        previewView.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    downParamX = params.x
                    downParamY = params.y
                    dragging = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY

                    if (!dragging && hypot(dx, dy) > slop) dragging = true

                    if (dragging) {
                        params.x = (downParamX + dx).toInt()
                            .coerceIn(0, screenWidth() - widthPx)
                        params.y = (downParamY + dy).toInt()
                            .coerceIn(0, screenHeight() - heightPx)
                        runCatching { windows.updateViewLayout(previewView, params) }
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    if (!dragging) onTap()
                    dragging = false
                    true
                }

                else -> false
            }
        }
    }

    private fun screenWidth() = context.resources.displayMetrics.widthPixels

    private fun screenHeight() = context.resources.displayMetrics.heightPixels

    private fun statusBarHeight(): Int {
        val id = context.resources.getIdentifier("status_bar_height", "dimen", "android")

        return if (id > 0) context.resources.getDimensionPixelSize(id)
        else (24 * density).toInt()
    }
}
