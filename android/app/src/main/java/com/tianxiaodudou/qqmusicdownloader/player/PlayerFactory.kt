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

        // 硬件音频卸载（audio offload）：把解码后的 PCM 直接交给 DSP/音频 HAL 的硬件通道，
        // CPU 只负责喂数据、不参与混音，抗系统调度抖动最好，也最省电。
        // 设备/编码不支持时框架会自动回退到普通 AudioTrack 路径。
        val renderers = object : androidx.media3.exoplayer.DefaultRenderersFactory(ctx) {
            override fun buildAudioSink(
                context: android.content.Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): androidx.media3.exoplayer.audio.AudioSink =
                androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setAudioOffloadSupportProvider(
                        androidx.media3.exoplayer.audio.DefaultAudioOffloadSupportProvider(context),
                    )
                    .setAudioTrackBufferSizeProvider { minBufferSize, _, _, _, _, _, _ ->
                        minBufferSize * 4
                    }
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
        // 允许使用硬件卸载通道（不强制：设备/编码不支持时框架自动回退普通路径）
        runCatching {
            player.trackSelectionParameters = player.trackSelectionParameters
                .buildUpon()
                .setAudioOffloadPreferences(
                    androidx.media3.common.TrackSelectionParameters.AudioOffloadPreferences.Builder()
                        .setAudioOffloadMode(
                            androidx.media3.common.TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED,
                        )
                        .build(),
                )
                .build()
        }
        return player to cache
    }
}
