package com.tianxiaodudou.qqmusicdownloader.player

import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player

/**
 * 给 MediaSession 用的播放器包装：
 * 我们一次只往 ExoPlayer 里放一首歌（队列由 PlayerController 自己管），
 * 所以原生 Player 的「上一首/下一首」命令不可用，通知栏按钮会缺、蓝牙按键也无效。
 * 这里把这两个命令补上，并转发到 PlayerController 的队列操作。
 */
internal class QueuePlayer(
    player: Player,
    private val onNext: () -> Unit,
    private val onPrevious: () -> Unit,
    private val hasNext: () -> Boolean,
    private val hasPrevious: () -> Boolean,
) : ForwardingPlayer(player) {

    override fun getAvailableCommands(): Player.Commands {
        val commands = super.getAvailableCommands().buildUpon()
        if (hasNext()) {
            commands.addAll(
                Player.COMMAND_SEEK_TO_NEXT,
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            )
        }
        if (hasPrevious()) {
            commands.addAll(
                Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            )
        }
        return commands.build()
    }

    override fun seekToNext() {
        onNext()
    }

    override fun seekToNextMediaItem() {
        onNext()
    }

    override fun seekToPrevious() {
        onPrevious()
    }

    override fun seekToPreviousMediaItem() {
        onPrevious()
    }
}
