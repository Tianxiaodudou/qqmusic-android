package com.tianxiaodudou.qqmusicdownloader.ui

import com.tianxiaodudou.qqmusicdownloader.ui.AppSpinner

import com.tianxiaodudou.qqmusicdownloader.ui.theme.AppTheme

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FiberNew
import androidx.compose.material.icons.rounded.Leaderboard
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.tianxiaodudou.qqmusicdownloader.net.AlbumApi
import com.tianxiaodudou.qqmusicdownloader.net.MusicApi
import com.tianxiaodudou.qqmusicdownloader.net.PlaylistApi
import com.tianxiaodudou.qqmusicdownloader.net.RecommendApi
import com.tianxiaodudou.qqmusicdownloader.net.Settings
import com.tianxiaodudou.qqmusicdownloader.net.SingerApi
import com.tianxiaodudou.qqmusicdownloader.net.TopApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun entryIcons(): Map<String, androidx.compose.ui.graphics.vector.ImageVector> = mapOf(
    "推荐歌单" to Icons.Rounded.QueueMusic,
    "新歌速递" to Icons.Rounded.FiberNew,
    "每日推荐" to Icons.Rounded.AutoAwesome,
    "新碟上架" to Icons.Rounded.Album,
    "热门歌手" to Icons.Rounded.Person,
    "热搜榜" to Icons.Rounded.LocalFireDepartment,
    "排行榜" to Icons.Rounded.Leaderboard,
    "猜你喜欢" to Icons.Rounded.Favorite,
    "为你推荐" to Icons.Rounded.Explore,
)

private sealed interface HomeView {
    data object Feed : HomeView
    data object Recommend : HomeView
    data object Guess : HomeView
    data object Radar : HomeView
    data class Chart(val chart: TopApi.Chart) : HomeView
    data class Songlist(val item: RecommendApi.Songlist) : HomeView
    data class Card(val card: RecommendApi.FeedCard) : HomeView
    data object Albums : HomeView
    data object Singers : HomeView
    data class SingerSongs(val singer: SingerApi.Singer) : HomeView
    data class Hot(val kw: String = "") : HomeView
    data object Songlists : HomeView
    data object NewSongs : HomeView
    data object Charts : HomeView
    data class AlbumSongs(val album: AlbumApi.Album) : HomeView
}

