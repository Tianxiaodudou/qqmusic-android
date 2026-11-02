package com.tianxiaodudou.qqmusicdownloader.player

import com.tianxiaodudou.qqmusicdownloader.net.MusicApi
import java.util.concurrent.ConcurrentHashMap

/**
 * 歌曲元数据表：MediaItem 里只放 mid，UI 侧和服务侧都通过这张表把 mid 还原成完整歌曲信息。
 * 这样播放器（服务）不需要知道 UI 的队列对象，两边只共享不可变的歌曲数据。
 */
object TrackCatalog {
    private val map = ConcurrentHashMap<String, MusicApi.Song>()

    fun putAll(songs: List<MusicApi.Song>) {
        songs.forEach { map[it.mid] = it }
        if (map.size > 500) {
            // 简单防膨胀：超量时只保留最近 300 个
            map.keys.toList().take(map.size - 300).forEach { map.remove(it) }
        }
    }

    fun put(song: MusicApi.Song) { map[song.mid] = song }

    fun get(mid: String): MusicApi.Song? = map[mid]
}
