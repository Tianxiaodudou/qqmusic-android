package com.tianxiaodudou.qqmusicdownloader.player

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.tianxiaodudou.qqmusicdownloader.net.Extras
import com.tianxiaodudou.qqmusicdownloader.net.Lossless
import com.tianxiaodudou.qqmusicdownloader.net.MusicApi
import com.tianxiaodudou.qqmusicdownloader.net.ResumeStore
import com.tianxiaodudou.qqmusicdownloader.net.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random

/**
 * 全局播放器：队列、音质解析、播放模式、倍速、睡眠定时。
 * Compose 直接读这里的 state，弹层/摇一摇之类的 UI 不持有播放器实例。
 */
object PlayerController {

    enum class Mode { LIST, SINGLE, SHUFFLE }

    private const val PREFS = "player"
    private const val K_MODE = "mode"

    private var exo: ExoPlayer? = null
    private var appContext: Context? = null
    private var posAt = 0L
    private var posRealtime = 0L
    private var scope: CoroutineScope? = null
    private var loadJob: Job? = null

    var queue by mutableStateOf<List<MusicApi.Song>>(emptyList())
        private set
    var index by mutableStateOf(-1)
        private set
    var playing by mutableStateOf(false)
        private set
    var loading by mutableStateOf(false)
        private set
    var positionMs by mutableStateOf(0L)
        private set
    var durationMs by mutableStateOf(0L)
        private set
    var bufferedMs by mutableStateOf(0L)
        private set
    /** 真正在播的档位（上限是设置里选的，账号无权限会自动降级）。 */
    var actualQuality by mutableStateOf("")
        private set
    var mode by mutableStateOf(Mode.LIST)
        private set
    var speed by mutableStateOf(1f)
        private set
    var sourceLabel by mutableStateOf("")
        private set
    var sourcePath by mutableStateOf("")
        private set
    var error by mutableStateOf<String?>(null)
    var sleepEndAt by mutableStateOf(0L)
        private set
    var sleepAfterCurrent by mutableStateOf(false)
        private set

    private var retriedQuality = false
    private var forcedQuality: Settings.Quality? = null
    private var mediaCache: androidx.media3.datasource.cache.SimpleCache? = null

    /** 交给 PlaybackService / 系统媒体控制用的会话。 */
    var mediaSession: androidx.media3.session.MediaSession? = null
        private set

    private val sessionContext: Context? get() = appContext
    /** 卡顿降档计数与计时：同一首歌最多降两档，避免来回跳。 */
    private var stallDowngrades = 0
    private var bufferingSince = 0L
    private var loadedSong: MusicApi.Song? = null

    private data class CachedUrl(
        val url: String,
        val label: String,
        val quality: String,
        val at: Long,
        val code: String = "",
    )

    /** 音质代码 -> 档位序号（用于「不能超过上限」的判断）。 */
    private fun codeOrdinal(code: String): Int =
        Settings.Quality.entries.firstOrNull { it.code == code }?.ordinal ?: 0

    /** 下一首/上一首的直链预取缓存，10 分钟内有效。 */
    private val urlCache = java.util.concurrent.ConcurrentHashMap<String, CachedUrl>()

    val current: MusicApi.Song? get() = queue.getOrNull(index)

