package com.tianxiaodudou.qqmusicdownloader.player

import com.tianxiaodudou.qqmusicdownloader.net.LyricApi
import com.tianxiaodudou.qqmusicdownloader.ui.player.LyricsCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 歌词运行时：当前歌词行 + 行内进度，播放页和桌面歌词共用同一份数据。
 * 自己按 80ms 跟随播放进度，切歌自动重新加载（命中 LyricsCache 就不发请求）。
 */
object LyricsRuntime {

    var lines: List<LyricApi.Line> = emptyList()
        private set
    var index: Int = -1
        private set
    /** 当前行扫字进度 0..1，与播放页同一套 word 时间轴。 */
    var progress: Float = 0f
        private set

    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private var ticker: Job? = null
    private var loadedMid = ""

    val currentLine: String get() = lines.getOrNull(index)?.text.orEmpty()

    fun start() {
        if (ticker?.isActive == true) return
        ticker = scope.launch {
            while (isActive) {
                sync()
                delay(80)
            }
        }
    }

    fun stop() {
        ticker?.cancel()
        ticker = null
    }

    private suspend fun sync() {
        val song = PlayerController.current
        if (song == null) {
            if (loadedMid.isNotEmpty()) {
                loadedMid = ""
                lines = emptyList()
                index = -1
                progress = 0f
            }
            return
        }
        if (song.mid != loadedMid) {
            loadedMid = song.mid
            val cached = LyricsCache.get(song.mid)
            lines = cached?.lines ?: withContext(Dispatchers.IO) {
                runCatching { LyricApi.load(song.mid) }.getOrNull()
                    ?.also { LyricsCache.put(song.mid, it) }
                    ?.lines ?: emptyList()
            }
            index = -1
            progress = 0f
        }
        if (lines.isEmpty()) return
        val t = PlayerController.positionNow()
        var i = -1
        for (k in lines.indices) {
            if (lines[k].timeMs <= t) i = k else break
        }
        index = i
        progress = if (i < 0) 0f else lineProgress(lines[i], t, lines.getOrNull(i + 1)?.timeMs ?: (lines[i].timeMs + 4000))
    }

    /** 与播放页一致的连续扫字进度。 */
    private fun lineProgress(line: LyricApi.Line, t: Long, nextStart: Int): Float {
        val total = line.words.sumOf { it.text.length }
        if (total == 0) {
            val start = line.timeMs.toLong()
            val end = maxOf(nextStart.toLong(), start + 2000L)
            return ((t - start).toFloat() / (end - start)).coerceIn(0f, 1f)
        }
        var sung = 0f
        for (w in line.words) {
            val s = w.startMs.toLong()
            val e = s + w.durMs
            if (t >= e) sung += w.text.length
            else if (t > s) {
                sung += w.text.length * ((t - s).toFloat() / w.durMs.coerceAtLeast(1))
                break
            } else break
        }
        return (sung / total).coerceIn(0f, 1f)
    }
}