@Composable
fun HomeScreen(
    onPlay: (List<MusicApi.Song>, Int) -> Unit,
    onDownload: (MusicApi.Song) -> Unit,
    onSearch: (String) -> Unit = {},
    onOpenSearch: () -> Unit = {},
) {
    var charts by remember { mutableStateOf(HomeCache.charts) }
    var songlists by remember { mutableStateOf(HomeCache.songlists) }
    var newSongs by remember { mutableStateOf(HomeCache.newSongs) }
    var feed by remember { mutableStateOf(HomeCache.feed) }
    var albums by remember { mutableStateOf(HomeCache.albums) }
    var hot by remember { mutableStateOf(HomeCache.hot) }
    var singers by remember { mutableStateOf(HomeCache.singers) }
    // 模块页也是分层的（模块 -> 榜单/专辑/歌手 -> 歌曲），用一个栈记录，返回逐级回退
    var viewStack by remember { mutableStateOf(listOf<HomeView>(HomeView.Feed)) }
    val view = viewStack.last()
    fun open(v: HomeView) { viewStack = viewStack + v }
    fun back() { if (viewStack.size > 1) viewStack = viewStack.dropLast(1) }
    // 模块页逐级返回（模块 -> 榜单/专辑/歌手 -> 歌曲），到首页才交给上层
    BackHandler(enabled = viewStack.size > 1) { back() }
    var songs by remember { mutableStateOf<List<MusicApi.Song>?>(null) }
    var error by remember { mutableStateOf("") }

    val context = androidx.compose.ui.platform.LocalContext.current
    fun on(key: String) = Settings.homeEnabled(context, key)
    val PAGE = Settings.PAGE_SIZE
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var moreLoading by remember { mutableStateOf(false) }
    val cfgVersion = HomeConfig.version

    fun keyOf(v: HomeView): String = when (v) {
        is HomeView.Guess -> "guess"
        is HomeView.Radar -> "radar"
        is HomeView.Chart -> "chart:" + v.chart.id
        is HomeView.Songlist -> "songlist:" + v.item.id
        is HomeView.AlbumSongs -> "album:" + v.album.mid
        is HomeView.Hot -> "hot:" + v.kw
        is HomeView.SingerSongs -> "singer:" + v.singer.mid
        is HomeView.Card -> "card:" + v.card.id
        else -> "feed"
    }

    suspend fun fetchPage(v: HomeView, page: Int): List<MusicApi.Song> = withContext(Dispatchers.IO) {
        runCatching {
            when (v) {
                is HomeView.Guess -> RecommendApi.guessPage(page, PAGE)
                is HomeView.Radar -> RecommendApi.radar(page)
                is HomeView.Chart -> TopApi.songs(v.chart, page = page, num = PAGE)
                is HomeView.Songlist -> PlaylistApi.songs(
                    PlaylistApi.Item(v.item.id, v.item.dirId, v.item.name, v.item.songNum, v.item.pic),
                    page = page, num = PAGE,
                )
                is HomeView.AlbumSongs -> AlbumApi.songs(v.album.mid, num = PAGE, page = page)
                is HomeView.Hot -> MusicApi.legacySearch(v.kw, page, PAGE)
                is HomeView.SingerSongs -> SingerApi.songs(v.singer.mid, num = PAGE, page = page)
                is HomeView.Card -> {
                    if (v.card.kind == "song") {
                        val sid = v.card.id.toLongOrNull()
                        if (sid == null) emptyList() else listOfNotNull(MusicApi.detail(sid))
                    } else {
                        PlaylistApi.songs(PlaylistApi.Item(v.card.id, "0", v.card.name, 0), page = page, num = PAGE)
                    }
                }
                else -> emptyList()
            }
        }.getOrDefault(emptyList())
    }

    fun loadMore(v: HomeView) {
        if (moreLoading) return
        val key = keyOf(v)
        if (HomeCache.exhausted.contains(key)) return
        val page = (HomeCache.pageNos[key] ?: 1) + 1
        moreLoading = true
        scope.launch {
            val more = fetchPage(v, page)
            val known = HomeCache.songPages[key] ?: emptyList()
            val fresh = more.filter { s -> known.none { it.mid == s.mid } }
            if (fresh.isEmpty()) {
                HomeCache.exhausted.add(key)
            } else {
                val merged = known + fresh
                HomeCache.songPages[key] = merged
                HomeCache.pageNos[key] = page
                songs = merged
            }
            moreLoading = false
        }
    }

    fun loadMoreSonglists() {
        if (moreLoading || HomeCache.exhausted.contains("songlists")) return
        moreLoading = true
        scope.launch {
            val page = (HomeCache.pageNos["songlists"] ?: 1) + 1
            val more = withContext(Dispatchers.IO) {
                runCatching { RecommendApi.songlists(page = page, num = PAGE) }.getOrDefault(emptyList())
            }
            if (more.isEmpty()) {
                HomeCache.exhausted.add("songlists")
            } else {
                val merged = (songlists ?: emptyList()) + more.filter { m -> (songlists ?: emptyList()).none { it.id == m.id } }
                songlists = merged
                HomeCache.songlists = merged
                HomeCache.pageNos["songlists"] = page
            }
            moreLoading = false
        }
    }

    fun loadMoreAlbums() {
        if (moreLoading || HomeCache.exhausted.contains("albums")) return
        moreLoading = true
        scope.launch {
            val page = (HomeCache.pageNos["albums"] ?: 1) + 1
            val more = withContext(Dispatchers.IO) {
                runCatching { AlbumApi.newAlbums(num = PAGE, page = page) }.getOrDefault(emptyList())
            }
            if (more.isEmpty()) {
                HomeCache.exhausted.add("albums")
            } else {
                val merged = (albums ?: emptyList()) + more.filter { m -> (albums ?: emptyList()).none { it.mid == m.mid } }
                albums = merged
                HomeCache.albums = merged
                HomeCache.pageNos["albums"] = page
            }
            moreLoading = false
        }
    }

    LaunchedEffect(cfgVersion) {
        val force = HomeConfig.takeForce()
        if (!force && HomeCache.charts != null) return@LaunchedEffect
        if (force) HomeCache.clear()
        withContext(Dispatchers.IO) {
            if (on("charts")) runCatching { TopApi.charts() }.onSuccess { charts = it }.onFailure { charts = emptyList() } else charts = emptyList()
            if (on("songlists")) {
                runCatching { RecommendApi.songlists(page = 1, num = PAGE) }
                    .onSuccess { l ->
                        songlists = l
                        Covers.prefetch(context, l.map { it.pic })
                    }
                    .onFailure { songlists = emptyList() }
            } else songlists = emptyList()
            if (on("newsongs")) runCatching { RecommendApi.newSongsUpTo(PAGE) }.onSuccess { newSongs = it }.onFailure { newSongs = emptyList() } else newSongs = emptyList()
            if (on("feed")) {
                runCatching { RecommendApi.homeFeed(limit = PAGE) }
                    .onSuccess { g ->
                        feed = g
                        Covers.prefetch(context, g.flatMap { it.cards.map { c -> c.cover } })
                    }
                    .onFailure { feed = emptyList() }
            } else feed = emptyList()
            if (on("albums")) runCatching { AlbumApi.newAlbums(num = PAGE, page = 1) }
                .onSuccess { albums = it; Covers.prefetch(context, it.map { a -> a.cover }) }
                .onFailure { albums = emptyList() }
            if (on("singers")) runCatching { SingerApi.list() }.onSuccess { singers = it.take(PAGE) }.onFailure { singers = emptyList() }
            if (on("hot")) runCatching { MusicApi.hotKeys() }.onSuccess { hot = it.take(PAGE) }.onFailure { hot = emptyList() }
        }
        if (charts.isNullOrEmpty() && songlists.isNullOrEmpty() && newSongs.isNullOrEmpty()) {
            error = "推荐加载失败，请检查登录状态"
        }
        HomeCache.charts = charts
        HomeCache.songlists = songlists
        HomeCache.newSongs = newSongs
        HomeCache.feed = feed
        HomeCache.albums = albums
        HomeCache.singers = singers
        HomeCache.hot = hot
    }

    LaunchedEffect(view) {
        val v = view
        if (v is HomeView.Feed || v is HomeView.Recommend) return@LaunchedEffect
        if (v is HomeView.Hot && v.kw.isEmpty()) {
            if (hot.isNullOrEmpty()) {
                hot = null
                hot = withContext(Dispatchers.IO) {
                    runCatching { MusicApi.hotKeys() }.getOrDefault(emptyList()).take(PAGE)
                }
            }
            return@LaunchedEffect
        }
        if (v is HomeView.NewSongs) {
            songs = newSongs ?: emptyList()
            return@LaunchedEffect
        }
        if (v is HomeView.Singers) {
            if (singers.isNullOrEmpty()) singers = withContext(Dispatchers.IO) {
                runCatching { SingerApi.list() }.getOrDefault(emptyList()).take(PAGE)
            }
            return@LaunchedEffect
        }
        if (v is HomeView.Songlists) {
            // 之前这里漏了分支：页面永远拿不到数据，进去就是空白（只有标题和返回）
            if (songlists.isNullOrEmpty()) {
                songlists = null
                songlists = withContext(Dispatchers.IO) {
                    runCatching { RecommendApi.songlists(num = PAGE) }.getOrDefault(emptyList())
                }
            }
            return@LaunchedEffect
        }
        if (v is HomeView.Albums) {
            if (albums.isNullOrEmpty()) albums = withContext(Dispatchers.IO) {
                runCatching { AlbumApi.newAlbums(num = PAGE, page = 1) }.getOrDefault(emptyList())
            }
            return@LaunchedEffect
        }
        val key = keyOf(v)
        val cached = HomeCache.songPages[key]
        if (cached != null) {
            songs = cached
            return@LaunchedEffect
        }
        songs = null
        error = ""
        val first = fetchPage(v, 1)
        HomeCache.songPages[key] = first
        HomeCache.pageNos[key] = 1
        songs = first
    }

    val entries = buildList {
        if (on("feed")) add("为你推荐" to HomeView.Recommend)
        if (on("songlists")) add("推荐歌单" to HomeView.Songlists)
        if (on("newsongs")) add("新歌速递" to HomeView.NewSongs)
        if (on("radar")) add("每日推荐" to HomeView.Radar)
        if (on("albums")) add("新碟上架" to HomeView.Albums)
        if (on("singers")) add("热门歌手" to HomeView.Singers)
        if (on("hot")) add("热搜榜" to HomeView.Hot())
        if (on("charts")) add("排行榜" to HomeView.Charts)
        if (on("guess")) add("猜你喜欢" to HomeView.Guess)
    }
    val v = view
    if (v is HomeView.Feed) {
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                Row(
                    Modifier.fillMaxWidth().padding(start = AppTheme.dimens.pagePadding, end = AppTheme.dimens.spacingSm, top = AppTheme.dimens.spacingSm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "首页",
                        fontSize = AppTheme.typography.pageTitle.fontSize,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.weight(1f),
                    )
                    AppIconButton(onClick = onOpenSearch, modifier = Modifier.size(AppTheme.dimens.coverSm)) {
                        Icon(
                            Icons.Rounded.Search,
                            contentDescription = "搜索",
                            tint = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.size(AppTheme.dimens.iconLg),
                        )
                    }
                }
            }
            item {
                Column(Modifier.fillMaxWidth()) {
                    BlockTitle("发现")
                    val icons = entryIcons()
                    Column(Modifier.fillMaxWidth().padding(horizontal = AppTheme.dimens.pagePadding)) {
                        entries.chunked(4).forEach { row ->
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = AppTheme.dimens.spacingXs),
                                horizontalArrangement = Arrangement.spacedBy(AppTheme.dimens.spacingSm),
                            ) {
                                row.forEach { e ->
                                    EntryTile(
                                        icon = icons[e.first] ?: Icons.Rounded.MusicNote,
                                        label = e.first,
                                        onClick = { open(e.second) },
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                }
            }

            item { Box(Modifier.padding(AppTheme.dimens.spacingXl)) {} }
        }
    } else if (v is HomeView.Recommend) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = AppTheme.dimens.spacingSm, vertical = AppTheme.dimens.spacing6),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppTextButton(onClick = { back() }) { Text("返回") }
                Text("为你推荐", fontSize = AppTheme.typography.sectionTitle.fontSize, fontWeight = FontWeight.SemiBold)
            }
            val fd = feed
            if (fd == null) {
                LoadingBox(Modifier.fillMaxSize())
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    for (g in fd) {
                        item { SubTitle(g.title) }
                        items(g.cards) { c ->
                            Row(
                                Modifier.fillMaxWidth().clip(AppTheme.shapes.cardSmall)
                                    .clickable { open(HomeView.Card(c)) }
                                    .padding(horizontal = AppTheme.dimens.pagePadding, vertical = AppTheme.dimens.spacing10),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Cover(c.cover, AppTheme.dimens.buttonHeight)
                                Column(Modifier.weight(1f).padding(start = AppTheme.dimens.spacing10)) {
                                    Text(c.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = AppTheme.typography.title.fontSize)
                                    Text(
                                        c.sub, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        fontSize = AppTheme.typography.caption.fontSize, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    } else if (v is HomeView.Albums) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = AppTheme.dimens.spacingSm, vertical = AppTheme.dimens.spacing6),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppTextButton(onClick = { back(); albums = null }) { Text("返回") }
                Text("新碟上架", fontSize = AppTheme.typography.sectionTitle.fontSize, fontWeight = FontWeight.SemiBold)
            }
            val al = albums
            if (al == null) {
                LoadingBox(Modifier.fillMaxSize())
            } else if (al.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("暂无新碟", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                val alState = androidx.compose.foundation.lazy.rememberLazyListState()
                androidx.compose.runtime.LaunchedEffect(alState, al.size) {
                    androidx.compose.runtime.snapshotFlow {
                        alState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                    }.collect { last -> if (last >= al.size - 3) loadMoreAlbums() }
                }
                LazyColumn(state = alState, modifier = Modifier.fillMaxSize()) {
                    items(al) { a ->
                        Row(
                            Modifier.fillMaxWidth().clip(AppTheme.shapes.cardSmall)
                                .clickable { open(HomeView.AlbumSongs(a)) }
                                .padding(horizontal = AppTheme.dimens.pagePadding, vertical = AppTheme.dimens.spacing10),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Cover(a.cover, AppTheme.dimens.coverLg)
                            Column(Modifier.weight(1f).padding(start = AppTheme.dimens.spacing10)) {
                                Text(a.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = AppTheme.typography.title.fontSize)
                                Text(
                                    a.singer, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    fontSize = AppTheme.typography.caption.fontSize, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    } else if (v is HomeView.Songlists) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = AppTheme.dimens.spacingSm, vertical = AppTheme.dimens.spacing6),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppTextButton(onClick = { back() }) { Text("返回") }
                Text("推荐歌单", fontSize = AppTheme.typography.sectionTitle.fontSize, fontWeight = FontWeight.SemiBold)
            }
            val sl = songlists
            if (sl == null) {
                SongListSkeleton(Modifier.fillMaxSize())
            } else if (sl.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("暂无推荐歌单", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                val slState = androidx.compose.foundation.lazy.rememberLazyListState()
                androidx.compose.runtime.LaunchedEffect(slState, sl.size) {
                    androidx.compose.runtime.snapshotFlow {
                        slState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                    }.collect { last -> if (last >= sl.size - 3) loadMoreSonglists() }
                }
                LazyColumn(state = slState, modifier = Modifier.fillMaxSize()) {
                    items(sl) { s ->
                        Row(
                            Modifier.fillMaxWidth().clip(AppTheme.shapes.cardSmall)
                                .clickable { open(HomeView.Songlist(s)) }
                                .padding(horizontal = AppTheme.dimens.pagePadding, vertical = AppTheme.dimens.spacing10),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Cover(s.pic, AppTheme.dimens.coverLg)
                            Column(Modifier.weight(1f).padding(start = AppTheme.dimens.spacing10)) {
                                Text(s.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = AppTheme.typography.title.fontSize)
                                Text(
                                    s.songNum.toString() + " 首 · " + fmtPlay(s.listenNum) +
                                        (if (s.creator.isEmpty()) "" else " · " + s.creator),
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    fontSize = AppTheme.typography.caption.fontSize, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    } else if (v is HomeView.Charts) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = AppTheme.dimens.spacingSm, vertical = AppTheme.dimens.spacing6),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppTextButton(onClick = { back() }) { Text("返回") }
                Text("排行榜", fontSize = AppTheme.typography.sectionTitle.fontSize, fontWeight = FontWeight.SemiBold)
            }
            val cs = charts
            if (cs == null) {
                LoadingBox(Modifier.fillMaxSize())
            } else if (cs.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("暂无榜单", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(cs) { c ->
                        Row(
                            Modifier.fillMaxWidth().clip(AppTheme.shapes.cardSmall)
                                .clickable { open(HomeView.Chart(c)) }
                                .padding(horizontal = AppTheme.dimens.pagePadding, vertical = AppTheme.dimens.spacing10),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            SquareCover(c.cover)
                            Column(Modifier.weight(1f).padding(start = AppTheme.dimens.spacing10)) {
                                Text(c.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = AppTheme.typography.title.fontSize)
                                if (c.count > 0) {
                                    Text(
                                        c.count.toString() + " 首", fontSize = AppTheme.typography.caption.fontSize,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    } else if (v is HomeView.Singers) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = AppTheme.dimens.spacingSm, vertical = AppTheme.dimens.spacing6),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppTextButton(onClick = { back(); singers = null }) { Text("返回") }
                Text("热门歌手", fontSize = AppTheme.typography.sectionTitle.fontSize, fontWeight = FontWeight.SemiBold)
            }
            val sl = singers
            if (sl == null) {
                LoadingBox(Modifier.fillMaxSize())
            } else if (sl.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("暂无歌手", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(sl) { s ->
                        Row(
                            Modifier.fillMaxWidth().clip(AppTheme.shapes.cardSmall)
                                .clickable { open(HomeView.SingerSongs(s)) }
                                .padding(horizontal = AppTheme.dimens.pagePadding, vertical = AppTheme.dimens.spacingMd),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(s.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = AppTheme.typography.title.fontSize)
                        }
                    }
                }
            }
        }
    } else if (v is HomeView.Hot && v.kw.isEmpty()) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = AppTheme.dimens.spacingSm, vertical = AppTheme.dimens.spacing6),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppTextButton(onClick = { back(); hot = null }) { Text("返回") }
                Text("热搜榜", fontSize = AppTheme.typography.sectionTitle.fontSize, fontWeight = FontWeight.SemiBold)
            }
            val hk = hot
            if (hk == null) {
                LoadingBox(Modifier.fillMaxSize())
            } else if (hk.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("暂无热搜", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    itemsIndexed(hk) { i, kw ->
                        Row(
                            Modifier.fillMaxWidth().clip(AppTheme.shapes.cardSmall)
                                .clickable { onSearch(kw) }
                                .padding(horizontal = AppTheme.dimens.pagePadding, vertical = AppTheme.dimens.spacingMd),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text((i + 1).toString(), fontSize = AppTheme.typography.bodySmall.fontSize, color = MaterialTheme.colorScheme.primary)
                            Text(
                                kw, modifier = Modifier.padding(start = AppTheme.dimens.spacingMd), fontSize = AppTheme.typography.title.fontSize,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    } else {
        val title = when (v) {
            is HomeView.Guess -> "猜你喜欢"
            is HomeView.Radar -> "每日推荐"
            is HomeView.Chart -> v.chart.name
            is HomeView.Songlist -> v.item.name
            is HomeView.Card -> v.card.name
            is HomeView.AlbumSongs -> v.album.name
            is HomeView.Hot -> if (v.kw.isEmpty()) "热搜榜" else v.kw
            is HomeView.SingerSongs -> v.singer.name
            is HomeView.NewSongs -> "新歌速递"
            else -> ""
        }
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = AppTheme.dimens.spacingSm, vertical = AppTheme.dimens.spacing6),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppTextButton(onClick = { back(); songs = null }) { Text("返回") }
                Text(
                    title, fontSize = AppTheme.typography.sectionTitle.fontSize, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            val list = songs
            if (list == null) {
                // 骨架屏：请求期间就有可见内容，不会像白屏
                SongListSkeleton(Modifier.fillMaxSize())
            } else if (list.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(error.ifEmpty { "暂无歌曲" }, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                val listId = when (v) {
                    is HomeView.Card -> v.card.id.toLongOrNull()
                    is HomeView.Songlist -> v.item.id.toLongOrNull()
                    else -> null
                }
                SongListView(
                    songs = list,
                    modifier = Modifier.weight(1f),
                    fixId = title,
                    playlistId = listId,
                    onPlay = onPlay,
                    onDownload = onDownload,
                    onLoadMore = { loadMore(v) },
                    loadingMore = moreLoading,
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Row(Modifier.fillMaxWidth().padding(start = AppTheme.dimens.pagePadding, end = AppTheme.dimens.pagePadding, top = AppTheme.dimens.spacing18, bottom = AppTheme.dimens.spacing6)) {
        Text(text, fontSize = AppTheme.typography.sectionTitle.fontSize, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SubTitle(text: String) {
    Row(Modifier.fillMaxWidth().padding(start = AppTheme.dimens.pagePadding, end = AppTheme.dimens.pagePadding, top = AppTheme.dimens.spacingMd, bottom = AppTheme.dimens.spacingXs)) {
        Text(text, fontSize = AppTheme.typography.body.fontSize, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun LoadingRow() {
    Box(Modifier.fillMaxWidth().padding(AppTheme.dimens.pagePadding), contentAlignment = Alignment.Center) {
        AppSpinner(Modifier.padding(AppTheme.dimens.spacingXs))
    }
}

@Composable
private fun HintRow(text: String) {
    Box(Modifier.fillMaxWidth().padding(AppTheme.dimens.pagePadding), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = AppTheme.typography.bodySmall.fontSize)
    }
}

@Composable
private fun SongRow(song: MusicApi.Song, onClick: () -> Unit, onDl: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(AppTheme.shapes.cardSmall)
            .clickable(onClick = onClick)
            .padding(horizontal = AppTheme.dimens.pagePadding, vertical = AppTheme.dimens.spacing10),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cover(albumCover(song.albumMid), AppTheme.dimens.buttonHeight)
        Column(Modifier.weight(1f).padding(start = AppTheme.dimens.spacing10)) {
            Text(song.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = AppTheme.typography.title.fontSize)
            Text(
                song.singers, maxLines = 1, overflow = TextOverflow.Ellipsis,
                fontSize = AppTheme.typography.caption.fontSize, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        AppTextButton(onClick = onDl) { Text("下载", fontSize = AppTheme.typography.bodySmall.fontSize) }
    }
}

@Composable
private fun Cover(url: String, dim: Dp) {
    if (url.isEmpty()) return
    AsyncImage(
        model = url,
        contentDescription = null,
        modifier = Modifier.size(dim).clip(AppTheme.shapes.cover),
        contentScale = ContentScale.Crop,
    )
}

private fun albumCover(mid: String): String =
    if (mid.isEmpty()) "" else "https://y.gtimg.cn/music/photo_new/T002R300x300M000" + mid + ".jpg"

private fun fmtPlay(n: Int): String = when {
    n >= 100000000 -> String.format("%.1f 亿", n / 100000000.0)
    n >= 10000 -> String.format("%.1f 万", n / 10000.0)
    else -> n.toString()
}