    fun attach(context: Context) {
        if (exo != null) return
        val ctx = context.applicationContext
        appContext = ctx
        TrackResolver.attach(ctx)
        // 播放缓存：缓冲过的字节落盘（LRU 1.5GB），重听同一首/回拖不再重新下载，
        // 高音质下卡顿主要靠「缓存 + 更大的缓冲窗口」缓解（ExoPlayer 本身是单连接拉流）。
        val cache = runCatching {
            androidx.media3.datasource.cache.SimpleCache(
                java.io.File(ctx.cacheDir, "media_cache"),
                androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor(1500L * 1024 * 1024),
            )
        }.getOrNull()
        mediaCache = cache
        val upstream = androidx.media3.datasource.DefaultHttpDataSource.Factory()
            .setUserAgent(com.tianxiaodudou.qqmusicdownloader.net.QqClient.userAgent())
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
            .setAllowCrossProtocolRedirects(true)
        // 懒解析上游：MediaItem 里存的是 qqmusic://track/<mid>，播放到哪一首才去换直链，
        // 这样整个队列能一次性交给 ExoPlayer，系统侧才有原生的上一首/下一首
        val resolving = androidx.media3.datasource.ResolvingDataSource.Factory(upstream) { spec ->
            val mid = TrackResolver.midOf(spec.uri)
            val url = TrackResolver.resolve(mid)
                ?: throw java.io.IOException("拿不到播放地址：" + mid)
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
        val p = ExoPlayer.Builder(ctx)
            .setAudioAttributes(
                androidx.media3.common.AudioAttributes.Builder()
                    .setUsage(androidx.media3.common.C.USAGE_MEDIA)
                    .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setMediaSourceFactory(
                androidx.media3.exoplayer.source.DefaultMediaSourceFactory(cacheFactory),
            )
            .setLoadControl(
                androidx.media3.exoplayer.DefaultLoadControl.Builder()
                    // 维持播放至少 60s、最多囤 240s；起播 3s、卡顿后补 12s 再继续。
                    // 用几秒起播延迟换稳定性，配合下面的整首多连接预下，效果更明显。
                    .setBufferDurationsMs(60_000, 240_000, 3_000, 12_000)
                    .setBackBuffer(30_000, true)
                    .setPrioritizeTimeOverSizeThresholds(true)
                    .build(),
            )
            .build()
        exo = p
        scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
        speed = Settings.speed(ctx)
        p.setPlaybackSpeed(speed)
        mode = runCatching {
            Mode.valueOf(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(K_MODE, "LIST") ?: "LIST")
        }.getOrDefault(Mode.LIST)

        p.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
                if (isPlaying) {
                    retriedQuality = false
                    // 已经因为卡顿降过档就别再抬回去，否则会反复卡
                    if (stallDowngrades == 0) forcedQuality = null
                }
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) onEnded()
                if (state == Player.STATE_READY) durationMs = p.duration.coerceAtLeast(0L)
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val mid = mediaItem?.mediaId ?: return
                val idx = queue.indexOfFirst { it.mid == mid }
                if (idx < 0) return
                loadedSong?.let { prev ->
                    if (prev.mid != mid) appContext?.let { c -> ResumeStore.save(c, prev.mid, positionMs, durationMs) }
                }
                index = idx
                val song = queue[idx]
                loadedSong = song
                durationMs = song.interval * 1000L
                actualQuality = TrackResolver.qualityOf(mid)
                appContext?.let { c -> positionMs = ResumeStore.get(c, mid) }
                prefetchAround()
            }

            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                AudioFx.attach(ctx, audioSessionId, Settings.eqPreset(ctx))
            }

