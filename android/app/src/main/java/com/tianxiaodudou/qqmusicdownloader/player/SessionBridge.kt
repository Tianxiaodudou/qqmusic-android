package com.tianxiaodudou.qqmusicdownloader.player

import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture

/**
 * UI ↔ 播放服务的桥梁：传输类命令（播放/暂停/上下首/seek/倍速）优先走 MediaController，
 * 由 PlaybackService 里的 MediaSession 处理；控制器还没连上时退回直接操作播放器，保证不卡手。
 *
 * 状态展示仍然读 PlayerController（同一份 ExoPlayer），避免 UI 层做两套状态源。
 */
object SessionBridge {

    private var controller: MediaController? = null
    private var future: ListenableFuture<MediaController>? = null
    private var connecting = false

    fun connect(ctx: Context) {
        if (controller?.isConnected == true || connecting) return
        connecting = true
        runCatching {
            val token = SessionToken(ctx, ComponentName(ctx, PlaybackService::class.java))
            val f = MediaController.Builder(ctx.applicationContext, token).buildAsync()
            future = f
            f.addListener(
                {
                    controller = runCatching { f.get() }.getOrNull()
                    connecting = false
                },
                ContextCompat.getMainExecutor(ctx),
            )
        }.onFailure { connecting = false }
    }

    fun release() {
        future?.let { runCatching { MediaController.releaseFuture(it) } }
        future = null
        controller = null
        connecting = false
    }

    private inline fun withController(fallback: () -> Unit, block: (MediaController) -> Unit) {
        val c = controller
        if (c != null && c.isConnected) {
            runCatching { block(c) }.onFailure { fallback() }
        } else {
            fallback()
        }
    }

    fun toggle() = withController({ PlayerController.toggle() }) {
        if (it.isPlaying) it.pause() else it.play()
    }

    fun play() = withController({ PlayerController.toggle() }) { it.play() }

    fun pause() = withController({ PlayerController.pause() }) { it.pause() }

    fun next() = withController({ PlayerController.next() }) { it.seekToNext() }

    fun prev() = withController({ PlayerController.prev() }) { it.seekToPrevious() }

    fun seekTo(ms: Long) = withController({ PlayerController.seekTo(ms) }) {
        it.seekTo(ms)
    }

    fun setSpeed(speed: Float) = withController({ PlayerController.changeSpeed(speed) }) {
        it.setPlaybackSpeed(speed)
    }
}
