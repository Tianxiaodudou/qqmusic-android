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

        // 叶子数据源：默认单连接；开启「多连接下载（实验）」后对大文件走 4 段并行，
        // 内部任何失败都会自动退回单连接（MultiConnDataSource 自己兜底）
        val upstream: androidx.media3.datasource.DataSource.Factory =
            MultiConnDataSource.Factory(ctx)

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
                    .apply {
                        if (com.tianxiaodudou.qqmusicdownloader.net.Settings.audioOffload(context)) {
                            setAudioOffloadSupportProvider(
                                androidx.media3.exoplayer.audio.DefaultAudioOffloadSupportProvider(context),
                            )
                        }
                    }
                    .setExperimentalAudioOffloadListener(object : androidx.media3.exoplayer.ExoPlayer.AudioOffloadListener {
                        override fun onOffloadedPlayback(isOffloaded: Boolean) {
                            AudioStats.offload = isOffloaded
                            android.util.Log.i("QQAudio", "offload=" + isOffloaded)
                        }

                        override fun onSleepingForOffloadChanged(isSleeping: Boolean) {
                            android.util.Log.i("QQAudio", "offloadSleeping=" + isSleeping)
                        }
                    })
                    // 系统级效果链（MIUI 的 Dolby/MiSound）会把我们的轨拉进混音线程，
                    // 相机/桌面模糊造成的调度抖动会直接把缓冲吃空，这里给 12 倍余量。
                    .setAudioTrackBufferSizeProvider { minBufferSize, _, _, channels, sampleRate, encoding, _ ->
                        val mult = AudioTuning.pcmTier(ctx).mult
                        val bytes = minBufferSize * mult
                        // 估个毫秒数给状态卡片用
                        val bits = if (encoding == android.media.AudioFormat.ENCODING_PCM_FLOAT) 32 else 16
                        val ms = if (sampleRate > 0 && channels > 0) {
                            (bytes * 1000.0 / (sampleRate * channels * bits / 8.0)).toInt()
                        } else 0
                        AudioStats.onBuffer(bytes, ms)
                        bytes
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
                    .setBufferDurationsMs(
                        AudioTuning.loadTier(ctx).minBufferMs,
                        AudioTuning.loadTier(ctx).maxBufferMs,
                        AudioTuning.loadTier(ctx).forPlaybackMs,
                        AudioTuning.loadTier(ctx).afterRebufferMs,
                    )
                    .setBackBuffer(30_000, true)
                    .setPrioritizeTimeOverSizeThresholds(true)
                    .build(),
            )
            .build()
        runCatching { player.addAnalyticsListener(GlitchMonitor(ctx)) }
        // 允许使用硬件卸载通道（不强制：设备/编码不支持时框架自动回退普通路径）
        if (com.tianxiaodudou.qqmusicdownloader.net.Settings.audioOffload(ctx)) runCatching {
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

    /**
     * 打印设备对「压缩音频直送 DSP/HAL」的支持情况，便于用 logcat 确认是否真的走了硬件卸载。
     * 只有 API 29+ 才有 isOffloadedPlaybackSupported。
     */
    private fun enc(encoding: Int, rate: Int): android.media.AudioFormat =
        android.media.AudioFormat.Builder()
            .setEncoding(encoding)
            .setSampleRate(rate)
            .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_STEREO)
            .build()

    /** 只做诊断：任何异常都必须吞掉，绝不能影响播放器创建。 */
    fun logOffloadSupport(ctx: android.content.Context) {
        runCatching { logOffloadSupportInternal(ctx) }
            .onFailure { android.util.Log.w("AudioOffload", "能力探测失败：" + it) }
    }

    private fun logOffloadSupportInternal(ctx: android.content.Context) {
        if (android.os.Build.VERSION.SDK_INT < 29) {
            android.util.Log.i("AudioOffload", "API " + android.os.Build.VERSION.SDK_INT + " 不支持卸载通道")
            return
        }
        val am = ctx.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
        val attrs = android.media.AudioAttributes.Builder()
            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        // FLAC 的编码常量从 API 30 才有，这里直接用数值，避免版本差异
        val flac = 0x1500000 // AudioFormat.ENCODING_FLAC
        val formats = buildList {
            runCatching { add("MP3 44.1k" to enc(android.media.AudioFormat.ENCODING_MP3, 44100)) }
            runCatching { add("AAC 44.1k" to enc(android.media.AudioFormat.ENCODING_AAC_LC, 44100)) }
            runCatching { add("FLAC 44.1k" to enc(flac, 44100)) }
            runCatching { add("FLAC 96k" to enc(flac, 96000)) }
        }
        formats.forEach { (name, fmt) ->
            val ok = runCatching {
                am.javaClass.getMethod(
                    "isOffloadedPlaybackSupported",
                    android.media.AudioFormat::class.java,
                    android.media.AudioAttributes::class.java,
                ).invoke(am, fmt, attrs) as? Boolean ?: false
            }.getOrDefault(false)
            android.util.Log.i("AudioOffload", name + " -> " + if (ok) "支持卸载" else "不支持（会走 CPU 解码）")
        }
    }
}
