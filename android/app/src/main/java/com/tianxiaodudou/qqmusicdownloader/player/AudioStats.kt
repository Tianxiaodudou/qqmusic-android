package com.tianxiaodudou.qqmusicdownloader.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 音频链路实时状态（给设置里的状态卡片用）。
 * 由播放器监听器写入，Compose 直接读，改动即重组。
 */
object AudioStats {
    /** 是否真的走了硬件卸载（压缩帧直送 DSP）。 */
    var offload by mutableStateOf(false)
    /** 解码后的 PCM 格式。 */
    var sampleRate by mutableStateOf(0)
    var channels by mutableStateOf(0)
    /** AudioSink.AudioTrackConfig.encoding（2 = 16bit，4 = float）。 */
    var encoding by mutableStateOf(0)
    /** AudioTrack 实际缓冲字节数与毫秒数。 */
    var bufferBytes by mutableStateOf(0)
    var bufferMs by mutableStateOf(0)
    /** 播放中的音频码率（kbps，按解码格式与声道估算）。 */
    var bitrateKbps by mutableStateOf(0)
    /** 当前曲目的编码（例如 audio/mp4a-latm、audio/vorbis、audio/flac）。 */
    var codecMime by mutableStateOf("")

    /** 设备 HAL 是否支持这些编码的硬件卸载（启动时探测一次）。 */
    var offloadMp3 by mutableStateOf(false)
    var offloadAac by mutableStateOf(false)
    var offloadFlac by mutableStateOf(false)

    /** 当前为什么不走卸载，给用户看的短说明。 */
    fun offloadHint(): String {
        if (offload) return "已激活"
        val m = codecMime.lowercase()
        return when {
            m.contains("vorbis") || m.contains("opus") ->
                "当前曲目是 OGG/Vorbis，Android 的硬件卸载不支持该编码（可把音质上限设为「高品质」，走 MP3）"
            m.contains("flac") && !offloadFlac -> "设备 HAL 不支持 FLAC 卸载"
            m.contains("mpeg") && !offloadMp3 -> "设备 HAL 不支持 MP3 卸载"
            m.contains("mp4a") && !offloadAac -> "设备 HAL 不支持 AAC 卸载"
            m.isEmpty() -> "未开始播放"
            else -> "会话上可能挂了系统音效（Dolby/MiSound），有音效时框架不会走卸载"
        }
    }

    /** 最近一次 underrun 时间戳（毫秒）。 */
    var lastUnderrunAt by mutableStateOf(0L)
    var underrunCount by mutableStateOf(0)

    fun onFormat(sampleRate: Int, channels: Int, encoding: Int) {
        this.sampleRate = sampleRate
        this.channels = channels
        this.encoding = encoding
        // 16bit 立体声 44.1k ≈ 1411kbps；这里只作展示用途
        val bits = if (encoding == 4) 32 else 16
        bitrateKbps = sampleRate * channels * bits / 1000
    }

    fun onBuffer(bytes: Int, ms: Int) {
        bufferBytes = bytes
        bufferMs = ms
    }

    fun onUnderrun() {
        underrunCount += 1
        lastUnderrunAt = System.currentTimeMillis()
    }
}
