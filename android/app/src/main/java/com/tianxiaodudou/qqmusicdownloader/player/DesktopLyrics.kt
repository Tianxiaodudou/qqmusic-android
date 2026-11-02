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
 * 桌面歌词悬浮窗：两行（当前句 + 下一句），同一字号，逐字染色，超长横向滚动。
 * 窗口贴合文字，不允许拖出屏幕。
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
            val v = view
            if (v != null) {
                // 没在播放就自动隐藏，避免停在桌面时一直挂着一块歌词
                if (PlayerController.playing) {
                    v.visibility = View.VISIBLE
                    v.refreshFromPlayer()
                } else {
                    v.visibility = View.GONE
                }
            }
            handler.postDelayed(this, 80)
        }
    }

    fun setEnabled(ctx: Context, on: Boolean) {
        if (on && !Settings.canDrawOverlays(ctx)) return
        if (on) show(ctx) else hide()
    }

    fun isShowing(): Boolean = view != null

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
        v.clampInto(lp)
        runCatching { w.updateViewLayout(v, lp) }
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

    /** 两行歌词绘制。 */
    private class LyricView(ctx: Context) : View(ctx) {

        private val colors = com.tianxiaodudou.qqmusicdownloader.ui.theme.ThemeStore.colors
        private val density = resources.displayMetrics.density
        private val screenW = resources.displayMetrics.widthPixels
        private val screenH = resources.displayMetrics.heightPixels

        // 紧贴文字：水平 6dp、垂直 4dp、行距 2dp，另留 4dp 给描边光晕不被裁掉
        private val padH = 6f * density
        private val padV = 4f * density
        private val glow = 4f * density
        private val gap = 2f * density
        private val textSize = 18f * density

        private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = colors.brand.toArgb()
        }

        private val back = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.argb(
                210,
                (colors.surface2.red * 255).toInt(),
                (colors.surface2.green * 255).toInt(),
                (colors.surface2.blue * 255).toInt(),
            )
        }
        private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colors.lyricInactive.toArgb()
            textSize = this@LyricView.textSize
            typeface = Typeface.DEFAULT_BOLD
        }
        private val sungPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colors.lyricSung.toArgb()
            textSize = this@LyricView.textSize
            typeface = Typeface.DEFAULT_BOLD
        }
        private val nextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colors.textSecondary.toArgb()
            textSize = this@LyricView.textSize
            typeface = Typeface.DEFAULT_BOLD
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

        private val maxTextWidth: Float get() = (screenW - (padH + glow) * 2 - 16f * density)

        /** 直接按播放进度算当前句/下一句，避免两层 ticker 叠加造成换行延迟。 */
        fun refreshFromPlayer() {
            val cur = LyricsRuntime.lines
            if (cur.isEmpty()) {
                applyText("", "", 0f)
                return
            }
            val t = PlayerController.positionNow()
            var i = -1
            for (k in cur.indices) {
                // 用「行首第一个字的时间」判定换行，比行时间戳更贴近实际开口
                val startAt = cur[k].words.firstOrNull()?.startMs?.toLong() ?: cur[k].timeMs.toLong()
                if (startAt <= t) i = k else break
            }
            if (i < 0) {
                applyText("", cur[0].text, 0f)
                return
            }
            val lineObj = cur[i]
            val nextStart = cur.getOrNull(i + 1)?.words?.firstOrNull()?.startMs?.toLong()
                ?: cur.getOrNull(i + 1)?.timeMs?.toLong()
                ?: (lineObj.timeMs + 5000L)
            applyText(lineObj.text, cur.getOrNull(i + 1)?.text.orEmpty(), progressOf(lineObj, t, nextStart))
        }

        private fun progressOf(lineObj: com.tianxiaodudou.qqmusicdownloader.net.LyricApi.Line, t: Long, nextStart: Long): Float {
            val total = lineObj.words.sumOf { it.text.length }
            if (total == 0) {
                val s = lineObj.timeMs.toLong()
                val e = maxOf(nextStart, s + 2000L)
                return ((t - s).toFloat() / (e - s)).coerceIn(0f, 1f)
            }
            var sung = 0f
            for (w in lineObj.words) {
                val s = w.startMs.toLong()
                val e = s + w.durMs
                if (t >= e) sung += w.text.length
                else if (t > s) {
                    sung += w.text.length * ((t - s).toFloat() / w.durMs.coerceAtLeast(1))
                    break
                } else break
            }
            return (sung / total).coerceIn(0f, 1f)
        }

        private fun applyText(current: String, next: String, p: Float) {
            val changed = current != line || next != nextLine
            if (!changed && kotlin.math.abs(p - progress) < 0.003f) return
            if (changed) {
                line = current
                nextLine = next
                scroll = 0f
                lineWidth = dimPaint.measureText(line)
                nextWidth = nextPaint.measureText(nextLine)
                requestLayout()
            }
            progress = p
            invalidate()
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = (maxOf(minOf(lineWidth, maxTextWidth), minOf(nextWidth, maxTextWidth)) + (padH + glow) * 2)
                .toInt().coerceAtLeast((24 * density).toInt())
            val h = (textSize * 2 + gap + (padV + glow) * 2).toInt()
            setMeasuredDimension(w, h)
        }

        fun clampInto(p: WindowManager.LayoutParams) {
            val w = measuredWidth.takeIf { it > 0 } ?: (maxTextWidth + (padH + glow) * 2).toInt()
            val h = measuredHeight.takeIf { it > 0 } ?: (textSize * 2 + gap + (padV + glow) * 2).toInt()
            p.x = p.x.coerceIn(0, (screenW - w).coerceAtLeast(0))
            p.y = p.y.coerceIn(0, (screenH - h).coerceAtLeast(0))
        }

        private fun stepScroll() {
            if (lineWidth <= maxTextWidth) return
            val now = System.currentTimeMillis()
            if (now - lastStep < 33) return
            lastStep = now
            scroll += 1.4f * density
            val overflow = lineWidth - maxTextWidth
            if (scroll > overflow + 20f * density) scroll = 0f
        }

        override fun onDraw(canvas: Canvas) {
            val inset = glow * 0.6f
            round.set(inset, inset, width - inset, height - inset)
            val radius = 9f * density
            canvas.drawRoundRect(round, radius, radius, back)

            // 荧光描边：由外到内叠 3 层低透明度粗边 + 1 层亮边，模拟发光
            val glowLayers = arrayOf(3.2f to 40, 2.2f to 80, 1.3f to 140)
            for ((w, a) in glowLayers) {
                framePaint.strokeWidth = w * density
                framePaint.alpha = a
                canvas.drawRoundRect(round, radius, radius, framePaint)
            }
            framePaint.strokeWidth = 1.1f * density
            framePaint.alpha = 235
            canvas.drawRoundRect(round, radius, radius, framePaint)

            val left = padH + glow
            val baseline1 = glow + padV + textSize * 0.92f
            val baseline2 = baseline1 + gap + textSize * 0.95f

            val shown = line.ifEmpty { "桌面歌词 · 等待播放" }
            val overflow = lineWidth > maxTextWidth
            stepScroll()
            val offset = if (overflow) scroll else 0f

            canvas.save()
            canvas.clipRect(left, 0f, width - left, height.toFloat())
            canvas.drawText(shown, left - offset, baseline1, dimPaint)
            if (line.isNotEmpty() && progress > 0f) {
                canvas.save()
                canvas.clipRect(left, 0f, left + lineWidth * progress - offset, height.toFloat())
                canvas.drawText(shown, left - offset, baseline1, sungPaint)
                canvas.restore()
            }
            if (nextLine.isNotEmpty()) {
                canvas.drawText(nextLine, left, baseline2, nextPaint)
            }
            canvas.restore()
            if (overflow) postInvalidateOnAnimation()
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
                    clampInto(p)
                    runCatching { win?.updateViewLayout(this, p) }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    clampInto(lp ?: return true)
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
