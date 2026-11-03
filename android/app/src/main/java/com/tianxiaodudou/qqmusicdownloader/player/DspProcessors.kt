package com.tianxiaodudou.qqmusicdownloader.player

import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * 均衡器与音量增益的运行时参数（UI 改这里，播放线程下一个 buffer 就读到）。
 * 两个处理器常驻在播放器内部的处理链上，**不增删处理器**，所以改档即时生效、不会重配 AudioSink。
 */
object ProcessorState {
    @Volatile
    var eqPreset: Int = 0
    @Volatile
    var gainLevel: Int = 0
}

/** 预设曲线：7 段（60/150/400/1k/2.5k/6k/12k Hz），单位 dB。 */
internal object EqPresets {
    val NAMES = listOf("关闭", "流行", "摇滚", "古典", "爵士", "人声", "重低音")
    private val CURVES = arrayOf(
        floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f),
        floatArrayOf(2f, 1f, 0f, -1f, 0f, 1f, 2f),
        floatArrayOf(5f, 3f, -1f, -2f, 0f, 2f, 3f),
        floatArrayOf(3f, 1f, 0f, 0f, 1f, 2f, 3f),
        floatArrayOf(3f, 2f, 0f, 1f, 0f, 1f, 2f),
        floatArrayOf(-2f, 0f, 3f, 3f, 2f, 0f, -1f),
        floatArrayOf(7f, 5f, 1f, 0f, 0f, 0f, 0f),
    )

    fun curve(preset: Int): FloatArray = CURVES[preset.coerceIn(0, CURVES.lastIndex)]
}

/**
 * 7 段 biquad 均衡器，跑在播放器内部（解码后、写入 AudioTrack 前）。
 * 只处理 16-bit PCM；其它格式直接透传，避免影响兼容性。
 */
class EqProcessor : BaseAudioProcessor() {

    private val freqs = floatArrayOf(60f, 150f, 400f, 1000f, 2500f, 6000f, 12000f)
    private val q = 0.9f
    private var sampleRate = 44100
    private var channels = 2

    private val currentDb = FloatArray(freqs.size)
    private val targetDb = FloatArray(freqs.size)
    private val coeffs = Array(freqs.size) { FloatArray(5) }
    private val state = Array(freqs.size) { Array(2) { FloatArray(4) } }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != androidx.media3.common.C.ENCODING_PCM_16BIT) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        sampleRate = inputAudioFormat.sampleRate
        channels = inputAudioFormat.channelCount
        currentDb.fill(0f)
        targetDb.fill(0f)
        coeffs.forEach { it.fill(0f) }
        state.forEach { perCh -> perCh.forEach { it.fill(0f) } }
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val samples = inputBuffer.remaining() / 2
        if (samples <= 0) return
        val input = inputBuffer.order(ByteOrder.nativeOrder()).asShortBuffer()
        val out = replaceOutputBuffer(inputBuffer.remaining()).order(ByteOrder.nativeOrder()).asShortBuffer()

        // 目标曲线 + 每 buffer 平滑逼近，避免切预设时的阶跃爆音
        val target = EqPresets.curve(ProcessorState.eqPreset)
        var moving = false
        for (i in targetDb.indices) {
            targetDb[i] = target.getOrElse(i) { 0f }
            val diff = targetDb[i] - currentDb[i]
            if (abs(diff) > 0.02f) {
                currentDb[i] += diff * 0.2f
                moving = true
            } else currentDb[i] = targetDb[i]
            computeCoeffs(i)
        }

        val frames = samples / channels
        for (f in 0 until frames) {
            for (ch in 0 until min(channels, 2)) {
                var v = input.get(f * channels + ch) / 32768f
                for (b in freqs.indices) {
                    val c = coeffs[b]
                    if (c[0] == 0f) continue
                    val s = state[b][ch]
                    val y = c[0] * v + c[1] * s[0] + c[2] * s[1] - c[3] * s[2] - c[4] * s[3]
                    s[1] = s[0]; s[0] = v; s[3] = s[2]; s[2] = y
                    v = y
                }
                val idx = f * channels + ch
                out.put(idx, (v.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
            }
            // 超过 2 声道时其余声道直接透传
            for (ch in 2 until channels) out.put(f * channels + ch, input.get(f * channels + ch))
        }
        if (moving) {
            // 让下一个 buffer 继续逼近
        }
        inputBuffer.position(inputBuffer.limit())
        out.position(samples)
    }

    private fun computeCoeffs(band: Int) {
        val db = currentDb[band]
        if (abs(db) < 0.05f) {
            coeffs[band].fill(0f)
            return
        }
        val a = 10f.pow(db / 40f)
        val w0 = 2.0 * PI * freqs[band] / sampleRate
        val cosW = cos(w0).toFloat()
        val alpha = (sin(w0) / (2.0 * q)).toFloat()
        val b0 = 1f + alpha * a
        val b1 = -2f * cosW
        val b2 = 1f - alpha * a
        val a0 = 1f + alpha / a
        val a1 = -2f * cosW
        val a2 = 1f - alpha / a
        coeffs[band][0] = b0 / a0
        coeffs[band][1] = b1 / a0
        coeffs[band][2] = b2 / a0
        coeffs[band][3] = a1 / a0
        coeffs[band][4] = a2 / a0
    }

    override fun onFlush() {
        state.forEach { perCh -> perCh.forEach { it.fill(0f) } }
    }

    override fun onReset() {
        onFlush()
        coeffs.forEach { it.fill(0f) }
        currentDb.fill(0f)
        targetDb.fill(0f)
    }
}

