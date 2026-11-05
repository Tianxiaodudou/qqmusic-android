package com.tianxiaodudou.qqmusicdownloader.net

import androidx.compose.runtime.mutableStateMapOf
import com.tianxiaodudou.qqmusicdownloader.player.SongAccess

/**
 * 可播性探测缓存。
 *
 * 结论来自实测（197 首对拍）：pay_month / price_track 都不能单独作为判据 ——
 * 有 16 首 pay_month=1 的歌 320k 依然能拿到直链（实际可播），
 * 也有 price_track=0 的歌全档位 104003。
 *
 * 唯一可靠的是 vkey 返回的 result：0=能播，104003=无权限。
 * 列表渲染时先按「未知」不标；后台批量探测（25 首/次）回来后再打徽章。
 */
object PlayableProbe {

    /** mid -> result（0 可播，104003 无权限，其它为失败） */
    val results = mutableStateMapOf<String, Int>()
    private val requested = HashSet<String>()

    fun request(songs: List<MusicApi.Song>, limit: Int = 30) {
        val todo = songs.asSequence()
            .filter { it.mid.isNotEmpty() && !requested.contains(it.mid) && !results.containsKey(it.mid) }
            .take(limit)
            .toList()
        if (todo.isEmpty()) return
        todo.forEach { requested.add(it.mid) }
        Thread {
            runCatching {
                val out = HashMap<String, Int>()
                todo.chunked(25).forEach { chunk ->
                    out.putAll(runCatching { MusicApi.probeBatch(chunk, "M800") }.getOrDefault(emptyMap()))
                    out.putAll(runCatching { MusicApi.probeBatch(chunk, "F000") }.getOrDefault(emptyMap()))
                }
                todo.forEach { s -> results[s.mid] = out[s.mid] ?: -1 }
            }
        }.start()
    }

    /** 只有实测 104003 才返回徽章文案，其它一律空（避免误标）。 */
    fun badge(song: MusicApi.Song): String {
        val r = results[song.mid] ?: return ""
        if (r != 104003) return ""
        return when (SongAccess.of(song).kind) {
            SongAccess.Kind.PAID_TRACK -> "单曲付费"
            SongAccess.Kind.PAID_ALBUM -> "专辑付费"
            SongAccess.Kind.NO_COPYRIGHT -> "无版权"
            else -> "绿钻"
        }
    }
}
