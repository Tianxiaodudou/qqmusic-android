package com.tianxiaodudou.qqmusicdownloader.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.tianxiaodudou.qqmusicdownloader.net.AlbumApi
import com.tianxiaodudou.qqmusicdownloader.net.MusicApi
import com.tianxiaodudou.qqmusicdownloader.net.RecommendApi
import com.tianxiaodudou.qqmusicdownloader.net.SingerApi
import com.tianxiaodudou.qqmusicdownloader.net.TopApi

/** 首页模块设置变化时 +1，首页据此（强制）重载。 */
object HomeConfig {
    var version by mutableIntStateOf(0)
        private set

    /** 置位表示下一次加载必须绕过缓存（设置改动 / 手动刷新）。 */
    private var force by mutableStateOf(true)

    fun bump() {
        force = true
        version += 1
    }

    /** 手动刷新首页：等同设置变更，但会提示。 */
    fun refresh() = bump()

    fun takeForce(): Boolean {
        val f = force
        force = false
        return f
    }
}

/**
 * 首页数据的内存缓存：切换标签、进出模块页都不再重新请求，
 * 直到手动刷新或进程重启（缓存只活在内存里）。
 */
object HomeCache {
    var feed: List<RecommendApi.FeedGroup>? = null
    var songlists: List<RecommendApi.Songlist>? = null
    var newSongs: List<MusicApi.Song>? = null
    var albums: List<AlbumApi.Album>? = null
    var singers: List<SingerApi.Singer>? = null
    var hot: List<String>? = null
    var charts: List<TopApi.Chart>? = null

    /** 各模块已加载的歌曲页（key = 模块标识），进出模块页都复用，不再重新请求。 */
    val songPages: MutableMap<String, List<MusicApi.Song>> = mutableMapOf()
    val pageNos: MutableMap<String, Int> = mutableMapOf()
    val exhausted: MutableSet<String> = mutableSetOf()

    fun clear() {
        songPages.clear()
        pageNos.clear()
        exhausted.clear()
        feed = null
        songlists = null
        newSongs = null
        albums = null
        singers = null
        hot = null
        charts = null
    }
}
