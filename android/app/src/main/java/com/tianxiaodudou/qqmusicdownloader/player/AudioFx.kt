package com.tianxiaodudou.qqmusicdownloader.player

import android.content.Context
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
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
    private var session = 0
    private var preset = 0

    fun attach(context: Context, sessionId: Int, index: Int) {
        preset = index
        if (sessionId == session && eq != null) {
            apply(index)
            return
        }
        release()
        session = sessionId
        if (sessionId == 0) return
        runCatching { eq = Equalizer(0, sessionId) }
        runCatching { bass = BassBoost(0, sessionId) }
        apply(index)
    }

    fun apply(index: Int) {
        preset = index
        val e = eq ?: return
        val on = index != 0
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
        runCatching { eq?.release() }
        runCatching { bass?.release() }
        eq = null
        bass = null
        session = 0
    }

    fun current(): Int = preset
}
