package com.tianxiaodudou.qqmusicdownloader.player

import android.content.Context
import androidx.compose.ui.graphics.toArgb
import android.graphics.Canvas
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
 * 桌面歌词悬浮窗：两行（当前句 + 下一句），逐字染色，宽度按屏幕收敛、超长自动滚动。
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
            view?.update(
                LyricsRuntime.currentLine,
                LyricsRuntime.nextLine,
                LyricsRuntime.progress,
            )
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
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val v = LyricView(ctx)
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

    /** 悬浮歌词绘制：一行当前句（逐字染色）+ 一行下一句（暗色）。 */
    private class LyricView(ctx: Context) : View(ctx) {

        private val colors = com.tianxiaodudou.qqmusicdownloader.ui.theme.ThemeStore.colors
        private val density = resources.displayMetrics.density
        private val screenWidth = resources.displayMetrics.widthPixels

        private val padding = 18f * density
        private val lineGap = 6f * density
        private val textSize = 20f * density
        private val subTextSize = 15f * density

        private val back = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.argb(
                214,
                (colors.surface2.red * 255).toInt(),
                (colors.surface2.green * 255).toInt(),
                (colors.surface2.blue * 255).toInt(),
            )
        }
        private val mainDim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colors.lyricInactive.toArgb()
            textSize = this@LyricView.textSize
            typeface = Typeface.DEFAULT_BOLD
        }
        private val mainSung = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colors.lyricSung.toArgb()
            textSize = this@LyricView.textSize
            typeface = Typeface.DEFAULT_BOLD
        }
        private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colors.textSecondary.toArgb()
            textSize = subTextSize
        }
        private val round = RectF()

        private var line = ""
        private var nextLine = ""
        private var progress = 0f
        private var lineWidth = 0f
        private var nextWidth = 0f
        private var scroll = 0f
        private var lastStep = 0L

        private var win: WindowManager? = null
        private var lp: WindowManager.LayoutParams? = null
        private var prefs: android.content.SharedPreferences? = null

        fun attach(wm: WindowManager, params: WindowManager.LayoutParams, p: android.content.SharedPreferences) {
            win = wm
            lp = params
            prefs = p
        }

        private val maxTextWidth: Float
            get() = (screenWidth - padding * 2 - 24f * density).coerceAtLeast(120f * density)

        fun update(current: String, next: String, p: Float) {
            val textChanged = current != line || next != nextLine
            val progressChanged = kotlin.math.abs(p - progress) > 0.003f
            if (!textChanged && !progressChanged) return
            if (textChanged) {
                line = current
                nextLine = next
                scroll = 0f
                lineWidth = mainDim.measureText(line)
                nextWidth = subPaint.measureText(nextLine)
                // 文本变了必须重新测量，否则窗口宽度还停在上一句（之前只能显示四个半字就是这个原因）
                requestLayout()
            }
            progress = p
            invalidate()
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val contentW = maxOf(
                minOf(lineWidth, maxTextWidth),
                minOf(nextWidth, maxTextWidth),
                mainDim.measureText("桌面歌词") * 0.6f,
            )
            val w = (contentW + padding * 2).toInt().coerceAtMost(screenWidth - (12 * density).toInt())
            val h = (textSize + lineGap + subTextSize + padding * 2).toInt()
            setMeasuredDimension(w, h)
        }

        private fun stepScroll() {
            if (lineWidth <= maxTextWidth) return
            val now = System.currentTimeMillis()
            if (now - lastStep < 33) return
            lastStep = now
            scroll += 1.6f * density
            val overflow = lineWidth - maxTextWidth
            if (scroll > overflow + 24f * density) scroll = 0f
        }

        override fun onDraw(canvas: Canvas) {
            round.set(0f, 0f, width.toFloat(), height.toFloat())
            canvas.drawRoundRect(round, 18f * density, 18f * density, back)

            val baseline1 = padding + textSize * 0.92f
            val baseline2 = baseline1 + lineGap + subTextSize * 0.95f

            val shown = line.ifEmpty { "桌面歌词 · 等待播放" }
            val overflow = lineWidth > maxTextWidth
            stepScroll()
            val offset = if (overflow) scroll else 0f

            canvas.save()
            canvas.clipRect(padding, 0f, width - padding, height.toFloat())
            canvas.drawText(shown, padding - offset, baseline1, mainDim)
            if (line.isNotEmpty() && progress > 0f) {
                // 已唱部分：按文字宽度比例裁切（与播放页的逐字进度一致）
                canvas.save()
                canvas.clipRect(padding, 0f, padding + lineWidth * progress - offset, height.toFloat())
                canvas.drawText(shown, padding - offset, baseline1, mainSung)
                canvas.restore()
            }
            if (nextLine.isNotEmpty()) {
                canvas.drawText(nextLine, padding, baseline2, subPaint)
            }
            canvas.restore()
            if (overflow && line.isNotEmpty()) postInvalidateOnAnimation()
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val p = lp ?: return false
                    p.x += (event.rawX - downX).toInt()
                    p.y += (event.rawY - downY).toInt()
                    downX = event.rawX
                    downY = event.rawY
                    runCatching { win?.updateViewLayout(this, p) }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    prefs?.edit()?.putInt("x", lp?.x ?: 0)?.putInt("y", lp?.y ?: 0)?.apply()
                    return true
                }
            }
            return super.onTouchEvent(event)
        }

        private var downX = 0f
        private var downY = 0f
    }
}
