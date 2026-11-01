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
    /** 卡顿降档计数与计时：同一首歌最多降两档，避免来回跳。 */
    private var stallDowngrades = 0
    private var bufferingSince = 0L
    private var loadedSong: MusicApi.Song? = null

    private data class CachedUrl(val url: String, val label: String, val quality: String, val at: Long)

    /** 下一首/上一首的直链预取缓存，10 分钟内有效。 */
    private val urlCache = java.util.concurrent.ConcurrentHashMap<String, CachedUrl>()

    val current: MusicApi.Song? get() = queue.getOrNull(index)

    fun attach(context: Context) {
        if (exo != null) return
        val ctx = context.applicationContext
        appContext = ctx
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
        val cacheFactory = androidx.media3.datasource.cache.CacheDataSource.Factory()
            .setUpstreamDataSourceFactory(upstream)
        if (cache != null) {
            cacheFactory.setCache(cache)
            cacheFactory.setCacheWriteDataSinkFactory(
                androidx.media3.datasource.cache.CacheDataSink.Factory()
                    .setCache(cache)
                    .setFragmentSize(4L * 1024 * 1024),
            )
        }
        val p = ExoPlayer.Builder(ctx)
            .setMediaSourceFactory(
                androidx.media3.exoplayer.source.DefaultMediaSourceFactory(cacheFactory),
            )
            .setLoadControl(
                androidx.media3.exoplayer.DefaultLoadControl.Builder()
                    // 起播 2s、保底 30s、上限 180s：高音质码流给足余量
                    .setBufferDurationsMs(30_000, 180_000, 2_000, 5_000)
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
                    load(song, startAt = positionMs, autoPlay = true, alt = true)
                    return
                }
                loading = false
                error = "播放出错：" + (e.message ?: "未知错误")
            }
        })
        AudioFx.attach(ctx, p.audioSessionId, Settings.eqPreset(ctx))

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
        load(list[at])
    }

    fun playAt(at: Int) {
        val song = queue.getOrNull(at) ?: return
        index = at
        load(song)
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
            load(queue[0])
        }
    }

    fun toggle() {
        val p = exo ?: return
        if (current == null) return
        // 解析音源期间不要重复触发 load，否则会把正在进行的解析取消掉，越点越慢
        if (loading) return
        if (p.isPlaying) p.pause() else {
            if (p.playbackState == Player.STATE_IDLE) load(current!!) else p.play()
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
        load(queue[target])
    }

    fun prev() {
        if (queue.isEmpty()) return
        val p = exo
        if (p != null && p.currentPosition > 3000) {
            p.seekTo(0)
            positionMs = 0
            return
        }
        val target = if (index > 0) index - 1 else queue.lastIndex
        index = target
        load(queue[target])
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

    /** 换音质：保留当前进度与播放状态重新解析。 */
    fun setQuality(q: Settings.Quality) {
        val ctx = appContext ?: return
        Settings.setQuality(ctx, q)
        val song = current ?: return
        load(song, startAt = positionMs, autoPlay = playing)
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
        if (loading || stallDowngrades >= 2) return
        val cap = forcedQuality ?: Settings.quality(ctx)
        val lower = Settings.Quality.entries.filter { it.ordinal < cap.ordinal }.maxByOrNull { it.ordinal } ?: return
        stallDowngrades += 1
        forcedQuality = lower
        error = null
        load(song, startAt = positionMs, autoPlay = true)
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
        runCatching { mediaCache?.release() }
        mediaCache = null
    }

    // ---------------- 内部实现 ----------------

    private data class Source(val uri: Uri, val label: String, val path: String, val quality: String = "")

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
            pl.setMediaItem(MediaItem.fromUri(src.uri))
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
        }
    }

    private fun resolve(ctx: Context, song: MusicApi.Song, alt: Boolean = false): Source {
        // 命中预取：直接起播，不再等接口
        urlCache[song.mid]?.let { c ->
            if (System.currentTimeMillis() - c.at < 10 * 60_000L) {
                return Source(Uri.parse(c.url), c.label, "", c.quality)
            }
            urlCache.remove(song.mid)
        }
        val wanted = forcedQuality ?: Settings.quality(ctx)
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
                )
            }
        }
        // 批量没拿到（极端情况）再逐档补一次
        for (q in chain) {
            val url = runCatching { MusicApi.streamUrlOf(song, q.code) }.getOrNull()
            if (!url.isNullOrEmpty()) {
                return Source(Uri.parse(if (alt) cdnUrl(url) else url), q.label + " · " + q.detail, "", qualityText(q.code))
            }
        }

        // 版权受限：换一个可播版本再试
        val replaced = runCatching { Extras.replacement(song) }.getOrNull()
        if (replaced != null) {
            for (q in chain) {
                val url = runCatching { MusicApi.streamUrlOf(replaced, q.code) }.getOrNull()
                if (!url.isNullOrEmpty()) {
                    return Source(Uri.parse(if (alt) cdnUrl(url) else url), "替换音源 · " + q.label, "", qualityText(q.code))
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
        sc.launch {
            withContext(Dispatchers.IO) {
                val wanted = Settings.quality(ctx)
                val codes = Settings.Quality.entries
                    .filter { it.ordinal <= wanted.ordinal }
                    .sortedByDescending { it.ordinal }
                    .map { it.code }
                    .filter { it.isNotEmpty() }
                    .distinct()
                for (s in targets) {
                    if (urlCache[s.mid] != null) continue
                    val map = runCatching { MusicApi.streamBatch(s, codes) }.getOrDefault(emptyMap())
                    val code = codes.firstOrNull { !map[it].isNullOrEmpty() } ?: continue
                    val q = Settings.Quality.entries.firstOrNull { it.code == code } ?: continue
                    urlCache[s.mid] = CachedUrl(
                        map[code]!!,
                        q.label + " · " + q.detail,
                        qualityText(code),
                        System.currentTimeMillis(),
                    )
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
