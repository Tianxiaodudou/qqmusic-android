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
    var sleepEndAt by mutableStateOf(0L)
        private set
    var sleepAfterCurrent by mutableStateOf(false)
        private set

    val current: MusicApi.Song? get() = queue.getOrNull(index)

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
        TrackResolver.onResolved = { mid, quality ->
            if (current?.mid == mid) actualQuality = quality
        }
        scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
        speed = Settings.speed(ctx)
        mode = runCatching {
            Mode.valueOf(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(K_MODE, "LIST") ?: "LIST")
        }.getOrDefault(Mode.LIST)

        runCatching {
            ctx.startService(android.content.Intent(ctx, PlaybackService::class.java))
            val token = SessionToken(ctx, android.content.ComponentName(ctx, PlaybackService::class.java))
            val future = MediaController.Builder(ctx, token).buildAsync()
            future.addListener(
                {
                    controller = runCatching { future.get() }.getOrNull()
                    controller?.let { bind(it) }
                },
                ContextCompat.getMainExecutor(ctx),
            )
        }
    }

    private fun bind(c: MediaController) {
        runCatching { c.setPlaybackSpeed(speed) }
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
                index = idx
                persistQueue()
                actualQuality = TrackResolver.qualityOf(mid)
                durationMs = (TrackCatalog.get(mid)?.interval ?: 0) * 1000L
                appContext?.let { ResumeStore.get(it, mid).let { pos -> if (pos > 0) c.seekTo(pos) } }
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
                    val d = pl.duration
                    if (d > 0) durationMs = d
                    playing = pl.isPlaying
                    if (System.currentTimeMillis() - lastPersistAt > 5000) {
                        lastPersistAt = System.currentTimeMillis()
                        persistQueue()
                    }
                    val song = current
                    if (pl.isPlaying && song != null) {
                        appContext?.let { ResumeStore.save(it, song.mid, positionMs, durationMs) }
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
        val c = controller ?: return
        queue = list
        index = at
        TrackCatalog.putAll(list)
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
        val c = controller ?: return
        if (at !in queue.indices) return
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
        if (queue.isEmpty()) return
        if (auto && mode == Mode.SINGLE) {
            c.seekTo(0)
            c.play()
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
        buildAt(c, target)
    }

    /** 应用内上一首：3 秒内回上一首，超过 3 秒先回本曲开头。 */
    fun prev() {
        val c = controller ?: return
        if (queue.isEmpty()) return
        if (c.currentPosition > 3000) {
            c.seekTo(0)
            positionMs = 0
            return
        }
        prevTrack()
    }

    /** 系统侧上一首：永远切到上一首。 */
    fun prevTrack() {
        val c = controller ?: return
        if (queue.isEmpty()) return
        val target = if (index > 0) index - 1 else queue.lastIndex
        index = target
        buildAt(c, target)
    }

    fun seekTo(ms: Long) {
        controller?.seekTo(ms)
        positionMs = ms
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
    }

    fun cycleMode() {
        changeMode(when (mode) {
            Mode.LIST -> Mode.SINGLE
            Mode.SINGLE -> Mode.SHUFFLE
            Mode.SHUFFLE -> Mode.LIST
        })
    }

    fun changeSpeed(v: Float) {
        speed = v
        controller?.setPlaybackSpeed(v)
        appContext?.let { Settings.setSpeed(it, v) }
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

    private fun mediaItemFor(song: MusicApi.Song): MediaItem =
        MediaItem.Builder()
            .setMediaId(song.mid)
            .setUri(TrackResolver.uriOf(song.mid))
            .setMediaMetadata(metadataOf(song, false))
            .build()

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