/**
 * 音量增益 + 软限幅，同样跑在播放器内部。
 * 档位 0..9 对应 0..+9 dB；增益变化做逐样本斜坡，切档不会有 zipper noise。
 */
class GainProcessor : BaseAudioProcessor() {

    private var channels = 2
    private var current = 1f
    private var envelope = 0f
    private var limiter = 1f

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != androidx.media3.common.C.ENCODING_PCM_16BIT) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        channels = inputAudioFormat.channelCount
        current = factorFor(ProcessorState.gainLevel)
        envelope = 0f
        limiter = 1f
        return inputAudioFormat
    }

    private fun factorFor(level: Int): Float = 10f.pow(level.coerceIn(0, 9) / 20f)

    override fun queueInput(inputBuffer: ByteBuffer) {
        val samples = inputBuffer.remaining() / 2
        if (samples <= 0) return
        val input = inputBuffer.order(ByteOrder.nativeOrder()).asShortBuffer()
        val out = replaceOutputBuffer(inputBuffer.remaining()).order(ByteOrder.nativeOrder()).asShortBuffer()

        val target = factorFor(ProcessorState.gainLevel)
        // 约 40ms 完成过渡（44.1k 下每样本 0.0006 的指数逼近）
        val ramp = 0.0006f
        val frames = samples / channels
        for (f in 0 until frames) {
            // 每帧取该帧的峰值用于限幅包络
            var peak = 0f
            for (ch in 0 until channels) {
                val v = abs(input.get(f * channels + ch) / 32768f)
                if (v > peak) peak = v
            }
            envelope = maxOf(peak, envelope * 0.9995f)
            val needed = if (envelope * target > 0.95f) 0.95f / (envelope * target) else 1f
            // 限幅器：迅速下压、缓慢恢复，避免削顶破音
            limiter = if (needed < limiter) needed else limiter + (1f - limiter) * 0.0002f
            current += (target - current) * ramp
            for (ch in 0 until channels) {
                val idx = f * channels + ch
                val v = input.get(idx) / 32768f * current * limiter
                out.put(idx, (v.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
            }
        }
        inputBuffer.position(inputBuffer.limit())
        out.position(samples)
    }

    override fun onFlush() {
        current = factorFor(ProcessorState.gainLevel)
        envelope = 0f
        limiter = 1f
    }

    override fun onReset() {
        onFlush()
    }
}