            override fun onPlayerError(e: PlaybackException) {
                val song = current
                // 失败先换换 CDN / 替换音源再来一次，仍不行才报错
                if (song != null && !retriedQuality) {
                    retriedQuality = true
                    // 高音质源有问题时降一档重来，避免卡在同一个坏文件上
                    forcedQuality = Settings.Quality.HIGH
                    runCatching { if (sourcePath.isNotEmpty()) java.io.File(sourcePath).delete() }
                    TrackResolver.clear()
                    applyPlaylist(index)
                    return
                }
                loading = false
                error = "播放出错：" + (e.message ?: "未知错误")
            }
        })
        AudioFx.attach(ctx, p.audioSessionId, Settings.eqPreset(ctx))

        // 歌词运行时（蓝牙歌词 / 桌面歌词都要用）
        runCatching { LyricsRuntime.start() }

        // 系统接管：播放器留在应用内（队列在这里），会话由 PlaybackService 创建并持有，
        // 符合 Media3 的 MediaSessionService 生命周期（服务 onCreate 建会话，onGetSession 返回它）
        runCatching { ctx.startService(android.content.Intent(ctx, PlaybackService::class.java)) }
        ensureSession()

        LyricsRuntime.onLineChanged = { line ->
            val ctx2 = appContext
            if (ctx2 != null && Settings.btLyrics(ctx2)) {
                btTitle = line
                val pl = exo
                val idx = pl?.currentMediaItemIndex ?: -1
                val song2 = current
                if (pl != null && idx >= 0 && song2 != null) {
                    runCatching {
                        pl.replaceMediaItem(idx, mediaItemOf(song2, pl.currentMediaItem?.localConfiguration?.uri ?: return@runCatching))
                    }
                }
            } else {
                btTitle = ""
            }
        }

        scope?.launch {
            var lastSavedSec = -1L
            while (isActive) {
                val pl = exo
                if (pl != null) {
                    positionMs = pl.currentPosition.coerceAtLeast(0L)
                    posAt = positionMs
                    posRealtime = SystemClock.elapsedRealtime()
                    bufferedMs = pl.bufferedPosition.coerceAtLeast(0L)
                    val d = pl.duration
                    if (d > 0) durationMs = d
                    val song = current
                    if (pl.isPlaying && song != null) {
                        val sec = positionMs / 5000
                        if (sec != lastSavedSec) {
                            lastSavedSec = sec
                            appContext?.let { ResumeStore.save(it, song.mid, positionMs, durationMs) }
                        }
                    }
                }
                // 缓冲卡住超过 6 秒就自动降一档音质重来（高码率网络跟不上时最有效）
                val pl2 = exo
                val now = SystemClock.elapsedRealtime()
                if (pl2 != null && pl2.playbackState == Player.STATE_BUFFERING && pl2.playWhenReady) {
                    if (bufferingSince == 0L) bufferingSince = now
                    if (now - bufferingSince > 6_000) {
                        bufferingSince = 0
                        downgradeForStall()
                    }
                } else {
                    bufferingSince = 0
                }
                val end = sleepEndAt
                if (end > 0 && System.currentTimeMillis() >= end) {
                    sleepEndAt = 0
                    pause()
                }
                delay(80)
            }
        }
    }

    fun play(list: List<MusicApi.Song>, at: Int) {
        if (at !in list.indices) return
        queue = list
        index = at
        applyPlaylist(at)
    }

    fun playAt(at: Int) {
        if (at !in queue.indices) return
        index = at
        val p = exo
        if (p != null && p.mediaItemCount > 0) {
            p.seekTo(at, 0L)
            p.play()
        } else {
            applyPlaylist(at)
        }
    }

    /**
     * 把整个队列做成 ExoPlayer 的播放列表（每首一个 MediaItem，uri 是 qqmusic://track/<mid>，
     * 直链由 ResolvingDataSource 懒解析）。这样系统拿到的就是标准 Player：
     * 通知栏/锁屏/蓝牙的上一首、下一首都由框架自己处理，不需要包装或自定义按钮。
     */
    private fun applyPlaylist(startAt: Int) {
        val p = exo ?: return
        loading = true
        error = null
        val items = queue.map { mediaItemFor(it) }
        runCatching {
            p.setMediaItems(items, startAt.coerceIn(0, (items.size - 1).coerceAtLeast(0)), 0L)
            p.prepare()
            p.play()
        }.onFailure { error = "播放失败：" + (it.message ?: "") }
        loading = false
        val song = queue.getOrNull(startAt) ?: return
        loadedSong = song
        durationMs = song.interval * 1000L
        actualQuality = TrackResolver.qualityOf(song.mid)
        prefetchAround()
    }

    private fun mediaItemFor(song: MusicApi.Song): MediaItem {
        val artwork = com.tianxiaodudou.qqmusicdownloader.ui.player.coverUrl(song.albumMid, 500)
        val metadata = androidx.media3.common.MediaMetadata.Builder()
            .setTitle(song.name)
            .setArtist(song.singers)
            .setAlbumTitle(song.album)
            .apply { if (artwork.isNotEmpty()) setArtworkUri(Uri.parse(artwork)) }
            .build()
        return MediaItem.Builder()
            .setMediaId(song.mid)
            .setUri(TrackResolver.uriOf(song.mid))
            .setMediaMetadata(metadata)
            .build()
    }

    /** 追加到播放队列；已经在队列里的不重复加。队列原本为空时立即开始播。 */
    fun enqueue(songs: List<MusicApi.Song>) {
        if (songs.isEmpty()) return
        val known = queue.mapTo(HashSet()) { it.mid }
        val add = songs.filter { known.add(it.mid) }
        if (add.isEmpty()) return
        val startEmpty = queue.isEmpty()
        queue = queue + add
        if (startEmpty) {
            index = 0
            applyPlaylist(0)
        } else {
            // 队列变长：把新增的补到播放列表尾部，系统侧立刻能看到新的下一首
            runCatching { exo?.addMediaItems(queue.drop(exo?.mediaItemCount ?: 0).map { mediaItemFor(it) }) }
        }
    }

    fun toggle() {
        val p = exo ?: return
        if (current == null) return
        // 解析音源期间不要重复触发 load，否则会把正在进行的解析取消掉，越点越慢
        if (loading) return
        if (p.isPlaying) p.pause() else {
            if (p.playbackState == Player.STATE_IDLE) applyPlaylist(index) else p.play()
        }
    }

    fun pause() {
        val song = current
        if (song != null) appContext?.let { ResumeStore.save(it, song.mid, positionMs, durationMs) }
        exo?.pause()
    }

    fun next(auto: Boolean = false) {
        if (queue.isEmpty()) return
        if (auto && mode == Mode.SINGLE) {
            exo?.seekTo(0)
            exo?.play()
            return
        }
        val target = when {
            mode == Mode.SHUFFLE && queue.size > 1 -> {
                var n = index
                while (n == index) n = Random.nextInt(queue.size)
                n
            }
            index + 1 < queue.size -> index + 1
            else -> 0
        }
        index = target
        val pl = exo
        if (pl != null && pl.mediaItemCount == queue.size) {
            pl.seekTo(target, 0L)
            pl.play()
        } else {
            applyPlaylist(target)
        }
    }

    /** 应用内「上一首」：3 秒内回上一首，超过 3 秒先回到本曲开头（常见播放器行为）。 */
    fun prev() = prevInternal(restartFirst = true)

    /** 系统侧「上一首」（耳机/车机/锁屏）：永远切到上一首，不重头播。 */
    fun prevTrack() = prevInternal(restartFirst = false)

    private fun prevInternal(restartFirst: Boolean) {
        if (queue.isEmpty()) return
        val p = exo
        if (restartFirst && p != null && p.currentPosition > 3000) {
            p.seekTo(0)
            positionMs = 0
            return
        }
        val target = if (index > 0) index - 1 else queue.lastIndex
        index = target
        if (p != null && p.mediaItemCount == queue.size) {
            p.seekTo(target, 0L)
            p.play()
        } else {
            applyPlaylist(target)
        }
    }

    fun seekTo(ms: Long) {
        val p = exo ?: return
        val target = ms.coerceIn(0, durationMs.coerceAtLeast(1))
        p.seekTo(target)
        positionMs = target
        posAt = target
        posRealtime = SystemClock.elapsedRealtime()
    }

    /**
     * 帧级的播放位置：在最近一次采样（80ms 一跳）上按真实流逝时间外推，
     * 避免逐字歌词比声音慢半拍、一跳一跳地前进。
     */
    fun positionNow(): Long {
        if (!playing) return positionMs
        val elapsed = SystemClock.elapsedRealtime() - posRealtime
        if (elapsed <= 0) return positionMs
        val ahead = (elapsed * speed).toLong()
        val pos = posAt + ahead
        return if (durationMs > 0) pos.coerceIn(0L, durationMs) else pos
    }

    fun changeMode(m: Mode) {
        mode = m
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()?.putString(K_MODE, m.name)?.apply()
    }

    fun cycleMode() {
        changeMode(
            when (mode) {
                Mode.LIST -> Mode.SINGLE
                Mode.SINGLE -> Mode.SHUFFLE
                Mode.SHUFFLE -> Mode.LIST
            }
        )
    }

    fun changeSpeed(v: Float) {
        speed = v.coerceIn(0.5f, 2f)
        exo?.setPlaybackSpeed(speed)
        appContext?.let { Settings.setSpeed(it, speed) }
    }

    /**
     * 建 MediaSession（只建一次，绝不重建——重建会让通知卡片消失）。
     *
     * 关键点：MediaSession 只在构建时读一次播放器的可用命令，而队列是后来才有的，
     * 所以 QueuePlayer 对「上一首/下一首」始终报告可用；真没队列时点了只是空操作。
     * 另外挂两个自定义按钮（上一首/下一首），由 onCustomCommand 转发，保证通知卡片三键齐全。
     */
    fun ensureSession() {
        val ctx = appContext ?: return
        val player = exo ?: return
        if (mediaSession != null) return
        runCatching {
            val sessionActivity = android.app.PendingIntent.getActivity(
                ctx,
                0,
                android.content.Intent(ctx, com.tianxiaodudou.qqmusicdownloader.MainActivity::class.java)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP),
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
            )
            // 现在播放器里是标准播放列表（每首歌一个 MediaItem），直接用原生 Player 建会话：
            // 上一首/下一首命令由框架按时间线自动给出，通知栏与锁屏都是系统标准控件
            mediaSession = androidx.media3.session.MediaSession.Builder(ctx, player)
                .setSessionActivity(sessionActivity)
                .build()
        }
    }

    /** 兼容旧调用：不再重建会话，避免通知卡片消失。 */
    fun refreshSession() = ensureSession()

    /** 元数据类设置（蓝牙歌词开关）变更后，立刻刷新当前 MediaItem 的标题。 */
    fun refreshMetadata() {
        val pl = exo ?: return
        val song = current ?: return
        val uri = pl.currentMediaItem?.localConfiguration?.uri ?: return
        val ctx = appContext ?: return
        btTitle = if (Settings.btLyrics(ctx)) LyricsRuntime.currentLine else ""
        runCatching { pl.replaceMediaItem(pl.currentMediaItemIndex, mediaItemOf(song, uri)) }
    }

    fun setQuality(q: Settings.Quality) {
        val ctx = appContext ?: return
        Settings.setQuality(ctx, q)
        // 上限变了：之前按旧上限解析的直链、以及卡顿降档的强制档位全部作废
        urlCache.clear()
        forcedQuality?.let { if (it.ordinal > q.ordinal) forcedQuality = null }
        stallDowngrades = 0
        if (current == null) return
        val pos = positionMs
        TrackResolver.clear()
        applyPlaylist(index)
        runCatching { exo?.seekTo(pos) }
        if (!playing) runCatching { exo?.pause() }
    }

    fun setSleep(minutes: Int) {
        sleepAfterCurrent = false
        sleepEndAt = System.currentTimeMillis() + minutes * 60_000L
    }

    fun sleepAfterCurrentTrack() {
        sleepEndAt = 0
        sleepAfterCurrent = true
    }

    fun cancelSleep() {
        sleepEndAt = 0
        sleepAfterCurrent = false
    }

    fun removeAt(at: Int) {
        if (at !in queue.indices) return
        val wasCurrent = at == index
        val list = queue.toMutableList().also { it.removeAt(at) }
        queue = list
        when {
            list.isEmpty() -> {
                index = -1
                exo?.stop()
                exo?.clearMediaItems()
            }
            at < index -> index -= 1
            wasCurrent -> {
                index = at.coerceAtMost(list.lastIndex)
                load(queue[index])
            }
        }
    }

    /** 网络顶不住当前码率：降一档重新解析（同一首最多两次），并提示用户。 */
    private fun downgradeForStall() {
        val ctx = appContext ?: return
        val song = current ?: return
        if (loading) return
        // 整首已经并行下好：直接切本地，音质不降也比网络流畅
        if (AudioFetch.cached(ctx, song.mid) != null) {
            exo?.seekTo(positionMs)
            exo?.play()
            return
        }
        // 用户关掉了自动降档：卡顿就卡着，不偷偷改音质
        if (!Settings.autoDowngrade(ctx)) return
        if (stallDowngrades >= 2) return
        val cap = forcedQuality ?: Settings.quality(ctx)
        val lower = Settings.Quality.entries.filter { it.ordinal < cap.ordinal }.maxByOrNull { it.ordinal } ?: return
        stallDowngrades += 1
        forcedQuality = lower
        error = null
        // 清掉解析缓存后重新按低一档解析当前曲
        TrackResolver.clear()
        val pos = positionMs
        applyPlaylist(index)
        runCatching { exo?.seekTo(pos) }
    }

    fun clearQueue() {
        queue = emptyList()
        index = -1
        exo?.stop()
        exo?.clearMediaItems()
        playing = false
        positionMs = 0
        durationMs = 0
    }

    fun release() {
        loadJob?.cancel()
        scope?.cancel()
        AudioFx.release()
        exo?.release()
        exo = null
        runCatching { LyricsRuntime.stop() }
        LyricsRuntime.onLineChanged = null
        runCatching { mediaSession?.release() }
        mediaSession = null
        runCatching { mediaCache?.release() }
        mediaCache = null
        runCatching { appContext?.stopService(android.content.Intent(appContext!!, PlaybackService::class.java)) }
    }

    // ---------------- 内部实现 ----------------

    /** 蓝牙歌词模式下当前标题（歌词行），空表示用歌名。 */
    private var btTitle: String = ""

    /**
     * 带元数据的 MediaItem：通知栏、锁屏、蓝牙车机都从这里取标题/歌手/封面/时长。
     * 开启「蓝牙歌词」时 Title 换成当前歌词行（非标准协议，车机兼容性不保证）。
     */


    private fun mediaItemOf(song: MusicApi.Song, uri: Uri): MediaItem {
        val artwork = com.tianxiaodudou.qqmusicdownloader.ui.player.coverUrl(song.albumMid, 500)
        val btOn = appContext?.let { Settings.btLyrics(it) } == true
        val title = if (btOn && btTitle.isNotEmpty()) btTitle else song.name
        val metadata = androidx.media3.common.MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(song.singers)
            .setAlbumTitle(song.album)
            .setDurationMs(song.interval * 1000L)
            .apply { if (artwork.isNotEmpty()) setArtworkUri(Uri.parse(artwork)) }
            .build()
        return MediaItem.Builder()
            .setMediaId(song.mid)
            .setUri(uri)
            .setMediaMetadata(metadata)
            .build()
    }

    private data class Source(
        val uri: Uri,
        val label: String,
        val path: String,
        val quality: String = "",
        val code: String = "",
    )

    private fun load(song: MusicApi.Song, startAt: Long = 0, autoPlay: Boolean = true, alt: Boolean = false) {
        val pl = exo ?: return
        val ctx = appContext ?: return
        loadJob?.cancel()
        // 切歌前把上一首的进度存下来（index 已经指向新歌，不能再用 current）
        loadedSong?.let { prev ->
            if (prev.mid != song.mid) {
                val prevPos = exo?.currentPosition ?: positionMs
                ResumeStore.save(ctx, prev.mid, prevPos, durationMs)
            }
        }
        if (loadedSong?.mid != song.mid) stallDowngrades = 0
        loadedSong = song
        loading = true
        error = null
        sourceLabel = ""
        sourcePath = ""
        actualQuality = ""
        durationMs = song.interval * 1000L
        val resumeAt = if (startAt <= 0) ResumeStore.get(ctx, song.mid) else 0L
        loadJob = scope?.launch {
            // 整首已经并行下到本地：直接本地播放，起播和拖动都不碰网络
            val cap = forcedQuality ?: Settings.quality(ctx)
            val localHit = withContext(Dispatchers.IO) { AudioFetch.cached(ctx, song.mid) }
                ?.takeIf { it.code.isEmpty() || codeOrdinal(it.code) <= cap.ordinal }
            if (localHit != null) {
                sourceLabel = "本地缓存" + if (localHit.label.isNotEmpty()) " · " + localHit.label else ""
                sourcePath = localHit.file.absolutePath
                actualQuality = if (localHit.quality.isNotEmpty()) localHit.quality else qualityText(localHit.code)
                pl.setMediaItem(mediaItemOf(song, Uri.fromFile(localHit.file)))
                val startPos = when {
                    startAt > 0 -> startAt
                    resumeAt > 0 -> resumeAt
                    else -> 0L
                }
                if (startPos > 0) pl.seekTo(startPos)
                pl.prepare()
                if (autoPlay) pl.play()
                loading = false
                prefetchAround()
                return@launch
            }
            val src = withContext(Dispatchers.IO) {
                runCatching { resolve(ctx, song, alt) }.getOrNull()
            }
            if (src == null) {
                loading = false
                error = "这首歌暂时拿不到播放地址（可能需要会员或已下架）"
                return@launch
            }
            sourceLabel = src.label
            sourcePath = src.path
            actualQuality = src.quality
            pl.setMediaItem(mediaItemOf(song, src.uri))
            val startPos = when {
                startAt > 0 -> startAt
                resumeAt > 0 -> resumeAt
                else -> 0L
            }
            if (startPos > 0) pl.seekTo(startPos)
            pl.prepare()
            if (autoPlay) pl.play()
            loading = false
            prefetchAround()
            // 起播稳定后再把当前这首整首并行拉下来（4 连接）：网络一抖就切本地，不用等降档
            val directUrl = src.uri.toString()
            if (directUrl.startsWith("http")) {
                scope?.launch {
                    delay(2_500)
                    if (current?.mid == song.mid && AudioFetch.cached(ctx, song.mid) == null) {
                        runCatching {
                            AudioFetch.ensure(ctx, song.mid, directUrl, src.label, src.quality, src.code)
                        }
                    }
                }
            }
        }
    }

    private fun resolve(ctx: Context, song: MusicApi.Song, alt: Boolean = false): Source {
        // 命中预取：直接起播，不再等接口
        urlCache[song.mid]?.let { c ->
            if (System.currentTimeMillis() - c.at < 10 * 60_000L) {
                return Source(Uri.parse(c.url), c.label, "", c.quality, c.code)
            }
            urlCache.remove(song.mid)
        }
        val wanted = forcedQuality ?: Settings.quality(ctx)
        // 预取缓存里可能是上限下调之前解析的高音质直链，超出上限的一律作废
        urlCache[song.mid]?.let { c ->
            if (c.code.isNotEmpty() && codeOrdinal(c.code) > wanted.ordinal) urlCache.remove(song.mid)
        }
        // 设置里选的是「上限」：只会从目标档位往下退，绝不升到更高档位
        val chain = Settings.Quality.entries
            .filter { it.ordinal <= wanted.ordinal }
            .sortedByDescending { it.ordinal }

        // 一次请求拿全部候选码率的直链
        val batch = runCatching {
            MusicApi.streamBatch(song, chain.map { it.code }.filter { it.isNotEmpty() }.distinct())
        }.getOrDefault(emptyMap())
        for (q in chain) {
            val url = batch[q.code]
            if (!url.isNullOrEmpty()) {
                return Source(
                    Uri.parse(if (alt) cdnUrl(url) else url),
                    q.label + " · " + q.detail,
                    "",
                    qualityText(q.code),
                    q.code,
                )
            }
        }
        // 批量没拿到（极端情况）再逐档补一次
        for (q in chain) {
            val url = runCatching { MusicApi.streamUrlOf(song, q.code) }.getOrNull()
            if (!url.isNullOrEmpty()) {
                return Source(
                    Uri.parse(if (alt) cdnUrl(url) else url),
                    q.label + " · " + q.detail,
                    "",
                    qualityText(q.code),
                    q.code,
                )
            }
        }

        // 版权受限：换一个可播版本再试
        val replaced = runCatching { Extras.replacement(song) }.getOrNull()
        if (replaced != null) {
            for (q in chain) {
                val url = runCatching { MusicApi.streamUrlOf(replaced, q.code) }.getOrNull()
                if (!url.isNullOrEmpty()) {
                    return Source(
                        Uri.parse(if (alt) cdnUrl(url) else url),
                        "替换音源 · " + q.label,
                        "",
                        qualityText(q.code),
                        q.code,
                    )
                }
            }
        }
        throw IllegalStateException("拿不到播放地址（可能需要会员或已下架）")
    }

    /** 播放页徽章显示「音质名称」，不是文件格式（FLAC/OGG 那种）。 */
    private fun qualityText(code: String): String = when {
        code == "M500" -> "标准"
        code == "M800" -> "高品质"
        code == "F000" -> "无损"
        code == "O801" -> "Hi-Res"
        code == "AI00" -> "母带"
        code == "D004" -> "杜比"
        else -> ""
    }

    /** 预取上一首/下一首的直链，切歌时直接命中缓存。 */
    private fun prefetchAround() {
        val ctx = appContext ?: return
        val sc = scope ?: return
        val targets = listOfNotNull(queue.getOrNull(index + 1), queue.getOrNull(index - 1))
        if (targets.isEmpty()) return
        // 播放列表模式下，下一首/上一首都是 MediaItem，提前把直链解析好即可
        targets.forEach { runCatching { TrackResolver.prefetch(it.mid) } }
        sc.launch {
            withContext(Dispatchers.IO) {
                val wanted = Settings.quality(ctx)
                val codes = Settings.Quality.entries
                    .filter { it.ordinal <= wanted.ordinal }
                    .sortedByDescending { it.ordinal }
                    .map { it.code }
                    .filter { it.isNotEmpty() }
                    .distinct()
                for ((n, s) in targets.withIndex()) {
                    if (urlCache[s.mid] != null) continue
                    val map = runCatching { MusicApi.streamBatch(s, codes) }.getOrDefault(emptyMap())
                    val code = codes.firstOrNull { !map[it].isNullOrEmpty() } ?: continue
                    val q = Settings.Quality.entries.firstOrNull { it.code == code } ?: continue
                    urlCache[s.mid] = CachedUrl(
                        map[code]!!,
                        q.label + " · " + q.detail,
                        qualityText(code),
                        System.currentTimeMillis(),
                        code,
                    )
                    // 只预下紧挨着的下一首，而且当前播放已经缓冲住 15s 以上才开工，别抢带宽
                    val ahead = bufferedMs - positionMs
                    if (n == 0 && ahead > 15_000 && AudioFetch.cached(ctx, s.mid) == null) {
                        runCatching {
                            AudioFetch.ensure(ctx, s.mid, map[code]!!, q.label, qualityText(code), code)
                        }
                    }
                }
            }
        }
    }

    /** 用 CDN 调度返回的根地址替换域名，播放失败重试时用。 */
    private fun cdnUrl(url: String): String = runCatching {
        val sip = Extras.cdnSips().firstOrNull() ?: return url
        val u = java.net.URI(url)
        val s = java.net.URI(sip)
        java.net.URI(s.scheme, s.authority, u.path, u.query, u.fragment).toString()
    }.getOrDefault(url)

    private fun onEnded() {
        current?.let { appContext?.let { ctx -> ResumeStore.clear(ctx, it.mid) } }
        if (sleepAfterCurrent) {
            sleepAfterCurrent = false
            exo?.pause()
            return
        }
        if (mode == Mode.SINGLE) {
            exo?.seekTo(0)
            exo?.play()
        } else {
            next(auto = true)
        }
    }
}
