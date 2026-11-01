package com.tianxiaodudou.qqmusicdownloader.player

import android.content.Intent
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * 媒体会话服务：把 PlayerController 里那个 ExoPlayer 的 MediaSession 交给系统，
 * 这样通知栏、锁屏、蓝牙车机、耳机按键都能控制播放，退到后台也不会被立刻回收。
 *
 * 播放器实例仍然由 PlayerController 持有（UI 直接读它的状态），这里只负责会话与前台服务。
 */
class PlaybackService : MediaSessionService() {

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        PlayerController.mediaSession

    /** 用户从最近任务里划掉应用：没在播就停掉服务，在播就让它继续（后台播放）。 */
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!PlayerController.playing) stopSelf()
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    companion object {
        @Volatile
        var instance: PlaybackService? = null
            private set
    }
}
