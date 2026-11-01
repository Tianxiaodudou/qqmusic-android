package com.tianxiaodudou.qqmusicdownloader.player

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import androidx.compose.ui.graphics.toArgb
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager

/**
 * 桌面歌词悬浮窗：复用 LyricsRuntime 的逐字进度做逐字染色，颜色跟随应用深色皮肤。
 * 关闭时直接 removeView，不常驻。
 */
object DesktopLyrics {

    private const val PREFS = "desktop_lyrics"
    private const val K_X = "x"
    private const val K_Y = "y"

    private var view: LyricView? = null
    private var params: WindowManager.LayoutParams? = null
    private var wm: WindowManager? = null
    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            view?.update(LyricsRuntime.currentLine, LyricsRuntime.progress)
            handler.postDelayed(this, 50)
        }
    }

    fun setEnabled(ctx: Context, on: Boolean) {
        if (on && !Settings.canDrawOverlays(ctx)) return
        if (on) show(ctx) else hide()
    }

    fun isShowing(): Boolean = view != null

    /** 主题切换后重建悬浮窗，颜色立即跟随。 */
    fun refresh(ctx: Context) {
        if (view == null) return
        hide()
        show(ctx)
    }

    private fun show(ctx: Context) {
        if (view != null) return
        val w = ctx.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        wm = w
        val v = LyricView(ctx)
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.graphics.PixelFormat.TRANSLUCENT,
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = prefs.getInt(K_X, 0)
        lp.y = prefs.getInt(K_Y, 240)
        v.attach(w, lp, prefs)
        runCatching { w.addView(v, lp) }.onFailure { return }
        view = v
        params = lp
        LyricsRuntime.start()
        handler.post(ticker)
    }

    private fun hide() {
        handler.removeCallbacks(ticker)
        val v = view ?: return
        runCatching { wm?.removeView(v) }
        view = null
        params = null
    }

    /** 悬浮歌词的绘制：暗色打底 + 已唱部分用品牌色按宽度裁切。 */
    private class LyricView(ctx: Context) : View(ctx) {

        private val colors = com.tianxiaodudou.qqmusicdownloader.ui.theme.ThemeStore.colors
        private val back = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.argb(
                224,
                (colors.surface2.red * 255).toInt(),
                (colors.surface2.green * 255).toInt(),
                (colors.surface2.blue * 255).toInt(),
            )
        }
        private val dim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colors.lyricInactive.toArgb()
            textSize = 46f
            typeface = Typeface.DEFAULT_BOLD
        }
        private val sung = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colors.lyricSung.toArgb()
            textSize = 46f
            typeface = Typeface.DEFAULT_BOLD
        }
        private val round = RectF()
        private var text = ""
        private var progress = 0f
        private var lastX = 0f
        private var lastY = 0f
        private var w: WindowManager? = null
        private var lp: WindowManager.LayoutParams? = null
        private var prefs: android.content.SharedPreferences? = null

        fun attach(wm: WindowManager, params: WindowManager.LayoutParams, p: android.content.SharedPreferences) {
            w = wm
            lp = params
            prefs = p
        }

        fun update(line: String, p: Float) {
            if (line == text && kotlin.math.abs(p - progress) < 0.004f) return
            text = line
            progress = p
            invalidate()
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val padding = 36f
            val textWidth = if (text.isEmpty()) dim.measureText("桌面歌词") else dim.measureText(text)
            setMeasuredDimension((textWidth + padding * 2).toInt(), (46f * 1.9f + padding).toInt())
        }

        override fun onDraw(canvas: Canvas) {
            round.set(0f, 0f, width.toFloat(), height.toFloat())
            canvas.drawRoundRect(round, 40f, 40f, back)
            val shown = text.ifEmpty { "桌面歌词 · 等待播放" }
            val baseline = height / 2f + 16f
            canvas.drawText(shown, 36f, baseline, dim)
            if (text.isNotEmpty() && progress > 0f) {
                val clipRight = 36f + dim.measureText(shown) * progress
                canvas.save()
                canvas.clipRect(0f, 0f, clipRight, height.toFloat())
                canvas.drawText(shown, 36f, baseline, sung)
                canvas.restore()
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = event.rawX
                    lastY = event.rawY
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val p = lp ?: return false
                    p.x += (event.rawX - lastX).toInt()
                    p.y += (event.rawY - lastY).toInt()
                    lastX = event.rawX
                    lastY = event.rawY
                    runCatching { w?.updateViewLayout(this, p) }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    // 横向 + 纵向位置都记住，下次按同一位置出现
                    prefs?.edit()
                        ?.putInt("x", lp?.x ?: 0)
                        ?.putInt("y", lp?.y ?: 0)
                        ?.apply()
                    return true
                }
            }
            return super.onTouchEvent(event)
        }
    }
}
