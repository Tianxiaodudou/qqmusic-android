package com.tianxiaodudou.qqmusicdownloader.ui

import com.tianxiaodudou.qqmusicdownloader.ui.theme.AppTheme

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tianxiaodudou.qqmusicdownloader.net.MusicApi
import com.tianxiaodudou.qqmusicdownloader.net.Extras
import com.tianxiaodudou.qqmusicdownloader.net.PlaylistApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(onPlay: (List<MusicApi.Song>, Int) -> Unit, onDownload: (MusicApi.Song) -> Unit) {
    var playlists by remember { mutableStateOf<List<PlaylistApi.Item>?>(null) }
    var opened by remember { mutableStateOf<PlaylistApi.Item?>(null) }
    var songs by remember { mutableStateOf<List<MusicApi.Song>?>(null) }
    var error by remember { mutableStateOf("") }
    var creating by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var showFav by remember { mutableStateOf(false) }
    var favs by remember { mutableStateOf<List<PlaylistApi.Item>?>(null) }
    var pendingDelete by remember { mutableStateOf<PlaylistApi.Item?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current

    // 歌单详情返回列表；列表页再交给上层（标签页 -> 首页 -> 退出）
    BackHandler(enabled = opened != null) {
        opened = null
        songs = null
    }

    LaunchedEffect(Unit) {
        val r = withContext(Dispatchers.IO) { runCatching { PlaylistApi.mine() } }
        r.onSuccess { list ->
            playlists = list
            val filled = withContext(Dispatchers.IO) { runCatching { PlaylistApi.withCovers(list) }.getOrDefault(list) }
            playlists = filled
            Covers.prefetch(context, filled.map { it.cover })
        }.onFailure { error = it.message ?: "歌单加载失败"; playlists = emptyList() }
    }
    LaunchedEffect(showFav) {
        if (showFav && favs == null) {
            favs = withContext(Dispatchers.IO) {
                val l = runCatching { Extras.favPlaylists() }.getOrDefault(emptyList())
                runCatching { PlaylistApi.withCovers(l) }.getOrDefault(l)
            }
        }
    }
    LaunchedEffect(opened) {
        val p = opened ?: return@LaunchedEffect
        songs = null
        val r = withContext(Dispatchers.IO) { runCatching { PlaylistApi.songs(p) } }
        r.onSuccess { songs = it }.onFailure { error = it.message ?: "歌曲加载失败"; songs = emptyList() }
    }

    val current = opened

        pendingDelete?.let { item ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除歌单") },
            text = { Text("删除「" + item.name + "」？歌单里的歌不会从曲库删除。") },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) {
                            runCatching { Extras.deletePlaylist(item.dirId.toIntOrNull() ?: 0) }.getOrDefault(false)
                        }
                        if (ok) {
                            playlists = playlists?.filter { it.id != item.id }
                        } else {
                            error = "删除失败，可能需要重新登录"
                        }
                    }
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } },
        )
    }

    if (creating) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { if (!busy) creating = false },
                title = { Text("新建歌单") },
                text = {
                    androidx.compose.material3.OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        singleLine = true,
                        placeholder = { Text("歌单名称") },
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        val n = newName.trim()
                        if (n.isEmpty() || busy) return@TextButton
                        busy = true
                        scope.launch {
                            val item = withContext(Dispatchers.IO) { runCatching { PlaylistApi.create(n) }.getOrNull() }
                            busy = false
                            creating = false
                            if (item != null) {
                                playlists = (playlists ?: emptyList()) + item
                                error = ""
                            } else {
                                error = "创建失败，可能需要重新登录"
                            }
                        }
                    }) { Text(if (busy) "处理中…" else "创建") }
                },
                dismissButton = { TextButton(onClick = { creating = false }) { Text("取消") } },
            )
        }

    if (current == null) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(AppTheme.dimens.pagePadding), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (showFav) "收藏的歌单" else "我的歌单",
                    fontSize = AppTheme.typography.pageTitle.fontSize,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { showFav = !showFav }) { Text(if (showFav) "我的歌单" else "收藏的歌单", fontSize = AppTheme.typography.bodySmall.fontSize) }
                if (!showFav) TextButton(onClick = { newName = ""; creating = true }) { Text("新建歌单", fontSize = AppTheme.typography.bodySmall.fontSize) }
            }
            if (showFav) {
                val favList = favs
                if (favList == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                } else if (favList.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("还没有收藏别人的歌单", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(favList) { item ->
                            Row(
                                Modifier.fillMaxWidth().clickable { Nav.push(Route.Playlist(item.id, item.name)) }
                                    .padding(horizontal = AppTheme.dimens.pagePadding, vertical = AppTheme.dimens.spacing14),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                SquareCover(item.cover)
                                Text(
                                    item.name, fontSize = AppTheme.typography.title.fontSize, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f).padding(start = 10.dp),
                                )
                                Text(item.count.toString() + " 首", fontSize = AppTheme.typography.caption.fontSize, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                return@Column
            }
            val list = playlists
            if (list == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else if (list.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(error.ifEmpty { "还没有歌单" }, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(list) { item ->
                        Row(
                            Modifier.fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .combinedClickable(onClick = { opened = item }, onLongClick = { pendingDelete = item })
                                .padding(horizontal = AppTheme.dimens.pagePadding, vertical = AppTheme.dimens.spacing14),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            SquareCover(item.cover)
                            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                                Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = AppTheme.typography.title.fontSize)
                                Text(
                                    item.count.toString() + " 首",
                                    fontSize = AppTheme.typography.caption.fontSize,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    } else {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = AppTheme.dimens.spacingSm, vertical = AppTheme.dimens.spacing6), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { opened = null }) { Text("返回") }
                Text(current.name, fontSize = AppTheme.typography.sectionTitle.fontSize, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            val list = songs
            if (list == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else {
                SongListView(
                    songs = list,
                    modifier = Modifier.weight(1f),
                    fixId = current.name,
                    onPlay = onPlay,
                    onDownload = onDownload,
                    // 自己的歌单才允许移出歌曲（收藏别人的歌单不动）
                    onRemove = { picked ->
                        scope.launch {
                            val dirId = current.dirId.toIntOrNull() ?: 0
                            val tid = current.id.toIntOrNull() ?: 0
                            val ok = withContext(Dispatchers.IO) {
                                runCatching { PlaylistApi.removeSongs(dirId, tid, picked) }.getOrDefault(false)
                            }
                            if (ok) {
                                val gone = picked.map { it.mid }.toSet()
                                songs = songs?.filterNot { gone.contains(it.mid) }
                                playlists = playlists?.map {
                                    if (it.id == current.id) it.copy(count = (it.count - picked.size).coerceAtLeast(0)) else it
                                }
                                opened = playlists?.firstOrNull { it.id == current.id } ?: current
                                android.widget.Toast.makeText(context, "已移出 " + picked.size + " 首", android.widget.Toast.LENGTH_SHORT).show()
                            } else {
                                android.widget.Toast.makeText(context, "移出失败，可能需要重新登录", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                )
            }
        }
    }
}
