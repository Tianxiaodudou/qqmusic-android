package com.tianxiaodudou.qqmusicdownloader.player

import android.content.Intent
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * 媒体会话服务：按谷歌范例在 onCreate 里创建 ExoPlayer + MediaSession 并持有它们。
 * UI 侧只通过 PlayerController 这层门面读写状态与命令，不再直接 new 播放器。
 */
class PlaybackService : MediaSessionService() {

    private var player: androidx.media3.exoplayer.ExoPlayer? = null
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        // 服务可能先于 Activity 启动：这里也初始化诊断日志，否则服务侧日志全部丢失
        com.tianxiaodudou.qqmusicdownloader.net.Diag.init(this)
        runCatching {
            val (p, _) = PlayerFactory.build(this)
            player = p
            val sessionActivity = android.app.PendingIntent.getActivity(
                this,
                0,
                Intent(this, com.tianxiaodudou.qqmusicdownloader.MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
            )
            p.addListener(object : androidx.media3.common.Player.Listener {
                override fun onAudioSessionIdChanged(audioSessionId: Int) {
                    AudioStats.sessionId = audioSessionId
                    AudioStats.routeName = runCatching {
                        val am = getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
                        when {
                            am.isBluetoothA2dpOn -> "蓝牙"
                            am.isWiredHeadsetOn -> "有线耳机"
                            am.isSpeakerphoneOn -> "扬声器"
                            else -> "默认输出"
                        }
                    }.getOrDefault("")
                    android.util.Log.i("QQAudio", "audioSession=" + audioSessionId)
                }
            })
            session = MediaSession.Builder(this, p)
                .setSessionActivity(sessionActivity)
                .build()
            // 只做诊断，放在会话建好之后，失败也不影响播放
            PlayerFactory.logOffloadSupport(this)
            probeOffloadSupport()
            // 播放器完全由服务持有；UI 通过 MediaController 观察这个会话
        }.onFailure {
            android.util.Log.e("PlaybackService", "初始化失败", it)
        }
        runCatching {
            if (com.tianxiaodudou.qqmusicdownloader.net.Settings.desktopLyrics(this)) {
                DesktopLyrics.setEnabled(this, true)
            }
        }
    }

    /** 探测 HAL 对 MP3/AAC/FLAC 的卸载支持，结果给状态卡片用。 */
    private fun probeOffloadSupport() {
        if (android.os.Build.VERSION.SDK_INT < 29) return
        val am = getSystemService(android.content.Context.AUDIO_SERVICE) as? android.media.AudioManager ?: return
        val attrs = android.media.AudioAttributes.Builder()
            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        fun supported(encoding: Int, rate: Int): Boolean = runCatching {
            val fmt = android.media.AudioFormat.Builder()
                .setEncoding(encoding)
                .setSampleRate(rate)
                .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_STEREO)
                .build()
            am.javaClass.getMethod(
                "isOffloadedPlaybackSupported",
                android.media.AudioFormat::class.java,
                android.media.AudioAttributes::class.java,
            ).invoke(am, fmt, attrs) as? Boolean ?: false
        }.getOrDefault(false)
        AudioStats.offloadMp3 = supported(android.media.AudioFormat.ENCODING_MP3, 44100)
        AudioStats.offloadAac = supported(android.media.AudioFormat.ENCODING_AAC_LC, 44100)
        // FLAC 常量 API 30+，直接用数值
        AudioStats.offloadFlac = supported(0x1500000, 44100)
        com.tianxiaodudou.qqmusicdownloader.net.Diag.log(
            "offload",
            "hal mp3=" + AudioStats.offloadMp3 + " aac=" + AudioStats.offloadAac + " flac=" + AudioStats.offloadFlac,
        )
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /** 供 PlayerController 接管（例如 Activity 重建时）。 */
    fun currentPlayer(): androidx.media3.exoplayer.ExoPlayer? = player

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!PlayerController.playing) stopSelf()
    }

    override fun onDestroy() {
        DesktopLyrics.setEnabled(this, false)
        LyricsRuntime.stop()
        runCatching { session?.release() }
        runCatching { player?.release() }
        session = null
        player = null

        instance = null
        super.onDestroy()
    }

    companion object {
        @Volatile
        var instance: PlaybackService? = null
            private set
    }
}
