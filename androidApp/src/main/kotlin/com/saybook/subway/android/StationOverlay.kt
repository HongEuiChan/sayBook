package com.saybook.subway.android

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/**
 * 유튜브·넷플릭스 등 다른 앱 위에 떠 있는 작은 정차역 표시 창.
 * 드래그로 위치를 옮길 수 있고, 정차 시 노선색으로 강조됩니다.
 *
 * 메인 스레드에서만 호출하세요.
 */
class StationOverlay(private val context: Context) {

    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var root: LinearLayout? = null
    private lateinit var dot: View
    private lateinit var titleView: TextView
    private lateinit var subtitleView: TextView
    private lateinit var background: GradientDrawable
    private var lineColor: Int = Color.GRAY
    private val unhighlight = Runnable { applyColors(highlight = false) }

    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = dp(16)
        y = dp(48)
    }

    val canShow: Boolean get() = Settings.canDrawOverlays(context)

    fun show(lineColorRgb: Int) {
        lineColor = lineColorRgb or 0xFF000000.toInt()
        if (root != null) return applyColors(highlight = false)
        if (!canShow) return
        val view = buildView()
        root = view
        applyColors(highlight = false)
        windowManager.addView(view, params)
    }

    fun update(title: String, subtitle: String, highlight: Boolean) {
        if (root == null) return
        titleView.text = title
        subtitleView.text = subtitle
        if (highlight) {
            applyColors(highlight = true)
            handler.removeCallbacks(unhighlight)
            handler.postDelayed(unhighlight, HIGHLIGHT_MS)
        }
    }

    fun hide() {
        handler.removeCallbacks(unhighlight)
        root?.let { runCatching { windowManager.removeView(it) } }
        root = null
    }

    private fun applyColors(highlight: Boolean) {
        if (root == null) return
        background.setColor(if (highlight) lineColor else Color.argb(220, 20, 20, 24))
        (dot.background as GradientDrawable).setColor(if (highlight) Color.WHITE else lineColor)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildView(): LinearLayout {
        background = GradientDrawable().apply { cornerRadius = dp(22).toFloat() }
        dot = View(context).apply {
            this.background = GradientDrawable().apply { shape = GradientDrawable.OVAL }
            layoutParams = LinearLayout.LayoutParams(dp(10), dp(10)).apply { marginEnd = dp(10) }
        }
        titleView = TextView(context).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            paint.isFakeBoldText = true
        }
        subtitleView = TextView(context).apply {
            setTextColor(Color.argb(210, 255, 255, 255))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        }
        val texts = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(titleView)
            addView(subtitleView)
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            this.background = this@StationOverlay.background
            setPadding(dp(14), dp(8), dp(18), dp(8))
            addView(dot)
            addView(texts)
            setOnTouchListener(DragListener())
        }
    }

    private inner class DragListener : View.OnTouchListener {
        private var startX = 0
        private var startY = 0
        private var downX = 0f
        private var downY = 0f

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x; startY = params.y
                    downX = e.rawX; downY = e.rawY
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (abs(dx) > 4 || abs(dy) > 4) {
                        params.x = startX + dx.toInt()
                        params.y = startY + dy.toInt()
                        root?.let { windowManager.updateViewLayout(it, params) }
                    }
                }
            }
            return true
        }
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), context.resources.displayMetrics).toInt()

    private companion object {
        const val HIGHLIGHT_MS = 5_000L
    }
}
