package com.tianxiaodudou.qqmusicdownloader.ui

import androidx.activity.compose.BackHandler
import com.tianxiaodudou.qqmusicdownloader.ui.theme.AppTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tianxiaodudou.qqmusicdownloader.net.AlbumApi
import com.tianxiaodudou.qqmusicdownloader.net.Extras
import com.tianxiaodudou.qqmusicdownloader.net.MusicApi
import com.tianxiaodudou.qqmusicdownloader.net.PlaylistApi
import com.tianxiaodudou.qqmusicdownloader.net.Settings
import com.tianxiaodudou.qqmusicdownloader.net.SingerApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val SEARCH_TYPES = listOf("单曲" to 0, "歌单" to 3, "歌手" to 1, "专辑" to 2)

@Composable
fun SearchScreen(onPlay: (List<MusicApi.Song>, Int) -> Unit, onDownload: (MusicApi.Song) -> Unit) {
    var keyword by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var type by remember { mutableIntStateOf(0) }
    var result by remember { mutableStateOf(Extras.SearchResult()) }
    var suggestions by remember { mutableStateOf<List<String>>(emptyList()) }
    var hot by remember { mutableStateOf<List<String>>(emptyList()) }
    var searched by remember { mutableStateOf(false) }
    // 无限流：每次 15 条，滑到底继续取
    var page by remember { mutableIntStateOf(1) }
    var moreLoading by remember { mutableStateOf(false) }
    var exhausted by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // 搜索页返回：先回热搜/历史，再交给上层（标签页 -> 首页 -> 退出）
    BackHandler(enabled = searched) {
        searched = false
        suggestions = emptyList()
    }

    LaunchedEffect(Unit) {
        hot = withContext(Dispatchers.IO) { runCatching { MusicApi.hotKeys() }.getOrDefault(emptyList()) }.take(10)
    }

    LaunchedEffect(keyword) {
        if (keyword.isBlank() || searched) {
            suggestions = emptyList()
            return@LaunchedEffect
        }
        delay(280)
        suggestions = withContext(Dispatchers.IO) { runCatching { Extras.complete(keyword.trim()) }.getOrDefault(emptyList()) }
    }

    fun doSearch(key: String = keyword, t: Int = type) {
        val q = key.trim()
        if (q.isEmpty() || loading) return
        keyword = q
        type = t
        loading = true
        error = ""
        page = 1
        exhausted = false
        suggestions = emptyList()
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching { Extras.searchByType(q, SEARCH_TYPES[t].second, page = 1, num = Settings.PAGE_SIZE) }
            }
            loading = false
            searched = true
            r.onSuccess {
                result = it
                val empty = when (t) {
                    0 -> it.songs.isEmpty()
                    1 -> it.singers.isEmpty()
                    2 -> it.albums.isEmpty()
                    else -> it.playlists.isEmpty()
                }
                if (empty) error = "没有搜到结果"
            }.onFailure { error = it.message ?: "搜索失败" }
        }
    }

    fun loadMore() {
        if (loading || moreLoading || exhausted || keyword.isBlank()) return
        moreLoading = true
        val t = type
        val next = page + 1
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching { Extras.searchByType(keyword, SEARCH_TYPES[t].second, page = next, num = Settings.PAGE_SIZE) }
            }
            moreLoading = false
            r.onSuccess { more ->
                val cur = result
                val merged = Extras.SearchResult(
                    songs = (cur.songs + more.songs).distinctBy { it.mid },
                    playlists = (cur.playlists + more.playlists).distinctBy { it.id },
                    singers = (cur.singers + more.singers).distinctBy { it.mid },
                    albums = (cur.albums + more.albums).distinctBy { it.mid },
                )
                if (merged.songs.size == cur.songs.size && merged.singers.size == cur.singers.size &&
                    merged.albums.size == cur.albums.size && merged.playlists.size == cur.playlists.size
                ) {
                    exhausted = true
                } else {
                    result = merged
                    page = next
                }
            }
        }
    }

    // 热搜词 / 外部入口进来：直接把关键词交给同一个搜索函数
    LaunchedEffect(SearchBus.ticket) {
        if (SearchBus.ticket > 0) {
            keyword = SearchBus.keyword
            type = 0
            doSearch(SearchBus.keyword, 0)
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = keyword,
                onValueChange = {
                    keyword = it
                    searched = false
                },
                singleLine = true,
                placeholder = { Text("歌名 / 歌手 / 专辑 / 歌单") },
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = { doSearch() }) { Text("搜索") }
        }

        // 联想词 / 热搜
        if (suggestions.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                suggestions.take(4).forEach { s ->
                    Text(
                        s,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .clickable { doSearch(s) }
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                    )
                }
            }
        } else if (!searched && keyword.isBlank() && hot.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                hot.take(4).forEach { s ->
                    Text(
                        s,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .clickable { doSearch(s) }
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                    )
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SEARCH_TYPES.forEachIndexed { i, pair ->
                Box(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(if (type == i) AppTheme.colors.brandSoft else MaterialTheme.colorScheme.surfaceVariant)
                        .clickable {
                            type = i
                            if (keyword.isNotBlank()) doSearch(keyword, i)
                        }
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                ) {
                    Text(
                        pair.first,
                        fontSize = 12.sp,
                        color = if (type == i) AppTheme.colors.selectedText else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (loading) {
            Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        }
        if (error.isNotEmpty()) {
            Text(error, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp))
        }

        when (type) {
            0 -> SongListView(
                songs = result.songs,
                modifier = Modifier.weight(1f),
                fixId = "search:" + keyword,
                onPlay = onPlay,
                onDownload = onDownload,
                onLoadMore = { loadMore() },
                loadingMore = moreLoading,
            )
            1 -> {
                val st = androidx.compose.foundation.lazy.rememberLazyListState()
                androidx.compose.runtime.LaunchedEffect(st, result.singers.size) {
                    androidx.compose.runtime.snapshotFlow { st.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
                        .collect { last -> if (last >= result.singers.size - 3) loadMore() }
                }
                LazyColumn(state = st, modifier = Modifier.fillMaxSize()) {
                    items(result.singers) { s ->
                        Row(
                            Modifier.fillMaxWidth().clickable { Nav.push(Route.Singer(s.mid, s.name)) }
                                .padding(horizontal = 4.dp, vertical = 12.dp),
                        ) { Text(s.name, fontSize = 15.sp) }
                    }
                }
            }
            2 -> {
                val st = androidx.compose.foundation.lazy.rememberLazyListState()
                androidx.compose.runtime.LaunchedEffect(st, result.albums.size) {
                    androidx.compose.runtime.snapshotFlow { st.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
                        .collect { last -> if (last >= result.albums.size - 3) loadMore() }
                }
                LazyColumn(state = st, modifier = Modifier.fillMaxSize()) {
                    items(result.albums) { a ->
                        Row(
                            Modifier.fillMaxWidth().clickable { Nav.push(Route.Album(a.mid, a.name)) }
                                .padding(horizontal = 4.dp, vertical = 12.dp),
                        ) {
                            Column {
                                Text(a.name, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(a.singer, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
            else -> {
                val st = androidx.compose.foundation.lazy.rememberLazyListState()
                androidx.compose.runtime.LaunchedEffect(st, result.playlists.size) {
                    androidx.compose.runtime.snapshotFlow { st.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
                        .collect { last -> if (last >= result.playlists.size - 3) loadMore() }
                }
                LazyColumn(state = st, modifier = Modifier.fillMaxSize()) {
                    items(result.playlists) { p ->
                        Row(
                            Modifier.fillMaxWidth().clickable { Nav.push(Route.Playlist(p.id, p.name)) }
                                .padding(horizontal = 4.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(p.name, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Text(p.count.toString() + " 首", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}
