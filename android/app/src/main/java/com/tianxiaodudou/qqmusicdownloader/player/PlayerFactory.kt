package com.tianxiaodudou.qqmusicdownloader.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory

/**
 * 播放器构建（谷歌范例：由 MediaSessionService 在 onCreate 里创建播放器与会话）。
 * UI 不再持有 ExoPlayer。
 */
object PlayerFactory {

    fun build(ctx: Context): Pair<ExoPlayer, androidx.media3.datasource.cache.SimpleCache?> {
        val cache: androidx.media3.datasource.cache.SimpleCache? = null

        // 叶子数据源：多连接分段下载（母带这种大文件单连接跑不满带宽），小文件自动退回单连接
        val upstream = MultiConnDataSource.Factory(ctx)

        // 懒解析：MediaItem 存 qqmusic://track/<mid>，播放到那首才换直链
        val resolving = androidx.media3.datasource.ResolvingDataSource.Factory(upstream) { spec ->
            val mid = TrackResolver.midOf(spec.uri)
            val url = TrackResolver.resolve(mid) ?: throw java.io.IOException("拿不到播放地址：" + mid)
            spec.withUri(Uri.parse(url))
        }

        val cacheFactory = resolving

        val player = ExoPlayer.Builder(ctx)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setMediaSourceFactory(DefaultMediaSourceFactory(cacheFactory))
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(60_000, 240_000, 3_000, 12_000)
                    .setBackBuffer(30_000, true)
                    .setPrioritizeTimeOverSizeThresholds(true)
                    .build(),
            )
            .build()
        return player to cache
    }
}
