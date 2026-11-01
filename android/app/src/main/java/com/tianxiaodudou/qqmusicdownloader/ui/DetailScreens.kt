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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.tianxiaodudou.qqmusicdownloader.net.AlbumApi
import com.tianxiaodudou.qqmusicdownloader.net.Extras
import com.tianxiaodudou.qqmusicdownloader.net.MusicApi
import com.tianxiaodudou.qqmusicdownloader.net.PlaylistApi
import com.tianxiaodudou.qqmusicdownloader.net.SingerApi
import com.tianxiaodudou.qqmusicdownloader.ui.player.coverUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun DetailScaffold(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    BackHandler { onBack() }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(AppTheme.dimens.topBarHeight), verticalAlignment = Alignment.CenterVertically) {
            AppIconButton(onClick = onBack, modifier = Modifier.size(AppTheme.dimens.touchTarget)) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
            }
            Text(title, fontSize = AppTheme.typography.sectionTitle.fontSize, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        content()
    }
}

@Composable
fun AlbumScreen(mid: String, name: String, onPlay: (List<MusicApi.Song>, Int) -> Unit, onDownload: (MusicApi.Song) -> Unit) {
    val scope = rememberCoroutineScope()
    var detail by remember(mid) { mutableStateOf<Extras.AlbumDetail?>(null) }
    var songs by remember(mid) { mutableStateOf<List<MusicApi.Song>?>(null) }
    var faved by remember(mid) { mutableStateOf(false) }

    LaunchedEffect(mid) {
        withContext(Dispatchers.IO) {
            detail = runCatching { Extras.albumDetail(mid) }.getOrNull()
            songs = runCatching { AlbumApi.songs(mid, 100) }.getOrNull() ?: emptyList()
        }
    }

    DetailScaffold(detail?.name?.ifEmpty { name } ?: name, onBack = { Nav.pop() }) {
        val d = detail
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = AppTheme.dimens.pagePadding, vertical = AppTheme.dimens.spacing6), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(AppTheme.dimens.coverHero).clip(AppTheme.shapes.cardSmall).background(AppTheme.colors.coverPlaceholder)) {
                    val cover = d?.cover?.takeIf { it.startsWith("http") } ?: coverUrl(mid)
                    AsyncImage(model = cover, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
                Spacer(Modifier.width(AppTheme.dimens.spacing14))
                Column(Modifier.weight(1f)) {
                    Text(d?.name?.ifEmpty { name } ?: name, fontSize = AppTheme.typography.sectionTitle.fontSize, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(d?.singer.orEmpty(), fontSize = AppTheme.typography.bodySmall.fontSize, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    Text(
                        listOfNotNull(d?.publish?.takeIf { it.isNotEmpty() }, d?.genre?.takeIf { it.isNotEmpty() })
                            .joinToString(" · "),
                        fontSize = AppTheme.typography.caption.fontSize,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        d?.company?.takeIf { it.isNotEmpty() } ?: "",
                        fontSize = AppTheme.typography.caption.fontSize,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
            if (!d?.desc.isNullOrEmpty()) {
                Text(
                    d!!.desc,
                    fontSize = AppTheme.typography.caption.fontSize,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = AppTheme.dimens.pagePadding, vertical = AppTheme.dimens.spacingXs),
                )
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = AppTheme.dimens.pagePadding, vertical = AppTheme.dimens.spacingSm), horizontalArrangement = Arrangement.spacedBy(AppTheme.dimens.spacing10)) {
                AppPrimaryButton(onClick = { songs?.let { if (it.isNotEmpty()) onPlay(it, 0) } }, modifier = Modifier.weight(1f)) {
                    Text("播放全部", fontSize = AppTheme.typography.bodySmall.fontSize)
                }
                AppSecondaryButton(onClick = {
                    val id = d?.id ?: 0L
                    if (id <= 0L) return@AppSecondaryButton
                    scope.launch {
                        faved = withContext(Dispatchers.IO) { Extras.favAlbum(id, !faved) }
                    }
                }, modifier = Modifier.weight(1f)) {
                    Text(if (faved) "已收藏" else "收藏专辑", fontSize = AppTheme.typography.bodySmall.fontSize)
                }
            }
            val list = songs
            if (list == null) {
                LoadingBox(Modifier.fillMaxSize())
            } else {
                SongListView(
                    songs = list,
                    modifier = Modifier.weight(1f),
                    fixId = mid,
                    onPlay = onPlay,
                    onDownload = onDownload,
                )
            }
        }
    }
}

@Composable
fun SingerScreen(mid: String, name: String, onPlay: (List<MusicApi.Song>, Int) -> Unit, onDownload: (MusicApi.Song) -> Unit) {
    var info by remember(mid) { mutableStateOf<Extras.SingerInfo?>(null) }
    var desc by remember(mid) { mutableStateOf("") }
    var songs by remember(mid) { mutableStateOf<List<MusicApi.Song>?>(null) }
    var albums by remember(mid) { mutableStateOf<List<AlbumApi.Album>>(emptyList()) }
    var similar by remember(mid) { mutableStateOf<List<SingerApi.Singer>>(emptyList()) }
    var tab by remember { mutableIntStateOf(0) }

    LaunchedEffect(mid) {
        withContext(Dispatchers.IO) {
            info = runCatching { Extras.singerInfo(mid) }.getOrNull()
            desc = runCatching { Extras.singerDesc(mid) }.getOrDefault("")
            songs = runCatching { SingerApi.songs(mid, 50) }.getOrNull() ?: emptyList()
            albums = runCatching { Extras.singerAlbums(mid) }.getOrDefault(emptyList())
            similar = runCatching { Extras.similarSingers(mid) }.getOrDefault(emptyList())
        }
    }

    DetailScaffold(info?.name?.ifEmpty { name } ?: name, onBack = { Nav.pop() }) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = AppTheme.dimens.pagePadding, vertical = AppTheme.dimens.spacing6), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(AppTheme.dimens.coverXxl).clip(RoundedCornerShape(50)).background(AppTheme.colors.coverPlaceholder)) {
                    val a = info?.avatar.orEmpty()
                    if (a.startsWith("http")) {
                        AsyncImage(model = a, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    }
                }
                Spacer(Modifier.width(AppTheme.dimens.spacing14))
                Column(Modifier.weight(1f)) {
                    Text(info?.name?.ifEmpty { name } ?: name, fontSize = AppTheme.typography.sectionTitle.fontSize, fontWeight = FontWeight.SemiBold)
                    Text(
                        listOfNotNull(
                            info?.songs?.takeIf { it.isNotEmpty() }?.let { it + " 首" },
                            info?.fans?.takeIf { it.isNotEmpty() }?.let { it + " 粉丝" },
                        ).joinToString(" · "),
                        fontSize = AppTheme.typography.caption.fontSize,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (desc.isNotEmpty()) {
                Text(
                    desc,
                    fontSize = AppTheme.typography.caption.fontSize,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = AppTheme.dimens.pagePadding, vertical = AppTheme.dimens.spacingXs),
                )
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = AppTheme.dimens.spacingMd, vertical = AppTheme.dimens.spacing6)) {
                listOf("热门歌曲", "专辑", "相似歌手").forEachIndexed { i, t ->
                    AppTextButton(onClick = { tab = i }) {
                        Text(
                            t,
                            fontSize = AppTheme.typography.bodySmall.fontSize,
                            color = if (tab == i) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                if (tab == 0) AppTextButton(onClick = { songs?.let { if (it.isNotEmpty()) onPlay(it, 0) } }) { Text("播放全部", fontSize = AppTheme.typography.bodySmall.fontSize) }
            }
            when (tab) {
                0 -> {
                    val list = songs
                    if (list == null) LoadingBox(Modifier.fillMaxSize())
                    else SongListView(list, Modifier.weight(1f), fixId = mid, onPlay = onPlay, onDownload = onDownload)
                }
                1 -> LazyColumn(Modifier.fillMaxSize()) {
                    items(albums) { a ->
                        Row(
                            Modifier.fillMaxWidth().clickable { Nav.push(Route.Album(a.mid, a.name)) }
                                .padding(horizontal = AppTheme.dimens.pagePadding, vertical = AppTheme.dimens.spacing10),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.size(AppTheme.dimens.coverLg).clip(AppTheme.shapes.cover).background(AppTheme.colors.coverPlaceholder)) {
                                AsyncImage(
                                    model = a.cover.takeIf { it.startsWith("http") } ?: coverUrl(a.mid),
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                            Spacer(Modifier.width(AppTheme.dimens.spacingMd))
                            Column(Modifier.weight(1f)) {
                                Text(a.name, fontSize = AppTheme.typography.body.fontSize, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(a.singer, fontSize = AppTheme.typography.caption.fontSize, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                            }
                        }
                    }
                }
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(similar) { s ->
                        Row(
                            Modifier.fillMaxWidth().clickable { Nav.push(Route.Singer(s.mid, s.name)) }
                                .padding(horizontal = AppTheme.dimens.pagePadding, vertical = AppTheme.dimens.spacingMd),
                        ) { Text(s.name, fontSize = AppTheme.typography.body.fontSize) }
                    }
                }
            }
        }
    }
}

@Composable
fun PlaylistScreen(id: String, name: String, onPlay: (List<MusicApi.Song>, Int) -> Unit, onDownload: (MusicApi.Song) -> Unit) {
    var songs by remember(id) { mutableStateOf<List<MusicApi.Song>?>(null) }
    var faved by remember(id) { mutableStateOf(false) }
    var rname by remember(id) { mutableStateOf(name) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(id) {
        withContext(Dispatchers.IO) {
            val item = PlaylistApi.Item(id, "0", name, 0)
            val res = runCatching { PlaylistApi.songs(item) }.getOrNull() ?: emptyList()
            songs = res
        }
    }

    DetailScaffold(rname, onBack = { Nav.pop() }) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = AppTheme.dimens.pagePadding, vertical = AppTheme.dimens.spacingSm), verticalAlignment = Alignment.CenterVertically) {
                Text("共 " + (songs?.size ?: 0) + " 首", fontSize = AppTheme.typography.bodySmall.fontSize, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                AppTextButton(onClick = {
                    if (faved) return@AppTextButton
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) {
                            runCatching { PlaylistApi.favPlaylist(id.toLongOrNull() ?: 0L) }.getOrDefault(false)
                        }
                        faved = ok
                    }
                }) { Text(if (faved) "已收藏" else "收藏歌单", fontSize = AppTheme.typography.bodySmall.fontSize) }
                AppTextButton(onClick = { songs?.let { if (it.isNotEmpty()) onPlay(it, 0) } }) { Text("播放全部", fontSize = AppTheme.typography.bodySmall.fontSize) }
            }
            val list = songs
            if (list == null) {
                LoadingBox(Modifier.fillMaxSize())
            } else {
                SongListView(list, Modifier.weight(1f), fixId = id, onPlay = onPlay, onDownload = onDownload)
            }
        }
    }
}

