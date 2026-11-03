package com.tianxiaodudou.qqmusicdownloader.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * 均衡器与音量增益的运行时参数（UI 改这里，播放线程下一个 buffer 就读到）。
 * 两个处理器常驻在播放器内部的处理链上，不增删处理器，所以改档即时生效、不会重配 AudioSink。
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

/** 仅处理单/双声道 16-bit PCM；其它格式返回 NOT_SET 让处理器整体旁路。 */
private fun supported(format: AudioProcessor.AudioFormat): Boolean =
    format.encoding == C.ENCODING_PCM_16BIT && format.channelCount in 1..2

/**
 * 7 段 biquad 均衡器，跑在播放器内部（解码后、写入 AudioTrack 前）。
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
        if (!supported(inputAudioFormat)) return AudioProcessor.AudioFormat.NOT_SET
        sampleRate = inputAudioFormat.sampleRate
        channels = inputAudioFormat.channelCount
        currentDb.fill(0f)
        targetDb.fill(0f)
        coeffs.forEach { it.fill(0f) }
        state.forEach { perCh -> perCh.forEach { it.fill(0f) } }
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return
        val size = inputBuffer.remaining()
        val input = inputBuffer.order(ByteOrder.nativeOrder())
        val output = replaceOutputBuffer(size).order(ByteOrder.nativeOrder())

        // 目标曲线 + 每 buffer 平滑逼近，切预设是渐变而不是阶跃
        val target = EqPresets.curve(ProcessorState.eqPreset)
        for (i in targetDb.indices) {
            targetDb[i] = target.getOrElse(i) { 0f }
            val diff = targetDb[i] - currentDb[i]
            if (abs(diff) > 0.02f) currentDb[i] += diff * 0.2f else currentDb[i] = targetDb[i]
            computeCoeffs(i)
        }

        val frames = size / 2 / channels
        for (f in 0 until frames) {
            for (ch in 0 until channels) {
                var v = input.short / 32768f
                for (b in freqs.indices) {
                    val c = coeffs[b]
                    if (c[0] == 0f) continue
                    val s = state[b][ch]
                    val y = c[0] * v + c[1] * s[0] + c[2] * s[1] - c[3] * s[2] - c[4] * s[3]
                    s[1] = s[0]; s[0] = v; s[3] = s[2]; s[2] = y
                    v = y
                }
                output.putShort((v.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
            }
        }
        output.flip()
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
 * 音量增益 + 软限幅，同一条播放器内部处理链。
 * 档位 0..9 对应 0..+9 dB；增益变化做逐样本斜坡，切档没有 zipper noise。
 */
class GainProcessor : BaseAudioProcessor() {

    private var channels = 2
    private var current = 1f
    private var envelope = 0f
    private var limiter = 1f

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (!supported(inputAudioFormat)) return AudioProcessor.AudioFormat.NOT_SET
        channels = inputAudioFormat.channelCount
        current = factorFor(ProcessorState.gainLevel)
        envelope = 0f
        limiter = 1f
        return inputAudioFormat
    }

    private fun factorFor(level: Int): Float = 10f.pow(level.coerceIn(0, 9) / 20f)

    private val frame = FloatArray(2)

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return
        val size = inputBuffer.remaining()
        val input = inputBuffer.order(ByteOrder.nativeOrder())
        val output = replaceOutputBuffer(size).order(ByteOrder.nativeOrder())

        val target = factorFor(ProcessorState.gainLevel)
        val ramp = 0.0006f
        val frames = size / 2 / channels
        for (f in 0 until frames) {
            var peak = 0f
            for (ch in 0 until channels) {
                frame[ch] = input.short / 32768f
                val a = abs(frame[ch])
                if (a > peak) peak = a
            }
            envelope = maxOf(peak, envelope * 0.9995f)
            val needed = if (envelope * target > 0.95f) 0.95f / (envelope * target) else 1f
            limiter = if (needed < limiter) needed else limiter + (1f - limiter) * 0.0002f
            current += (target - current) * ramp
            for (ch in 0 until channels) {
                val v = (frame[ch] * current * limiter).coerceIn(-1f, 1f)
                output.putShort((v * 32767f).toInt().toShort())
            }
        }
        output.flip()
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
