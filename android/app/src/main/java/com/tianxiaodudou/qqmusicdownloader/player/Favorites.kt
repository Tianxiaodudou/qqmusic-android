package com.tianxiaodudou.qqmusicdownloader.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.tianxiaodudou.qqmusicdownloader.net.MusicApi
import com.tianxiaodudou.qqmusicdownloader.net.PlaylistApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 「我喜欢」：收藏态缓存 + 切换。 */
object Favorites {

    var ids by mutableStateOf<Set<String>>(emptySet())
        private set
    var loaded by mutableStateOf(false)
        private set

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var job: Job? = null

    fun isFav(mid: String): Boolean = ids.contains(mid)

    fun ensureLoaded() {
        if (loaded || job?.isActive == true) return
        refresh()
    }

    fun refresh() {
        job = scope.launch {
            val list = runCatching { PlaylistApi.favSongs(1, 100) }.getOrNull() ?: return@launch
            ids = list.map { it.mid }.toSet()
            loaded = true
        }
    }

    /** 返回是否成功；成功后立刻更新本地收藏态。 */
    suspend fun toggle(song: MusicApi.Song): Boolean = withContext(Dispatchers.IO) {
        val fav = ids.contains(song.mid)
        val ok = runCatching {
            if (fav) PlaylistApi.unlike(song) else PlaylistApi.like(song)
        }.getOrDefault(false)
        if (ok) ids = if (fav) ids - song.mid else ids + song.mid
        ok
    }
}