@Composable
fun FavLibraryScreen(albums: Boolean, onPlay: (List<MusicApi.Song>, Int) -> Unit, onDownload: (MusicApi.Song) -> Unit) {
    var list by remember(albums) { mutableStateOf<List<Extras.AlbumDetail>?>(null) }
    var playlists by remember(albums) { mutableStateOf<List<PlaylistApi.Item>?>(null) }
    LaunchedEffect(albums) {
        withContext(Dispatchers.IO) {
            if (albums) list = runCatching { Extras.favAlbums() }.getOrDefault(emptyList())
            else playlists = runCatching { Extras.favPlaylists() }.getOrDefault(emptyList())
                .let { PlaylistApi.withCovers(it) }
        }
    }
    DetailScaffold(if (albums) "收藏的专辑" else "收藏的歌单", onBack = { Nav.pop() }) {
        if (albums) {
            val data = list
            if (data == null) LoadingBox(Modifier.fillMaxSize())
            else if (data.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("暂无收藏", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            else LazyColumn(Modifier.fillMaxSize()) {
                items(data) { a ->
                    Row(
                        Modifier.fillMaxWidth().clickable { Nav.push(Route.Album(a.mid, a.name)) }
                            .padding(horizontal = AppTheme.dimens.pagePadding, vertical = AppTheme.dimens.spacing10),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(AppTheme.dimens.coverLg).clip(AppTheme.shapes.cover).background(AppTheme.colors.coverPlaceholder)) {
                            AsyncImage(
                                model = a.cover.takeIf { it.startsWith("http") } ?: coverUrl(a.mid),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                        Spacer(Modifier.width(AppTheme.dimens.spacingMd))
                        Column(Modifier.weight(1f)) {
                            Text(a.name, fontSize = AppTheme.typography.body.fontSize, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(a.singer, fontSize = AppTheme.typography.caption.fontSize, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        }
                    }
                }
            }
        } else {
            val data = playlists
            if (data == null) LoadingBox(Modifier.fillMaxSize())
            else if (data.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("暂无收藏", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            else LazyColumn(Modifier.fillMaxSize()) {
                items(data) { p ->
                    Row(
                        Modifier.fillMaxWidth().clickable { Nav.push(Route.Playlist(p.id, p.name)) }
                            .padding(horizontal = AppTheme.dimens.pagePadding, vertical = AppTheme.dimens.spacingMd),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SquareCover(p.cover)
                        Text(
                            p.name, fontSize = AppTheme.typography.body.fontSize, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f).padding(start = AppTheme.dimens.spacingMd),
                        )
                        Text(p.count.toString() + " 首", fontSize = AppTheme.typography.caption.fontSize, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
