package com.tianxiaodudou.qqmusicdownloader.player

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
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
import kotlin.random.Random

/**
 * UI 侧门面：**不持有 ExoPlayer**，只持有一个 MediaController（谷歌范例）。
 * 播放器与会话都在 PlaybackService 里；这里负责：
 *  - 把 UI 的队列/操作翻译成 MediaController 命令；
 *  - 把控制器回调镜像成 Compose 可观察状态（供界面直接读）；
 *  - 睡眠定时、倍速、进度记忆等 UI 侧业务。
 */
object PlayerController {

    enum class Mode { LIST, SINGLE, SHUFFLE }

    private const val PREFS = "player"
    private const val K_MODE = "mode"

    private var controller: MediaController? = null
    private var appContext: Context? = null
    private var scope: CoroutineScope? = null
    private var ticker: Job? = null

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
    /** 播放失败（含账户无权限）时的外部提示回调，UI 用它弹提示条。 */
    var onPlaybackError: ((String) -> Unit)? = null
    var sleepEndAt by mutableStateOf(0L)
        private set
    var sleepAfterCurrent by mutableStateOf(false)
        private set

    val current: MusicApi.Song? get() = queue.getOrNull(index)

    private var lastRx = 0L
    private var lastRxAt = 0L
    private var posAt = 0L
    private var lastPersistAt = 0L
    private var posRealtime = 0L

    /** 播放位置外推：UI 每帧读它做逐字歌词，不用等控制器回传。 */
    fun positionNow(): Long {
        if (!playing) return positionMs
        val elapsed = android.os.SystemClock.elapsedRealtime() - posRealtime
        return (posAt + (elapsed * speed).toLong()).coerceAtLeast(0L)
    }

    fun attach(context: Context) {
        if (controller != null) return
        val ctx = context.applicationContext
        appContext = ctx
        TrackResolver.attach(ctx)
        TrackResolver.onFailed = { _, reason ->
            error = reason
            onPlaybackError?.invoke(reason)
        }
        TrackResolver.onResolved = { mid, quality ->
            if (current?.mid == mid) {
                actualQuality = quality
                error = null
            }
        }
        scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
        speed = 1f
        mode = runCatching {
            Mode.valueOf(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(K_MODE, "LIST") ?: "LIST")
        }.getOrDefault(Mode.LIST)

        connect(ctx)
    }

    /** 连接服务里的 MediaSession；失败自动重试，连上后补做挂起的播放请求。 */
    private fun connect(ctx: Context) {
        runCatching { ctx.startService(android.content.Intent(ctx, PlaybackService::class.java)) }
        runCatching {
            val token = SessionToken(ctx, android.content.ComponentName(ctx, PlaybackService::class.java))
            val future = MediaController.Builder(ctx, token).buildAsync()
            future.addListener(
                {
                    val c = runCatching { future.get() }.getOrNull()
                    if (c != null) {
                        controller = c
                        bind(c)
                        flushPending()
                    } else {
                        android.util.Log.w("PlayerController", "MediaController 连接失败，2 秒后重试")
                        android.os.Handler(android.os.Looper.getMainLooper())
                            .postDelayed({ if (controller == null) connect(ctx) }, 2000)
                    }
                },
                ContextCompat.getMainExecutor(ctx),
            )
        }
    }

    // 控制器还没连上时用户就点了歌：先记住请求，连上后自动执行
    private var pendingPlay: Pair<List<MusicApi.Song>, Int>? = null
    private var connecting = false

    private fun flushPending() {
        val p = pendingPlay ?: return
        pendingPlay = null
        play(p.first, p.second)
    }

    private fun bind(c: MediaController) {
        runCatching { c.setPlaybackSpeed(speed) }
        applyModeTo(c)
        c.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
            }

            override fun onPlaybackStateChanged(state: Int) {
                loading = state == Player.STATE_BUFFERING
                if (state == Player.STATE_READY) durationMs = c.duration.coerceAtLeast(0L)
                if (state == Player.STATE_ENDED) onEnded()
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val mid = mediaItem?.mediaId ?: return
                val idx = queue.indexOfFirst { it.mid == mid }
                if (idx < 0) return
                if (sleepAfterCurrent) {
                    // 单曲循环下不会发生切歌，这里只在真的换曲时触发
                    sleepAfterCurrent = false
                    runCatching { c.pause() }
                }
                index = idx
                persistQueue()
                // 新曲还没解析完就保留旧值，避免徽章闪一下消失；解析完成后 ticker 会立刻校正
                TrackResolver.qualityOf(mid).takeIf { it.isNotEmpty() }?.let { actualQuality = it }
                durationMs = (TrackCatalog.get(mid)?.interval ?: 0) * 1000L
                // 只保留当前曲的进度：切到别的歌一律从头放
                appContext?.let { ctx -> ResumeStore.keepOnly(ctx, mid) }
            }

