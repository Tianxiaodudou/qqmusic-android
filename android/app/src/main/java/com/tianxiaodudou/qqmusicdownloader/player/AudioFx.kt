package com.tianxiaodudou.qqmusicdownloader.player

import android.content.Context
import android.media.audiofx.BassBoost
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.Equalizer
import com.tianxiaodudou.qqmusicdownloader.net.Settings
import kotlin.math.PI
import kotlin.math.sin

/**
 * 均衡器：挂在 ExoPlayer 的 audioSessionId 上。
 * 预设只是一条归一化的频响曲线，再按设备实际的 band 数和电平范围映射。
 */
object AudioFx {

    val PRESETS = listOf("关闭", "流行", "摇滚", "古典", "爵士", "人声", "重低音")

    private var eq: Equalizer? = null
    private var bass: BassBoost? = null
    private var gain: LoudnessEnhancer? = null
    private var session = 0
    private var lastSessionId = 0
    private var preset = 0
    private var context: Context? = null

    @Volatile
    private var lastAttachAt = 0L

    fun attach(context: Context, sessionId: Int, index: Int) {
        preset = index
        this.context = context.applicationContext
        if (sessionId != 0) lastSessionId = sessionId
        // 关闭状态：完全脱离播放器实例，不创建任何效果器
        if (index <= 0) {
            runCatching { release() }
            session = 0
            return
        }
        // 音频会话没变就不重建效果器：重复 new Equalizer/BassBoost 会在输出链上产生咔哒声
        if (sessionId == session && eq != null) {
            runCatching { apply(index) }
            return
        }
        // 处理系统短时间内连续回调同一次会话的情况
        val now = android.os.SystemClock.elapsedRealtime()
        if (sessionId == session && now - lastAttachAt < 1500) return
        lastAttachAt = now
        runCatching { release() }
        session = sessionId
        if (sessionId == 0) return
        runCatching { eq = Equalizer(0, sessionId) }
        runCatching { bass = BassBoost(0, sessionId) }
        runCatching { gain = LoudnessEnhancer(sessionId) }
        runCatching { apply(index) }
        runCatching { setGain(Settings.volumeGain(context)) }
    }

    /** 全局音量增益：level 0..9 -> 0..+9dB（LoudnessEnhancer 的目标增益单位是 mB）。 */
    fun setGain(level: Int) {
        val l = level.coerceIn(0, 9)
        val e = gain ?: return
        runCatching {
            e.enabled = l > 0
            if (l > 0) e.setTargetGain(l * 100)
        }
    }

    fun apply(index: Int) {
        preset = index
        if (index <= 0) {
            runCatching { release() }
            session = 0
            return
        }
        // 关闭状态下是「完全脱离」的，所以重新开启时要按记住的会话重新挂上
        if (eq == null && lastSessionId != 0) {
            session = lastSessionId
            runCatching { eq = Equalizer(0, lastSessionId) }
            runCatching { bass = BassBoost(0, lastSessionId) }
        }
        val e = eq ?: return
        val on = true
        runCatching {
            e.enabled = on
            if (on) {
                val range = e.bandLevelRange
                val min = range[0].toInt()
                val max = range[1].toInt()
                val bands = e.numberOfBands.toInt()
                for (i in 0 until bands) {
                    val x = if (bands <= 1) 0.5f else i.toFloat() / (bands - 1)
                    val curve = curve(index, x)
                    val level = (curve * max).toInt().coerceIn(min, max)
                    e.setBandLevel(i.toShort(), level.toShort())
                }
            }
            bass?.let { b ->
                b.enabled = on && index != 7
                if (on) b.setStrength((if (index == 7) 1000.toShort() else 300.toShort()))
            }
        }
    }

    /** 归一化频响：x = 0 低频，1 高频，返回值 -1..1。 */
    private fun curve(index: Int, x: Float): Float {
        val v = when (index) {
            1 -> 0.35f + 0.55f * sin(PI.toFloat() * x)            // 流行：中间人声
            2 -> -0.6f + 1.2f * Math.abs(x - 0.5f) * 2f          // 摇滚：两头翘
            3 -> 0.25f - 0.3f * x                                 // 古典：略偏暖
            4 -> 0.4f - 0.2f * Math.abs(x - 0.35f)                // 爵士：中低
            5 -> 0.1f + 0.7f * sin(PI.toFloat() * (x * 0.9f + 0.05f)) // 人声：中频
            6 -> 0.9f - 1.1f * x                                  // 重低音
            else -> 0f
        }
        return v.coerceIn(-1f, 1f)
    }

    fun release() {
        runCatching { gain?.release() }
        gain = null
        runCatching { eq?.release() }
        runCatching { bass?.release() }
        eq = null
        bass = null
        session = 0
    }

    fun current(): Int = preset
}
