package com.tianxiaodudou.qqmusicdownloader.player

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import com.tianxiaodudou.qqmusicdownloader.net.Diag
import com.tianxiaodudou.qqmusicdownloader.net.Settings

/**
 * 卡顿探测：挂在播放器的 AnalyticsListener 上，把“可能是咔哒声”的事件按时间顺序记到 diag.log。
 *
 * 预想的成因都会带上现场信息：
 *  - 音频 underrun（系统音频队列被吃空）→ 系统合成/相机/CPU 抢占
 *  - 解码器/音频轨道错误
 *  - 缓冲中（BUFFERING）反复进入 → 网络跟不上
 *  - 播放位置跳变 → seek 或重解析
 *  - 声卡/输出设备切换、音频会话重建 → 蓝牙/相机抢占
 */
@UnstableApi
class GlitchMonitor(private val context: Context) : AnalyticsListener {

    private val enabled: Boolean get() = Settings.glitchLog(context)

    private fun log(msg: String) {
        if (!enabled) return
        Diag.log("glitch", msg + " | top=" + topActivity() + " offload=" + AudioStats.offload +
            " buf=" + AudioStats.bufferMs + "ms fmt=" + AudioStats.sampleRate + "/" + AudioStats.channels)
    }

    private fun captureSystemSnapshot(reason: String) {
        if (!RootShell.available) return
        val out = runCatching {
            val dir = context.getExternalFilesDir(null) ?: context.filesDir
            java.io.File(dir, "diag-root.log")
        }.getOrNull() ?: return
        runCatching { out.appendText("\n===== " + reason + " @ " + System.currentTimeMillis() + " =====\n") }
        RootShell.capture("logcat -d -t 400 -v threadtime", out)
        RootShell.capture("dumpsys media.audio_flinger", out)
    }

    override fun onAudioUnderrun(
        eventTime: AnalyticsListener.EventTime,
        bufferSize: Int,
        bufferSizeMs: Long,
        elapsedSinceLastFeedMs: Long,
    ) {
        AudioStats.onUnderrun()
        captureSystemSnapshot("UNDERRUN")
        log("UNDERRUN bufferSize=" + bufferSize + " (" + bufferSizeMs + "ms) elapsedSinceLastFeed=" + elapsedSinceLastFeedMs)
    }

    override fun onAudioSinkError(eventTime: AnalyticsListener.EventTime, audioSinkError: Exception) {
        log("AUDIO_SINK_ERROR " + audioSinkError)
    }

    override fun onPlayerError(eventTime: AnalyticsListener.EventTime, error: androidx.media3.common.PlaybackException) {
        log("PLAYER_ERROR " + error.errorCodeName + " " + error.message)
    }

    override fun onAudioInputFormatChanged(
        eventTime: AnalyticsListener.EventTime,
        format: androidx.media3.common.Format,
        decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?,
    ) {
        AudioStats.onFormat(format.sampleRate, format.channelCount, if (format.pcmEncoding == 4) 4 else 2)
        AudioStats.codecMime = format.sampleMimeType.orEmpty()
        log("FORMAT " + format.sampleMimeType + " " + format.sampleRate + "Hz ch=" + format.channelCount + " codec=" + format.codecs)
    }

    override fun onPlaybackStateChanged(eventTime: AnalyticsListener.EventTime, state: Int) {
        when (state) {
            ExoPlayer.STATE_BUFFERING -> log("BUFFERING")
            ExoPlayer.STATE_READY -> log("READY")
            ExoPlayer.STATE_ENDED -> log("ENDED")
        }
    }

    override fun onAudioAttributesChanged(eventTime: AnalyticsListener.EventTime, audioAttributes: AudioAttributes) {
        log("AUDIO_ATTRS usage=" + audioAttributes.usage)
    }

    private fun topActivity(): String = runCatching {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        @Suppress("DEPRECATION")
        am.getRunningTasks(1).firstOrNull()?.topActivity?.packageName ?: ""
    }.getOrDefault("")
}