            override fun onPlayerError(e: androidx.media3.common.PlaybackException) {
                error = "播放出错：" + (e.message ?: "未知错误")
            }
        })

        restoreQueue(c)

        ticker?.cancel()
        ticker = scope?.launch {
            while (isActive) {
                val pl = controller
                if (pl != null) {
                    positionMs = pl.currentPosition.coerceAtLeast(0L)
                    posAt = positionMs
                    posRealtime = android.os.SystemClock.elapsedRealtime()
                    bufferedMs = pl.bufferedPosition.coerceAtLeast(0L)
                    com.tianxiaodudou.qqmusicdownloader.player.AudioStats.bufferAheadMs =
                        (bufferedMs - positionMs).coerceAtLeast(0L)
                    runCatching {
                        val rx = android.net.TrafficStats.getUidRxBytes(android.os.Process.myUid())
                        val nowMs = android.os.SystemClock.elapsedRealtime()
                        if (lastRxAt > 0 && nowMs > lastRxAt && rx > lastRx) {
                            val kbps = (rx - lastRx) * 1000 / (nowMs - lastRxAt) / 1024
                            com.tianxiaodudou.qqmusicdownloader.player.AudioStats.netKbps = kbps
                        }
                        lastRx = rx
                        lastRxAt = nowMs
                    }
                    val d = pl.duration
                    if (d > 0) durationMs = d
                    playing = pl.isPlaying
                    // 音质徽章自愈：解析是异步的，回调/切歌顺序可能错过，这里每 200ms 校正一次
                    current?.let { s ->
                        val q = TrackResolver.qualityOf(s.mid)
                        if (q.isNotEmpty() && q != actualQuality) actualQuality = q
                    }
                    if (System.currentTimeMillis() - lastPersistAt > 5000) {
                        lastPersistAt = System.currentTimeMillis()
                        persistQueue()
                    }
                    val song = current
                    if (pl.isPlaying && song != null) {
                        appContext?.let { ctx ->
                            ResumeStore.save(ctx, song.mid, positionMs, durationMs)
                            ResumeStore.keepOnly(ctx, song.mid)
                        }
                    }
                }
                val end = sleepEndAt
                if (end > 0 && System.currentTimeMillis() >= end) {
                    sleepEndAt = 0
                    pause()
                }
                delay(200)
            }
        }
    }

    /** 从本地恢复上次的队列、当前曲目与进度（不自动播放）。 */
    private fun restoreQueue(c: MediaController) {
        val ctx = appContext ?: return
        if (queue.isNotEmpty()) return
        val snap = QueueStore.load(ctx) ?: return
        queue = snap.songs
        index = snap.index.coerceIn(0, snap.songs.lastIndex)
        TrackCatalog.putAll(snap.songs)
        durationMs = snap.songs[index].interval * 1000L
        positionMs = snap.positionMs
        runCatching {
            c.setMediaItems(snap.songs.map { mediaItemFor(it) }, index, snap.positionMs)
            c.prepare()
            c.pause()
        }
    }

    private fun persistQueue() {
        val ctx = appContext ?: return
        if (queue.isEmpty()) {
            QueueStore.clear(ctx)
        } else {
            QueueStore.save(ctx, queue, index, positionMs)
        }
    }

    // ---------------- 队列与命令 ----------------

    fun play(list: List<MusicApi.Song>, at: Int) {
        if (at !in list.indices) return
        // 先把队列写进状态：即使控制器还没连上，界面也能立刻显示这首歌
        queue = list
        index = at
        TrackCatalog.putAll(list)
        durationMs = list[at].interval * 1000L
        val c = controller
        if (c == null) {
            pendingPlay = list to at
            if (!connecting) {
                connecting = true
                appContext?.let { ctx ->
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        connecting = false
                        if (controller == null) connect(ctx) else flushPending()
                    }, 300)
                }
            }
            return
        }
        TrackResolver.clear()
        val items = list.map { mediaItemFor(it) }
        runCatching {
            c.setMediaItems(items, at, 0L)
            c.prepare()
            c.play()
        }.onFailure { error = "播放失败：" + (it.message ?: "") }
        durationMs = list[at].interval * 1000L
        persistQueue()
        prefetchAround()
    }

    fun playAt(at: Int) {
        val c = controller ?: run {
            if (at in queue.indices) pendingPlay = queue to at
            return
        }
        if (at !in 0 until c.mediaItemCount) return
        index = at
        buildAt(c, at)
    }

    fun enqueue(songs: List<MusicApi.Song>) {
        val c = controller ?: return
        if (songs.isEmpty()) return
        val known = queue.mapTo(HashSet()) { it.mid }
        val add = songs.filter { known.add(it.mid) }
        if (add.isEmpty()) return
        TrackCatalog.putAll(add)
        val startEmpty = queue.isEmpty()
        queue = queue + add
        if (startEmpty) {
            index = 0
            play(queue, 0)
        } else {
            runCatching { c.addMediaItems(add.map { mediaItemFor(it) }) }
        }
        persistQueue()
    }

    fun removeAt(at: Int) {
        val c = controller ?: return
        if (at !in queue.indices) return
        queue = queue.filterIndexed { i, _ -> i != at }
        runCatching { c.removeMediaItem(at) }
        if (queue.isEmpty()) {
            index = -1
        } else if (index >= queue.size) {
            index = queue.size - 1
        }
    }

    fun toggle() {
        val c = controller ?: return
        if (current == null) return
        if (c.isPlaying) c.pause() else c.play()
    }

    fun pause() {
        val c = controller ?: return
        c.pause()
        current?.let { appContext?.let { ctx -> ResumeStore.save(ctx, it.mid, positionMs, durationMs) } }
    }

    fun next(auto: Boolean = false) {
        val c = controller ?: return
        if (c.mediaItemCount == 0) return
        if (auto && mode == Mode.SINGLE) {
            c.seekTo(0)
            c.play()
            return
        }
        // 以播放列表的实际位置为准，避免本地 index 与实际播放项不同步时点不动
        val cur = c.currentMediaItemIndex.coerceAtLeast(0)
        val count = c.mediaItemCount
        val target = when {
            mode == Mode.SHUFFLE && count > 1 -> {
                var n = cur
                while (n == cur) n = Random.nextInt(count)
                n
            }
            cur + 1 < count -> cur + 1
            else -> 0
        }
        index = target
        buildAt(c, target)
    }

    /** 上一首：所有入口（应用内、迷你播放器、耳机/车机/锁屏）都直接切上一首，不回当前曲开头。 */
    fun prev() {
        val c = controller ?: return
        if (c.mediaItemCount == 0) return
        val cur = c.currentMediaItemIndex.coerceAtLeast(0)
        val count = c.mediaItemCount
        val target = if (cur > 0) cur - 1 else count - 1
        index = target
        buildAt(c, target)
    }

    fun prevTrack() = prev()

    fun seekTo(ms: Long) {
        controller?.seekTo(ms)
        positionMs = ms
    }

    /** 退出登录用：立刻停播、清空队列、释放会话并停掉服务，系统媒体控件也随之消失。 */
    fun stopAndClear() {
        runCatching {
            controller?.let { c ->
                c.pause()
                c.clearMediaItems()
            }
        }
        runCatching { controller?.release() }
        controller = null
        queue = emptyList()
        index = -1
        playing = false
        loading = false
        positionMs = 0
        durationMs = 0
        actualQuality = ""
        error = null
        sleepEndAt = 0
        sleepAfterCurrent = false
        appContext?.let { ctx ->
            QueueStore.clear(ctx)
            runCatching { ctx.stopService(android.content.Intent(ctx, PlaybackService::class.java)) }
        }
    }

    fun clearQueue() {
        controller?.clearMediaItems()
        queue = emptyList()
        index = -1
        playing = false
        positionMs = 0
        durationMs = 0
        appContext?.let { QueueStore.clear(it) }
    }

    // ---------------- 设置类 ----------------

    fun changeMode(m: Mode) {
        mode = m
        appContext?.let {
            it.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(K_MODE, m.name).apply()
        }
        // 关键：把模式交给播放器本身。之前只改了 UI 状态，播放列表走到头时
        // ExoPlayer 仍按默认的“自动下一首”前进，单曲循环自然不生效。
        controller?.let { applyModeTo(it) }
    }

    private fun applyModeTo(c: MediaController) {
        runCatching {
            when (mode) {
                Mode.LIST -> {
                    c.shuffleModeEnabled = false
                    c.repeatMode = Player.REPEAT_MODE_ALL
                }
                Mode.SINGLE -> {
                    c.shuffleModeEnabled = false
                    c.repeatMode = Player.REPEAT_MODE_ONE
                }
                Mode.SHUFFLE -> {
                    c.shuffleModeEnabled = true
                    c.repeatMode = Player.REPEAT_MODE_ALL
                }
            }
        }
    }

    fun cycleMode() {
        changeMode(when (mode) {
            Mode.LIST -> Mode.SINGLE
            Mode.SINGLE -> Mode.SHUFFLE
            Mode.SHUFFLE -> Mode.LIST
        })
    }

    /** 换音质：立即对当前曲生效（替换当前 MediaItem 让数据源重新解析），不需要切歌。 */
    fun setQuality(q: Settings.Quality) {
        val ctx = appContext ?: return
        Settings.setQuality(ctx, q)
        TrackResolver.clear()
        val c = controller ?: return
        val song = current ?: return
        val pos = positionMs
        val wasPlaying = playing
        actualQuality = ""
        runCatching {
            c.replaceMediaItem(c.currentMediaItemIndex, mediaItemFor(song))
            c.prepare()
            c.seekTo(pos)
            if (wasPlaying) c.play() else c.pause()
        }
    }

    fun refreshMetadata() {
        val c = controller ?: return
        val song = current ?: return
        val ctx = appContext ?: return
        val mid = song.mid
        val base = c.currentMediaItem ?: return
        c.replaceMediaItem(
            c.currentMediaItemIndex,
            base.buildUpon().setMediaMetadata(metadataOf(song, Settings.btLyrics(ctx) && LyricsRuntime.currentLine.isNotEmpty())).build(),
        )
        runCatching { mid }
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

    fun release() {
        ticker?.cancel()
        scope?.cancel()
        runCatching { controller?.release() }
        controller = null
        appContext?.let { ctx -> runCatching { ctx.stopService(android.content.Intent(ctx, PlaybackService::class.java)) } }
    }

    // ---------------- 内部 ----------------

    private fun buildAt(c: MediaController, at: Int) {
        val song = queue.getOrNull(at) ?: return
        TrackCatalog.put(song)
        runCatching {
            c.seekTo(at, 0L)
            c.play()
        }
        durationMs = song.interval * 1000L
        prefetchAround()
    }

    private fun onEnded() {
        if (sleepAfterCurrent) {
            sleepAfterCurrent = false
            pause()
            return
        }
        if (mode == Mode.SINGLE) {
            controller?.seekTo(0)
            controller?.play()
        } else {
            next(auto = true)
        }
    }

    private fun mediaItemFor(song: MusicApi.Song): MediaItem {
        // URI 里带上音质上限：换档时 URI 变化，ExoPlayer 才会重新解析数据源（同 URI 会被当成没变）
        val tag = appContext?.let { Settings.quality(it).name } ?: ""
        return MediaItem.Builder()
            .setMediaId(song.mid)
            .setUri(TrackResolver.uriOf(song.mid, tag))
            .setMediaMetadata(metadataOf(song, false))
            .build()
    }

    private fun metadataOf(song: MusicApi.Song, lyricTitle: Boolean): MediaMetadata {
        val artwork = com.tianxiaodudou.qqmusicdownloader.ui.player.coverUrl(song.albumMid, 500)
        val title = if (lyricTitle && LyricsRuntime.currentLine.isNotEmpty()) LyricsRuntime.currentLine else song.name
        return MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(song.singers)
            .setAlbumTitle(song.album)
            .apply { if (artwork.isNotEmpty()) setArtworkUri(Uri.parse(artwork)) }
            .build()
    }

    private fun prefetchAround() {
        val targets = listOfNotNull(queue.getOrNull(index + 1), queue.getOrNull(index - 1))
        targets.forEach { runCatching { TrackResolver.prefetch(it.mid) } }
    }

}
