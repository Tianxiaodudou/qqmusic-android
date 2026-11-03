package com.tianxiaodudou.qqmusicdownloader

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import com.tianxiaodudou.qqmusicdownloader.player.EqProcessor
import com.tianxiaodudou.qqmusicdownloader.player.GainProcessor
import com.tianxiaodudou.qqmusicdownloader.player.ProcessorState
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class DspProcessorTest {

    private val format = AudioProcessor.AudioFormat(44100, 2, C.ENCODING_PCM_16BIT)

    private fun tone(frames: Int, amp: Double = 0.2): ShortArray {
        val out = ShortArray(frames * 2)
        for (i in 0 until frames) {
            val v = (Math.sin(2 * Math.PI * 440.0 * i / 44100.0) * amp * 32767).toInt().toShort()
            out[i * 2] = v
            out[i * 2 + 1] = v
        }
        return out
    }

    private fun bufferOf(samples: ShortArray): ByteBuffer {
        val b = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.nativeOrder())
        samples.forEach { b.putShort(it) }
        b.flip()
        return b
    }

    private fun run(processor: AudioProcessor, samples: ShortArray): ShortArray {
        processor.configure(format)
        processor.queueInput(bufferOf(samples))
        val outBuf = processor.output
        val out = ShortArray(samples.size)
        val view = outBuf.order(ByteOrder.nativeOrder()).asShortBuffer()
        view.get(out)
        return out
    }

    private fun assertBypass(input: ShortArray, output: ShortArray) {
        for (i in input.indices) {
            val diff = Math.abs(input[i].toInt() - output[i].toInt())
            assertTrue("旁路时每个样本只允许 ±1 的量化误差，实际 $diff", diff <= 1)
        }
    }

    @Test
    fun eqBypassWhenPresetOff() {
        ProcessorState.eqPreset = 0
        val input = tone(512)
        val output = run(EqProcessor(), input)
        assertBypass(input, output)
    }

    @Test
    fun eqChangesSignalButStaysInRange() {
        ProcessorState.eqPreset = 6 // 重低音
        val input = tone(4096, 0.4)
        val output = run(EqProcessor(), input)
        var diff = 0
        for (i in input.indices) if (input[i] != output[i]) diff++
        assertTrue("均衡器应当改变低频信号", diff > 100)
        for (s in output) assertTrue("输出必须在 16-bit 范围内", s >= -32768 && s <= 32767)
    }

    @Test
    fun gainBypassAtZero() {
        ProcessorState.gainLevel = 0
        val input = tone(512, 0.1)
        val output = run(GainProcessor(), input)
        assertBypass(input, output)
    }

    @Test
    fun gainBoostsButLimits() {
        ProcessorState.gainLevel = 9
        val input = tone(8192, 0.8)
        val output = run(GainProcessor(), input)
        var louder = 0
        for (i in input.indices) if (Math.abs(output[i].toInt()) > Math.abs(input[i].toInt())) louder++
        assertTrue("增益应当放大信号", louder > input.size / 2)
        for (s in output) assertTrue("限幅后不能过载", s >= -32768 && s <= 32767)
    }
}
