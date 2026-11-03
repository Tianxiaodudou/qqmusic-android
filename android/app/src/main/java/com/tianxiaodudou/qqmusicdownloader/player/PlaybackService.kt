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
            session = MediaSession.Builder(this, p)
                .setSessionActivity(sessionActivity)
                .build()
            // 只做诊断，放在会话建好之后，失败也不影响播放
            PlayerFactory.logOffloadSupport(this)
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
