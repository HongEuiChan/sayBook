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
 * 유튜브·넷플릭스 등 OTT 영상 위에 떠 있는 정차역 표시.
 *
 * 1. 작은 표시: 항상 떠 있음. "강남 / 다음 역 · 역삼 출발" 처럼 지금 상황을 한 줄로. 드래그로 이동.
 * 2. 도착 배너: 역에 서면 화면 위쪽 가운데에 큰 글씨로 몇 초간 표시.
 *    영상에 몰입해 있어도 눈에 띄도록 노선색 배경 + 큰 역 이름. 터치는 아래 영상 앱으로 통과.
 *
 * 메인 스레드에서만 호출하세요.
 */
class StationOverlay(private val context: Context) {

    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var lineColor: Int = Color.GRAY

    // ── 작은 표시 ──
    private var pill: LinearLayout? = null
    private lateinit var pillDot: View
    private lateinit var pillTitle: TextView
    private lateinit var pillSubtitle: TextView
    private lateinit var pillBackground: GradientDrawable

    private val pillParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = dp(16)
        y = dp(48)
    }

    // ── 도착 배너 ──
    private var banner: LinearLayout? = null
    private val hideBanner = Runnable { removeBanner() }

    private val bannerParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        y = dp(24)
        // Android 12+ 는 불투명도 0.8 초과인 오버레이 아래로 터치를 넘기지 않음 → 0.8 로 맞춰 영상 조작을 막지 않게
        alpha = 0.8f
    }

    val canShow: Boolean get() = Settings.canDrawOverlays(context)

    fun show(lineColorRgb: Int) {
        lineColor = lineColorRgb or 0xFF000000.toInt()
        if (pill != null) {
            (pillDot.background as GradientDrawable).setColor(lineColor)
            return
        }
        if (!canShow) return
        val view = buildPill()
        pill = view
        windowManager.addView(view, pillParams)
    }

    /** 작은 표시 내용 갱신 */
    fun update(title: String, subtitle: String) {
        if (pill == null) return
        pillTitle.text = title
        pillSubtitle.text = subtitle
    }

    /**
     * 도착 배너를 [durationMs] 동안 크게 표시.
     * @param label 예: "이번 역", "종착역"
     * @param station 크게 보일 역 이름
     * @param detail 예: "다음 역 · 교대"
     */
    fun showArrival(label: String, station: String, detail: String, durationMs: Long = BANNER_MS) {
        if (!canShow) return
        removeBanner()
        val view = buildBanner(label, station, detail)
        banner = view
        view.alpha = 0f
        view.scaleX = 0.9f
        view.scaleY = 0.9f
        windowManager.addView(view, bannerParams)
        view.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(180).start()
        handler.postDelayed(hideBanner, durationMs)
    }

    fun hide() {
        removeBanner()
        pill?.let { runCatching { windowManager.removeView(it) } }
        pill = null
    }

    private fun removeBanner() {
        handler.removeCallbacks(hideBanner)
        banner?.let { runCatching { windowManager.removeView(it) } }
        banner = null
    }

    private fun buildBanner(label: String, station: String, detail: String): LinearLayout {
        val bg = GradientDrawable().apply {
            cornerRadius = dp(20).toFloat()
            setColor(lineColor)
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = bg
            setPadding(dp(32), dp(14), dp(32), dp(16))
            minimumWidth = dp(240)
            addView(text(label, 14f, bold = true, alpha = 230))
            addView(text(station, 36f, bold = true, alpha = 255))
            addView(text(detail, 14f, bold = false, alpha = 230))
        }
    }

    private fun text(value: String, sp: Float, bold: Boolean, alpha: Int) = TextView(context).apply {
        text = value
        gravity = Gravity.CENTER
        setTextColor(Color.argb(alpha, 255, 255, 255))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        paint.isFakeBoldText = bold
        setShadowLayer(4f, 0f, 1f, Color.argb(90, 0, 0, 0))
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildPill(): LinearLayout {
        pillBackground = GradientDrawable().apply {
            cornerRadius = dp(22).toFloat()
            setColor(Color.argb(200, 20, 20, 24))
        }
        pillDot = View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(lineColor)
            }
            layoutParams = LinearLayout.LayoutParams(dp(10), dp(10)).apply { marginEnd = dp(10) }
        }
        pillTitle = TextView(context).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            paint.isFakeBoldText = true
        }
        pillSubtitle = TextView(context).apply {
            setTextColor(Color.argb(210, 255, 255, 255))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        }
        val texts = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(pillTitle)
            addView(pillSubtitle)
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = pillBackground
            setPadding(dp(14), dp(8), dp(18), dp(8))
            addView(pillDot)
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
                    startX = pillParams.x; startY = pillParams.y
                    downX = e.rawX; downY = e.rawY
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (abs(dx) > 4 || abs(dy) > 4) {
                        pillParams.x = startX + dx.toInt()
                        pillParams.y = startY + dy.toInt()
                        pill?.let { windowManager.updateViewLayout(it, pillParams) }
                    }
                }
            }
            return true
        }
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), context.resources.displayMetrics).toInt()

    private companion object {
        const val BANNER_MS = 6_000L
    }
}
