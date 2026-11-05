package com.tianxiaodudou.qqmusicdownloader.ui

import com.tianxiaodudou.qqmusicdownloader.ui.AppSpinner

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil3.compose.AsyncImage
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tianxiaodudou.qqmusicdownloader.net.MusicApi
import com.tianxiaodudou.qqmusicdownloader.net.PlaylistApi
import com.tianxiaodudou.qqmusicdownloader.player.Favorites
import com.tianxiaodudou.qqmusicdownloader.ui.theme.AppTheme
import com.tianxiaodudou.qqmusicdownloader.player.PlayerController
import com.tianxiaodudou.qqmusicdownloader.net.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 行内图标按钮：28dp 点击区，紧凑排 4 个也不挤掉歌名。 */
@Composable
private fun RowAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean = true,
    tint: Color? = null,
    onClick: () -> Unit,
) {
    Box(
        Modifier.size(AppTheme.dimens.spacing32).clip(AppTheme.shapes.cover)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = if (enabled) (tint ?: AppTheme.colors.textSecondary) else AppTheme.colors.textDisabled,
            modifier = Modifier.size(AppTheme.dimens.iconSm),
        )
    }
}

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
    /** 传了就在多选工具条里多一个「移出歌单」（只有自己的歌单需要）。 */
    onRemove: ((List<MusicApi.Song>) -> Unit)? = null,
    /** 滑到底部继续加载（无限流）。 */
    onLoadMore: (() -> Unit)? = null,
    loadingMore: Boolean = false,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var picker by remember { mutableStateOf(false) }
    var createDialog by remember { mutableStateOf(false) }
    var faved by remember { mutableStateOf(false) }
    var moreFor by remember { mutableStateOf<MusicApi.Song?>(null) }

    LaunchedEffect(songs, fixId) {
        selected = emptySet()
        selecting = false
        faved = false
        moreFor = null
        Favorites.ensureLoaded()
        // 列表封面预加载，滚动到下一屏时不用现等
        withContext(Dispatchers.IO) {
            Covers.prefetch(
                context,
                songs.take(40).map { com.tianxiaodudou.qqmusicdownloader.ui.player.coverUrl(it.albumMid, 300) },
            )
        }
    }

    val policy = Settings.paidPolicy(context)
    val shown = if (policy == Settings.PaidPolicy.HIDE) songs.filter { !it.paid } else songs
    val hiddenCount = songs.size - shown.size
    val selectable = shown.filter { !it.paid }
    val chosen = shown.filter { selected.contains(it.mid) }

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
            Modifier.fillMaxWidth().padding(horizontal = AppTheme.dimens.spacingMd, vertical = AppTheme.dimens.spacing2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selecting) {
                Text("已选 " + selected.size + " 首", fontSize = AppTheme.typography.bodySmall.fontSize, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                AppTextButton(
                    onClick = { selected = selectable.map { it.mid }.toSet() },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = AppTheme.dimens.spacing6),
                ) { Text("全选", fontSize = AppTheme.typography.bodySmall.fontSize) }
                AppTextButton(
                    onClick = { selected = selectable.map { it.mid }.toSet() - selected },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = AppTheme.dimens.spacing6),
                ) { Text("反选", fontSize = AppTheme.typography.bodySmall.fontSize) }
                AppTextButton(
                    onClick = { finishSelection() },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = AppTheme.dimens.spacing6),
                ) { Text("取消", fontSize = AppTheme.typography.bodySmall.fontSize) }
            } else {
                Text(
                    if (hiddenCount > 0) shown.size.toString() + " 首 · 隐藏 " + hiddenCount + " 首付费" else shown.size.toString() + " 首",
                    fontSize = AppTheme.typography.bodySmall.fontSize,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                if (playlistId != null) {
                    AppTextButton(onClick = {
                        if (faved) return@AppTextButton
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) { runCatching { PlaylistApi.favPlaylist(playlistId) }.getOrDefault(false) }
                            faved = ok
                            toast(if (ok) "已收藏歌单" else "收藏失败，可能需要重新登录")
                        }
                    }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = AppTheme.dimens.spacing6)) {
                        Text(if (faved) "已收藏" else "收藏歌单", fontSize = AppTheme.typography.bodySmall.fontSize)
                    }
                }
                AppTextButton(onClick = {
                    if (selectable.isEmpty()) {
                        toast("没有可加入的歌曲")
                    } else {
                        PlayerController.enqueue(selectable)
                        toast("已把 " + selectable.size + " 首加入播放队列")
                    }
                }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = AppTheme.dimens.spacing6)) {
                    Text("全部入队", fontSize = AppTheme.typography.bodySmall.fontSize)
                }
                AppTextButton(onClick = {
                    selecting = true
                    selected = emptySet()
                }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = AppTheme.dimens.spacing6)) {
                    Text("多选", fontSize = AppTheme.typography.bodySmall.fontSize)
                }
            }
        }

        if (shown.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text(
                    if (hiddenCount > 0) "这里都是需要单独购买的付费内容" else "暂无歌曲",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            val listState = androidx.compose.foundation.lazy.rememberLazyListState()
            androidx.compose.runtime.LaunchedEffect(listState, shown.size, onLoadMore) {
                if (onLoadMore == null) return@LaunchedEffect
                snapshotFlow {
                    val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                    last to listState.layoutInfo.totalItemsCount
                }.collect { (last, total) ->
                    if (total > 0 && last >= total - 3 && !loadingMore) onLoadMore()
                }
            }
            LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().weight(1f)) {
                itemsIndexed(shown) { i, song ->
                    val checked = selected.contains(song.mid)
                    val access = com.tianxiaodudou.qqmusicdownloader.player.SongAccess.of(song)
                    val locked = song.paid || access.kind == com.tianxiaodudou.qqmusicdownloader.player.SongAccess.Kind.NONE
                    Row(
                        Modifier.fillMaxWidth()
                            .alpha(if (locked) 0.4f else 1f)
                            .clip(AppTheme.shapes.cardSmall)
                            .combinedClickable(
                                onClick = {
                                    when {
                                        locked -> toast("付费内容，购买后才能播放")
                                        selecting -> toggle(song)
                                        else -> onPlay(shown, i)
                                    }
                                },
                                onLongClick = {
                                    if (!locked && !selecting) {
                                        selecting = true
                                        selected = setOf(song.mid)
                                    }
                                },
                            )
                            .padding(start = if (selecting) AppTheme.dimens.spacing6 else AppTheme.dimens.pagePadding, end = AppTheme.dimens.pagePadding, top = AppTheme.dimens.spacing10, bottom = AppTheme.dimens.spacing10),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (selecting) {
                            Checkbox(checked = checked, enabled = !locked, onCheckedChange = { toggle(song) })
                        }
                        val playingThis = PlayerController.current?.mid == song.mid
                        Box(
                            Modifier.size(AppTheme.dimens.coverSm).clip(AppTheme.shapes.cover).background(AppTheme.colors.coverPlaceholder),
                            contentAlignment = Alignment.Center,
                        ) {
                            val cover = com.tianxiaodudou.qqmusicdownloader.ui.player.coverUrl(song.albumMid, 300)
                            // 没有封面/加载失败时也有个底，不至于空白
                            Text("♪", style = AppTheme.typography.title, color = AppTheme.colors.textTertiary)
                            if (cover.isNotEmpty()) {
                                AsyncImage(
                                    model = cover,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                            if (playingThis) {
                                Box(Modifier.fillMaxSize().background(AppTheme.colors.overlay), contentAlignment = Alignment.Center) {
                                    Text("♪", style = AppTheme.typography.body, color = AppTheme.colors.success)
                                }
                            }
                        }
                        Spacer(Modifier.width(AppTheme.dimens.spacingMd))
                        Column(Modifier.weight(1f)) {
                            Text(
                                song.name,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontSize = AppTheme.typography.title.fontSize,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                song.singers + "  ·  " + fmtDuration(song.interval),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontSize = AppTheme.typography.caption.fontSize,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (access.kind != com.tianxiaodudou.qqmusicdownloader.player.SongAccess.Kind.FREE) {
                            Text(
                                access.label,
                                fontSize = AppTheme.typography.badge.fontSize,
                                color = AppTheme.colors.warning,
                                modifier = Modifier
                                    .clip(AppTheme.shapes.tag)
                                    .background(AppTheme.colors.warningSoft)
                                    .padding(horizontal = AppTheme.dimens.spacing5, vertical = AppTheme.dimens.spacing2),
                            )
                        }
                        if (!selecting) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RowAction(Icons.Rounded.QueueMusic, "加入队列", enabled = !locked) {
                                    PlayerController.enqueue(listOf(song))
                                    toast("已加入播放队列")
                                }
                                val fav = Favorites.isFav(song.mid)
                                RowAction(
                                    if (fav) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                                    if (fav) "取消收藏" else "收藏",
                                    tint = if (fav) AppTheme.colors.error else AppTheme.colors.textSecondary,
                                ) {
                                    scope.launch {
                                        val ok = Favorites.toggle(song)
                                        toast(
                                            when {
                                                !ok -> "操作失败，可能需要重新登录"
                                                Favorites.isFav(song.mid) -> "已收藏到「我喜欢」"
                                                else -> "已取消收藏"
                                            }
                                        )
                                    }
                                }
                                RowAction(Icons.Rounded.Download, "下载", enabled = !locked) { onDownload(song) }
                                Box {
                                    RowAction(Icons.Rounded.MoreVert, "更多") { moreFor = song }
                                    DropdownMenu(
                                        expanded = moreFor?.mid == song.mid,
                                        onDismissRequest = { moreFor = null },
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text("收藏到歌单", fontSize = AppTheme.typography.bodySmall.fontSize) },
                                            onClick = {
                                                moreFor = null
                                                selected = setOf(song.mid)
                                                selecting = true
                                                picker = true
                                            },
                                        )
                                        DropdownMenuItem(
                                            text = { Text("查看歌手", fontSize = AppTheme.typography.bodySmall.fontSize) },
                                            onClick = {
                                                moreFor = null
                                                if (song.singerMid.isNotEmpty()) Nav.push(Route.Singer(song.singerMid, song.singers))
                                            },
                                        )
                                        DropdownMenuItem(
                                            text = { Text("查看专辑", fontSize = AppTheme.typography.bodySmall.fontSize) },
                                            onClick = {
                                                moreFor = null
                                                if (song.albumMid.isNotEmpty()) Nav.push(Route.Album(song.albumMid, song.album))
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                if (onLoadMore != null) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(AppTheme.dimens.spacing14), contentAlignment = Alignment.Center) {
                            if (loadingMore) {
                                AppSpinner(
                                    modifier = Modifier.size(AppTheme.dimens.iconMd),
                                    strokeWidth = AppTheme.dimens.stroke2,
                                )
                            } else {
                                Text("上滑继续加载", fontSize = AppTheme.typography.caption.fontSize, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }

        if (selecting) {
            Row(
                Modifier.fillMaxWidth().background(AppTheme.colors.surface2)
                    .padding(horizontal = AppTheme.dimens.spacingSm, vertical = AppTheme.dimens.spacing6),
                horizontalArrangement = Arrangement.spacedBy(AppTheme.dimens.spacing6),
            ) {
                AppPrimaryButton("加入队列", Modifier.weight(1f)) {
                    if (chosen.isEmpty()) {
                        toast("先选几首")
                        return@AppPrimaryButton
                    }
                    PlayerController.enqueue(chosen)
                    toast("已加入播放队列 " + chosen.size + " 首")
                    finishSelection()
                }
                AppPrimaryButton("收藏到歌单", Modifier.weight(1f)) {
                    if (chosen.isEmpty()) toast("先选几首") else picker = true
                }
                AppPrimaryButton("下载", Modifier.weight(1f)) {
                    if (chosen.isEmpty()) toast("先选几首") else chosen.forEach { onDownload(it) }
                    finishSelection()
                }
                if (onRemove != null) {
                    AppPrimaryButton("移出歌单", Modifier.weight(1f)) {
                        if (chosen.isEmpty()) {
                            toast("先选几首")
                        } else {
                            onRemove(chosen)
                            finishSelection()
                        }
                    }
                }
            }
        }
    }

    if (picker) {
        AppSheet(onDismiss = { picker = false }) {
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
                AppInput(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    placeholder = "歌单名称",
                )
            },
            confirmButton = {
                AppTextButton(onClick = {
                    val n = name.trim()
                    if (n.isEmpty() || busy) return@AppTextButton
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
            dismissButton = { AppTextButton(onClick = { createDialog = false }) { Text("取消") } },
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
    Column(Modifier.fillMaxWidth().fillMaxHeight(0.6f).padding(bottom = AppTheme.dimens.spacingMd)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = AppTheme.dimens.spacing20, vertical = AppTheme.dimens.spacingSm), verticalAlignment = Alignment.CenterVertically) {
            Text("收藏到歌单", fontSize = AppTheme.typography.sectionTitle.fontSize, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            AppTextButton(onClick = onNew) { Text("新建歌单", fontSize = AppTheme.typography.bodySmall.fontSize) }
        }
        val data = lists
        if (data == null) {
            LoadingBox(Modifier.fillMaxSize())
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(data) { item ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onPick(item) }
                            .padding(horizontal = AppTheme.dimens.spacing20, vertical = AppTheme.dimens.spacingMd),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(item.name, fontSize = AppTheme.typography.body.fontSize, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text(item.count.toString() + " 首", fontSize = AppTheme.typography.caption.fontSize, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
