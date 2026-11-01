package com.tianxiaodudou.qqmusicdownloader.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import com.tianxiaodudou.qqmusicdownloader.net.MusicApi

sealed interface Route {
    data class Album(val mid: String, val name: String) : Route
    data class Singer(val mid: String, val name: String) : Route
    data class Playlist(val id: String, val name: String) : Route
    data class FavLibrary(val albums: Boolean) : Route
    data object Social : Route
    data object HomeModules : Route
}

/** 极简页面栈：详情页盖在播放器/主界面上，返回即出栈。 */
object Nav {
    var stack by mutableStateOf<List<Route>>(emptyList())
        private set

    fun push(r: Route) {
        stack = stack + r
    }

    fun pop() {
        stack = stack.dropLast(1)
    }

    fun clear() {
        stack = emptyList()
    }
}

/** 搜索入口总线：热搜词点一下就把关键词送进搜索页，保证和搜索页走同一条链路。 */
object SearchBus {
    var keyword by mutableStateOf("")
        private set
    var ticket by androidx.compose.runtime.mutableIntStateOf(0)
        private set

    fun submit(k: String) {
        val q = k.trim()
        if (q.isEmpty()) return
        keyword = q
        ticket += 1
    }
}

@Composable
fun RouteHost(onPlay: (List<MusicApi.Song>, Int) -> Unit, onDownload: (MusicApi.Song) -> Unit) {
    when (val r = Nav.stack.lastOrNull()) {
        is Route.Album -> AlbumScreen(r.mid, r.name, onPlay, onDownload)
        is Route.Singer -> SingerScreen(r.mid, r.name, onPlay, onDownload)
        is Route.Playlist -> PlaylistScreen(r.id, r.name, onPlay, onDownload)
        is Route.FavLibrary -> FavLibraryScreen(r.albums, onPlay, onDownload)
        is Route.Social -> SocialScreen()
        is Route.HomeModules -> HomeModulesScreen()
        null -> Unit
    }
}
