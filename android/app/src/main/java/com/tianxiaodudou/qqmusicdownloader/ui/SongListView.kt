package com.tianxiaodudou.qqmusicdownloader.ui

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tianxiaodudou.qqmusicdownloader.net.MusicApi
import com.tianxiaodudou.qqmusicdownloader.net.PlaylistApi
import com.tianxiaodudou.qqmusicdownloader.player.PlayerController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 通用歌曲列表：支持多选（全选 / 反选）、批量加入播放队列、收藏到歌单、新建歌单、批量下载。
 * 任何展示歌曲的地方都复用它，避免每个页面各写一套多选。
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun SongListView(
    songs: List<MusicApi.Song>,
    modifier: Modifier = Modifier,
    header: (@Composable () -> Unit)? = null,
    fixId: String = "",
    playlistId: Long? = null,
    onPlay: (List<MusicApi.Song>, Int) -> Unit,
    onDownload: (MusicApi.Song) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var picker by remember { mutableStateOf(false) }
    var createDialog by remember { mutableStateOf(false) }
    var faved by remember { mutableStateOf(false) }

    LaunchedEffect(songs, fixId) {
        selected = emptySet()
        selecting = false
        faved = false
    }

    val chosen = songs.filter { selected.contains(it.mid) }

    fun toggle(song: MusicApi.Song) {
        selected = if (selected.contains(song.mid)) selected - song.mid else selected + song.mid
    }

    fun toast(msg: String) {
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    }

    fun finishSelection() {
        selecting = false
        selected = emptySet()
    }

    Column(modifier.fillMaxSize()) {
        header?.invoke()

        // 工具条：未选择时给「多选」和「收藏歌单」，选择中给全选/反选/取消
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selecting) {
                Text("已选 " + selected.size + " 首", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick = { selected = songs.map { it.mid }.toSet() },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp),
                ) { Text("全选", fontSize = 12.sp) }
                TextButton(
                    onClick = { selected = songs.map { it.mid }.toSet() - selected },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp),
                ) { Text("反选", fontSize = 12.sp) }
                TextButton(
                    onClick = { finishSelection() },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp),
                ) { Text("取消", fontSize = 12.sp) }
            } else {
                Text(songs.size.toString() + " 首", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                if (playlistId != null) {
                    TextButton(onClick = {
                        if (faved) return@TextButton
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) { runCatching { PlaylistApi.favPlaylist(playlistId) }.getOrDefault(false) }
                            faved = ok
                            toast(if (ok) "已收藏歌单" else "收藏失败，可能需要重新登录")
                        }
                    }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp)) {
                        Text(if (faved) "已收藏" else "收藏歌单", fontSize = 12.sp)
                    }
                }
                TextButton(onClick = {
                    PlayerController.enqueue(songs)
                    toast("已把 " + songs.size + " 首加入播放队列")
                }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp)) {
                    Text("全部入队", fontSize = 12.sp)
                }
                TextButton(onClick = {
                    selecting = true
                    selected = emptySet()
                }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp)) {
                    Text("多选", fontSize = 12.sp)
                }
            }
        }

        if (songs.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text("暂无歌曲", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                itemsIndexed(songs) { i, song ->
                    val checked = selected.contains(song.mid)
                    Row(
                        Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .combinedClickable(
                                onClick = {
                                    if (selecting) toggle(song) else onPlay(songs, i)
                                },
                                onLongClick = {
                                    if (!selecting) {
                                        selecting = true
                                        selected = setOf(song.mid)
                                    }
                                },
                            )
                            .padding(start = if (selecting) 6.dp else 16.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (selecting) {
                            Checkbox(checked = checked, onCheckedChange = { toggle(song) })
                        }
                        Column(Modifier.weight(1f)) {
                            Text(
                                song.name,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontSize = 15.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                song.singers,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(fmtDuration(song.interval), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        if (selecting) {
            Row(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Button(
                    onClick = {
                        if (chosen.isEmpty()) {
                            toast("先选几首")
                            return@Button
                        }
                        PlayerController.enqueue(chosen)
                        toast("已加入播放队列 " + chosen.size + " 首")
                        finishSelection()
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("加入队列", fontSize = 12.sp) }
                Button(
                    onClick = {
                        if (chosen.isEmpty()) {
                            toast("先选几首")
                        } else {
                            picker = true
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("收藏到歌单", fontSize = 12.sp) }
                Button(
                    onClick = {
                        if (chosen.isEmpty()) toast("先选几首") else chosen.forEach { onDownload(it) }
                        finishSelection()
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("下载", fontSize = 12.sp) }
            }
        }
    }

    if (picker) {
        val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { picker = false },
            sheetState = state,
            containerColor = MaterialTheme.colorScheme.surface,
            dragHandle = { BottomSheetDefaults.DragHandle() },
        ) {
            PlaylistPickerSheet(
                onDismiss = { picker = false },
                onNew = {
                    picker = false
                    createDialog = true
                },
                onPick = { item ->
                    picker = false
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) {
                            runCatching {
                                PlaylistApi.addSongs(
                                    item.dirId.toIntOrNull() ?: 0,
                                    item.id.toIntOrNull() ?: 0,
                                    chosen,
                                )
                            }.getOrDefault(false)
                        }
                        toast(if (ok) "已加入「" + item.name + "」" else "加入失败，可能需要重新登录")
                        finishSelection()
                    }
                },
            )
        }
    }

    if (createDialog) {
        var name by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { if (!busy) createDialog = false },
            title = { Text("新建歌单") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    placeholder = { Text("歌单名称") },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val n = name.trim()
                    if (n.isEmpty() || busy) return@TextButton
                    busy = true
                    scope.launch {
                        val item = withContext(Dispatchers.IO) { runCatching { PlaylistApi.create(n) }.getOrNull() }
                        if (item == null) {
                            busy = false
                            toast("创建失败，可能需要重新登录")
                            return@launch
                        }
                        val ok = withContext(Dispatchers.IO) {
                            runCatching {
                                PlaylistApi.addSongs(item.dirId.toIntOrNull() ?: 0, item.id.toIntOrNull() ?: 0, chosen)
                            }.getOrDefault(false)
                        }
                        busy = false
                        createDialog = false
                        toast(if (ok) "已创建「" + n + "」并加入 " + chosen.size + " 首" else "歌单已创建，加歌失败")
                        finishSelection()
                    }
                }) { Text(if (busy) "处理中…" else "创建并加入") }
            },
            dismissButton = { TextButton(onClick = { createDialog = false }) { Text("取消") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaylistPickerSheet(
    onDismiss: () -> Unit,
    onNew: () -> Unit,
    onPick: (PlaylistApi.Item) -> Unit,
) {
    var lists by remember { mutableStateOf<List<PlaylistApi.Item>?>(null) }
    LaunchedEffect(Unit) {
        lists = withContext(Dispatchers.IO) { runCatching { PlaylistApi.mine() }.getOrNull() ?: emptyList() }
    }
    Column(Modifier.fillMaxWidth().fillMaxHeight(0.6f).padding(bottom = 12.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("收藏到歌单", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            TextButton(onClick = onNew) { Text("新建歌单", fontSize = 12.sp) }
        }
        val data = lists
        if (data == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(data) { item ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onPick(item) }
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(item.name, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text(item.count.toString() + " 首", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

private fun fmtDuration(seconds: Int): String {
    if (seconds <= 0) return ""
    return String.format("%02d:%02d", seconds / 60, seconds % 60)
}
