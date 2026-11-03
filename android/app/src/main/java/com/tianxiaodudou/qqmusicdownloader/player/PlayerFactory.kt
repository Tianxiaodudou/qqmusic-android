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
        val cache = runCatching {
            androidx.media3.datasource.cache.SimpleCache(
                java.io.File(ctx.cacheDir, "media_cache"),
                androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor(1500L * 1024 * 1024),
            )
        }.getOrNull()

        val upstream = androidx.media3.datasource.DefaultHttpDataSource.Factory()
            .setUserAgent(com.tianxiaodudou.qqmusicdownloader.net.QqClient.userAgent())
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
            .setAllowCrossProtocolRedirects(true)

        // 懒解析：MediaItem 存 qqmusic://track/<mid>，播放到那首才换直链
        val resolving = androidx.media3.datasource.ResolvingDataSource.Factory(upstream) { spec ->
            val mid = TrackResolver.midOf(spec.uri)
            val url = TrackResolver.resolve(mid) ?: throw java.io.IOException("拿不到播放地址：" + mid)
            spec.withUri(Uri.parse(url))
        }

        val cacheFactory = androidx.media3.datasource.cache.CacheDataSource.Factory()
            .setUpstreamDataSourceFactory(resolving)
        if (cache != null) {
            cacheFactory.setCache(cache)
            cacheFactory.setCacheWriteDataSinkFactory(
                androidx.media3.datasource.cache.CacheDataSink.Factory()
                    .setCache(cache)
                    .setFragmentSize(4L * 1024 * 1024),
            )
        }

        // 混音线程被系统动画/亮屏抢调度时，小缓冲会直接 underrun（咔啦声）。
        // 把 AudioTrack 缓冲放大到默认的 4 倍，用一点延迟换稳定。
        val renderers = object : androidx.media3.exoplayer.DefaultRenderersFactory(ctx) {
            override fun buildAudioSink(
                context: android.content.Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): androidx.media3.exoplayer.audio.AudioSink =
                androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setAudioTrackBufferSizeProvider { minBufferSize, _, _, _, _, _, _ ->
                        minBufferSize * 4
                    }
                    // 均衡器与音量增益放进播放器内部（解码后、AudioTrack 之前）：
                    // 不触碰音频会话，就不会把系统拉到「混音+效果链」上
                    .setAudioProcessorChain(
                        androidx.media3.exoplayer.audio.DefaultAudioSink.DefaultAudioProcessorChain(
                            EqProcessor(),
                            GainProcessor(),
                        )
                    )
                    .build()
        }

        val player = ExoPlayer.Builder(ctx, renderers)
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
