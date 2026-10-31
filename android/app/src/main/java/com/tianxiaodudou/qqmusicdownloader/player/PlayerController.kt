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
import com.tianxiaodudou.qqmusicdownloader.net.Lossless
import com.tianxiaodudou.qqmusicdownloader.net.MusicApi
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

    fun attach(context: Context) {
        if (exo != null) return
        val ctx = context.applicationContext
        appContext = ctx
        val p = ExoPlayer.Builder(ctx).build()
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
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) onEnded()
                if (state == Player.STATE_READY) durationMs = p.duration.coerceAtLeast(0L)
            }

            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                AudioFx.attach(ctx, audioSessionId, Settings.eqPreset(ctx))
            }

            override fun onPlayerError(e: PlaybackException) {
                loading = false
                error = "播放出错：" + (e.message ?: "未知错误")
            }
        })
        AudioFx.attach(ctx, p.audioSessionId, Settings.eqPreset(ctx))

        scope?.launch {
            while (isActive) {
                val pl = exo
                if (pl != null) {
                    positionMs = pl.currentPosition.coerceAtLeast(0L)
                    posAt = positionMs
                    posRealtime = SystemClock.elapsedRealtime()
                    val d = pl.duration
                    if (d > 0) durationMs = d
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
        if (p.isPlaying) p.pause() else {
            if (p.playbackState == Player.STATE_IDLE) load(current!!) else p.play()
        }
    }

    fun pause() {
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
    }

    // ---------------- 内部实现 ----------------

    private data class Source(val uri: Uri, val label: String, val path: String)

    private fun load(song: MusicApi.Song, startAt: Long = 0, autoPlay: Boolean = true) {
        val pl = exo ?: return
        val ctx = appContext ?: return
        loadJob?.cancel()
        loading = true
        error = null
        sourceLabel = ""
        sourcePath = ""
        durationMs = song.interval * 1000L
        loadJob = scope?.launch {
            val src = withContext(Dispatchers.IO) {
                runCatching { resolve(ctx, song) }.getOrNull()
            }
            if (src == null) {
                loading = false
                error = "这首歌暂时拿不到播放地址（可能需要会员或已下架）"
                return@launch
            }
            sourceLabel = src.label
            sourcePath = src.path
            pl.setMediaItem(MediaItem.fromUri(src.uri))
            if (startAt > 0) pl.seekTo(startAt)
            pl.prepare()
            if (autoPlay) pl.play()
            loading = false
        }
    }

    private fun resolve(ctx: Context, song: MusicApi.Song): Source {
        val quality = Settings.quality(ctx)
        if (quality == Settings.Quality.LOSSLESS) {
            val file = runCatching { Lossless.playableFile(ctx, song) }.getOrNull()
            if (file != null) return Source(Uri.fromFile(file), "无损 · FLAC", file.absolutePath)
        }
        val stream = MusicApi.streamUrl(song, quality)
            ?: throw IllegalStateException("拿不到播放地址")
        val label = if (stream.code == "M800") "高品质 · 320kbps MP3" else "标准 · 128kbps MP3"
        return Source(Uri.parse(stream.url), label, "")
    }

    private fun onEnded() {
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
