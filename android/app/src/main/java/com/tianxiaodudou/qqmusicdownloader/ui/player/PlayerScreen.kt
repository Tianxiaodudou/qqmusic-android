package com.tianxiaodudou.qqmusicdownloader.ui.player

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.runtime.mutableFloatStateOf
import coil3.compose.AsyncImage
import com.tianxiaodudou.qqmusicdownloader.BuildConfig
import com.tianxiaodudou.qqmusicdownloader.net.LyricApi
import com.tianxiaodudou.qqmusicdownloader.net.MusicApi
import com.tianxiaodudou.qqmusicdownloader.net.PlaylistApi
import com.tianxiaodudou.qqmusicdownloader.net.Settings
import com.tianxiaodudou.qqmusicdownloader.ui.Covers
import com.tianxiaodudou.qqmusicdownloader.ui.Nav
import com.tianxiaodudou.qqmusicdownloader.ui.Route
import com.tianxiaodudou.qqmusicdownloader.player.Favorites
import com.tianxiaodudou.qqmusicdownloader.player.PlayerController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Ink = Color(0xFF0B0D10)
private val Brand = Color(0xFF1FC365)
private val TextHi = Color(0xFFE9EDF4)
private val TextLo = Color(0xFF8C96A6)

/** 歌词缓存：同一首歌再次打开秒开。 */
internal object LyricsCache {
    private val map = HashMap<String, LyricApi.Result>()
    fun get(mid: String): LyricApi.Result? = map[mid]
    fun put(mid: String, r: LyricApi.Result) {
        if (map.size > 12) map.clear()
        map[mid] = r
    }
    fun drop(mid: String) {
        map.remove(mid)
    }
}

internal sealed interface PSheet {
    data object Quality : PSheet
    data object Sync : PSheet
    data object Queue : PSheet
    data object More : PSheet
    data object Eq : PSheet
    data object Speed : PSheet
    data object Sleep : PSheet
    data object Style : PSheet
    data object Info : PSheet
    data object AddTo : PSheet
    data object Comments : PSheet
    data object Similar : PSheet
    data object Related : PSheet
    data object Versions : PSheet
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(onExit: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val song = PlayerController.current

    var lines by remember(song?.mid) { mutableStateOf<List<LyricApi.Line>>(emptyList()) }
    var lyricSource by remember(song?.mid) { mutableStateOf("") }
    var loadingLyric by remember(song?.mid) { mutableStateOf(false) }
    var reloadToken by remember(song?.mid) { mutableIntStateOf(0) }

    var offsetMs by remember(song?.mid) {
        mutableIntStateOf(Settings.lyricOffset(context, song?.mid ?: ""))
    }
    var style by remember { mutableStateOf(Settings.lyricStyle(context)) }
    var quality by remember { mutableStateOf(Settings.quality(context)) }
    var previewMs by remember { mutableStateOf<Long?>(null) }
    var flashIndex by remember { mutableIntStateOf(-1) }
    var seekPulse by remember { mutableIntStateOf(0) }
    var sheet by remember { mutableStateOf<PSheet?>(null) }
    var shareLine by remember { mutableStateOf<LyricApi.Line?>(null) }
    var dictLine by remember { mutableStateOf<LyricApi.Line?>(null) }
    var dictEntries by remember { mutableStateOf<List<com.tianxiaodudou.qqmusicdownloader.net.Extras.DictEntry>>(emptyList()) }
    var dictLoading by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf("") }

    // 返回链：词典 -> 分享 -> 关于 -> 任意面板 -> 收起播放页
    BackHandler(enabled = dictLine != null || shareLine != null || showAbout || sheet != null) {
        when {
            dictLine != null -> dictLine = null
            shareLine != null -> shareLine = null
            showAbout -> showAbout = false
            else -> sheet = null
        }
    }

    LaunchedEffect(song?.mid, reloadToken) {
        val mid = song?.mid ?: return@LaunchedEffect
        val cached = LyricsCache.get(mid)
        if (cached != null) {
            lines = cached.lines
            lyricSource = cached.source
            return@LaunchedEffect
        }
        loadingLyric = true
        val res = withContext(Dispatchers.IO) { runCatching { LyricApi.load(mid) }.getOrNull() }
        if (res != null) {
            LyricsCache.put(mid, res)
            lines = res.lines
            lyricSource = res.source
        }
        loadingLyric = false
    }

    // 预取前后各一首的歌词与封面，切歌时直接可用
    LaunchedEffect(PlayerController.index, PlayerController.queue) {
        val q = PlayerController.queue
        val i = PlayerController.index
        val neighbors = listOfNotNull(q.getOrNull(i - 1), q.getOrNull(i + 1))
        val current = q.getOrNull(i)
        if (current != null) {
            Covers.prefetch(context, listOf(coverUrl(current.albumMid, 500)))
        }
        if (neighbors.isEmpty()) return@LaunchedEffect
        Covers.prefetch(context, neighbors.map { coverUrl(it.albumMid, 500) })
        withContext(Dispatchers.IO) {
            for (n in neighbors) {
                if (LyricsCache.get(n.mid) == null) {
                    runCatching { LyricApi.load(n.mid) }.getOrNull()?.let { LyricsCache.put(n.mid, it) }
                }
            }
        }
    }

    LaunchedEffect(song?.mid) {
        Favorites.ensureLoaded()
        offsetMs = Settings.lyricOffset(context, song?.mid ?: "")
    }

    LaunchedEffect(dictLine) {
        val line = dictLine ?: return@LaunchedEffect
        dictLoading = true
        dictEntries = emptyList()
        val id = PlayerController.current?.id ?: 0
        val all = withContext(Dispatchers.IO) { runCatching { com.tianxiaodudou.qqmusicdownloader.net.Extras.aiDict(id) }.getOrDefault(emptyList()) }
        dictEntries = all.filter { it.lyricText == line.text || line.text.contains(it.phrase) }
        dictLoading = false
    }

    LaunchedEffect(toast) {
        if (toast.isNotEmpty()) {
            Toast.makeText(context, toast, Toast.LENGTH_SHORT).show()
            toast = ""
        }
    }

    val position = PlayerController.positionMs
    val duration = if (PlayerController.durationMs > 0) PlayerController.durationMs else (song?.interval ?: 0) * 1000L
    val effective = previewMs ?: position

    if (song == null) {
        Box(Modifier.fillMaxSize().background(Ink), contentAlignment = Alignment.Center) {
            Text("还没有正在播放的歌曲", color = TextLo, fontSize = 14.sp)
        }
        return
    }

    val cover = coverUrl(song.albumMid, 500)
    val fav = Favorites.isFav(song.mid)

    fun jumpToLine(line: LyricApi.Line) {
        val target = (line.timeMs - offsetMs).toLong().coerceAtLeast(0L)
        PlayerController.seekTo(target)
        previewMs = null
        flashIndex = lines.indexOf(line)
        seekPulse += 1
        scope.launch {
            delay(720)
            if (flashIndex == lines.indexOf(line)) flashIndex = -1
        }
    }

    Box(Modifier.fillMaxSize().background(Ink)) {
        // ---- 背景：封面高斯模糊 + 缓慢呼吸 ----
        if (cover.isNotEmpty()) {
            val breathTransition = rememberInfiniteTransition(label = "bgBreath")
            val breath by breathTransition.animateFloat(
                initialValue = 0.26f,
                targetValue = 0.36f,
                animationSpec = infiniteRepeatable(
                    animation = tween(9000, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "bgAlpha",
            )
            AsyncImage(
                model = cover,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().blur(64.dp).alpha(breath),
            )
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Ink.copy(alpha = 0.72f),
                    0.45f to Ink.copy(alpha = 0.86f),
                    1f to Ink.copy(alpha = 0.98f),
                )
            )
        )

        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            PlayerTopBar(
                song = song,
                qualityText = PlayerController.actualQuality,
                sleepRemainingMs = if (PlayerController.sleepEndAt > 0) PlayerController.sleepEndAt - System.currentTimeMillis() else 0,
                sleepAfterCurrent = PlayerController.sleepAfterCurrent,
                onBack = onExit,
                onMore = { sheet = PSheet.More },
            )

            Box(Modifier.weight(1f)) {
                LyricsPane(
                    lines = lines,
                    positionProvider = { previewMs ?: PlayerController.positionNow() },
                    offsetMs = offsetMs,
                    style = style,
                    playing = PlayerController.playing,
                    flashIndex = flashIndex,
                    onSeekLine = ::jumpToLine,
                    onLongPressLine = { shareLine = it },
                    onLoadLyric = {
                        LyricsCache.drop(song.mid)
                        reloadToken += 1
                    },
                )
                if (loadingLyric) {
                    Text(
                        "歌词加载中…",
                        fontSize = 11.sp,
                        color = TextLo,
                        modifier = Modifier.align(Alignment.TopEnd).padding(top = 10.dp, end = 16.dp),
                    )
                }
                if (sheet == PSheet.Sync) {
                    Box(Modifier.fillMaxSize().background(Color(0x47000000)))
                }
            }

            PlayerToolbar(
                fav = fav,
                qualityLabel = quality.label,
                offsetMs = offsetMs,
                onFav = {
                    scope.launch {
                        val ok = Favorites.toggle(song)
                        toast = if (ok) (if (fav) "已取消收藏" else "已收藏到「我喜欢」") else "操作失败，请稍后再试"
                    }
                },
                onAddTo = { sheet = PSheet.AddTo },
                onQuality = { sheet = PSheet.Quality },
                onSync = { if (lines.isEmpty()) toast = "暂无歌词，无法同步" else sheet = PSheet.Sync },
                lyricEnabled = lines.isNotEmpty(),
            )

            SeekRow(
                positionMs = effective,
                bufferedMs = PlayerController.bufferedMs,
                durationMs = duration,
                pulse = seekPulse,
                onPreview = { previewMs = it },
                onSeek = {
                    PlayerController.seekTo(it)
                    previewMs = null
                },
            )

            PlayerControls(
                playing = PlayerController.playing,
                loading = PlayerController.loading,
                mode = PlayerController.mode,
                onMode = { PlayerController.cycleMode() },
                onPrev = { PlayerController.prev() },
                onToggle = { PlayerController.toggle() },
                onNext = { PlayerController.next() },
                onQueue = { sheet = PSheet.Queue },
            )
            Spacer(Modifier.height(6.dp))
        }

        val s = sheet
        if (s != null) {
            val queueSheet = s == PSheet.Queue
            ModalBottomSheet(
                onDismissRequest = { sheet = null },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = !queueSheet),
                containerColor = Color(0xFF161A21),
                scrimColor = if (s == PSheet.Sync) Color.Transparent else Color(0xA6000000),
                dragHandle = {
                    Box(Modifier.fillMaxWidth().height(34.dp)) {
                        BottomSheetDefaults.DragHandle(
                            color = Color(0xFF3D4553),
                            modifier = Modifier.align(Alignment.Center),
                        )
                        IconButton(
                            onClick = { sheet = null },
                            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 6.dp).size(40.dp),
                        ) {
                            Icon(
                                Icons.Rounded.Close,
                                contentDescription = "关闭",
                                tint = Color(0xFF8C96A6),
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                },
            ) {
                when (s) {
                    PSheet.Quality -> QualitySheet(
                        current = quality,
                        onPick = { q ->
                            quality = q
                            PlayerController.setQuality(q)
                            sheet = null
                            toast = "已切换到「" + q.label + "」"
                        },
                    )

                    PSheet.Sync -> SyncSheet(
                        offsetMs = offsetMs,
                        onChange = {
                            offsetMs = it
                            Settings.setLyricOffset(context, song.mid, it)
                        },
                        onReset = {
                            offsetMs = 0
                            Settings.setLyricOffset(context, song.mid, 0)
                        },
                    )

                    PSheet.Queue -> QueueSheet(
                        queue = PlayerController.queue,
                        index = PlayerController.index,
                        playing = PlayerController.playing,
                        onPick = { i ->
                            PlayerController.playAt(i)
                            sheet = null
                        },
                        onRemove = { i -> PlayerController.removeAt(i) },
                        onClear = {
                            PlayerController.clearQueue()
                            sheet = null
                        },
                        onShuffleAll = {
                            PlayerController.changeMode(PlayerController.Mode.SHUFFLE)
                            PlayerController.next()
                            sheet = null
                        },
                    )

                    PSheet.More -> MoreSheet(
                        qualityLabel = quality.label,
                        speed = PlayerController.speed,
                        sleepActive = PlayerController.sleepEndAt > 0 || PlayerController.sleepAfterCurrent,
                        offsetCount = Settings.offsetCount(context),
                        onShare = {
                            sheet = null
                            shareSong(context, song)
                        },
                        onInfo = { sheet = PSheet.Info },
                        onAlbum = {
                            sheet = null
                            if (song.albumMid.isNotEmpty()) Nav.push(Route.Album(song.albumMid, song.album))
                        },
                        onSinger = {
                            sheet = null
                            if (song.singerMid.isNotEmpty()) Nav.push(Route.Singer(song.singerMid, song.singers))
                        },
                        onComments = { sheet = PSheet.Comments },
                        onSimilar = { sheet = PSheet.Similar },
                        onRelated = { sheet = PSheet.Related },
                        onVersions = { sheet = PSheet.Versions },
                        onEq = { sheet = PSheet.Eq },
                        onSpeed = { sheet = PSheet.Speed },
                        onSleep = { sheet = PSheet.Sleep },
                        onStyle = { sheet = PSheet.Style },
                        onClearOffsets = {
                            Settings.clearOffsets(context)
                            offsetMs = 0
                            toast = "已清除所有歌曲的歌词偏移"
                        },
                        onFeedback = {
                            sheet = null
                            openUrl(context, "https://github.com/Tianxiaodudou/qqmusic-android/issues")
                        },
                        onAbout = {
                            sheet = null
                            showAbout = true
                        },
                    )

                    PSheet.Eq -> EqSheet(
                        current = Settings.eqPreset(context),
                        onPick = {
                            Settings.setEqPreset(context, it)
                            com.tianxiaodudou.qqmusicdownloader.player.AudioFx.apply(it)
                        },
                    )

                    PSheet.Speed -> SpeedSheet(
                        current = PlayerController.speed,
                        onPick = {
                            PlayerController.changeSpeed(it)
                        },
                    )

                    PSheet.Sleep -> SleepSheet(
                        sleepEndAt = PlayerController.sleepEndAt,
                        afterCurrent = PlayerController.sleepAfterCurrent,
                        onMinutes = {
                            PlayerController.setSleep(it)
                            sheet = null
                            toast = it.toString() + " 分钟后停止播放"
                        },
                        onAfterCurrent = {
                            PlayerController.sleepAfterCurrentTrack()
                            sheet = null
                            toast = "播完当前歌曲后停止"
                        },
                        onCancel = {
                            PlayerController.cancelSleep()
                            sheet = null
                        },
                    )

                    PSheet.Style -> StyleSheet(
                        style = style,
                        onChange = {
                            style = it
                            Settings.setLyricStyle(context, it)
                        },
                        onReset = {
                            Settings.resetLyricStyle(context)
                            style = Settings.lyricStyle(context)
                        },
                    )

                    PSheet.Info -> SongInfoSheet(
                        song = song,
                        qualityLabel = quality.label,
                        source = PlayerController.sourceLabel,
                        path = PlayerController.sourcePath,
                        lyricSource = lyricSource,
                        onClose = { sheet = null },
                    )

                    PSheet.Comments -> CommentsSheet(song = song, onClose = { sheet = null })

                    PSheet.Similar -> SimilarSongsSheet(
                        songId = song.id,
                        onPlay = { list, i -> PlayerController.play(list, i) },
                        onClose = { sheet = null },
                    )

                    PSheet.Related -> RelatedPlaylistsSheet(
                        songId = song.id,
                        onClose = { sheet = null },
                    )

                    PSheet.Versions -> OtherVersionsSheet(
                        song = song,
                        onPlay = { list, i -> PlayerController.play(list, i) },
                        onClose = { sheet = null },
                    )

                    PSheet.AddTo -> AddToSheet(
                        song = song,
                        onDone = { msg ->
                            sheet = null
                            toast = msg
                        },
                    )
                }
            }
        }
    }

    shareLine?.let { line ->
        AlertDialog(
            onDismissRequest = { shareLine = null },
            title = { Text("歌词") },
            text = {
                Column {
                    Text(line.text)
                    Spacer(Modifier.height(8.dp))
                    Row {
                        TextButton(onClick = {
                            clipboard.setText(AnnotatedString(line.text))
                            shareLine = null
                            toast = "已复制这句"
                        }) { Text("复制", fontSize = 12.sp) }
                        TextButton(onClick = {
                            val text = line.text + "\n—— " + song.name + " · " + song.singers
                            val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                            context.startActivity(Intent.createChooser(i, "分享歌词"))
                            shareLine = null
                        }) { Text("分享", fontSize = 12.sp) }
                        TextButton(onClick = {
                            val target = line
                            shareLine = null
                            dictLine = target
                        }) { Text("查词", fontSize = 12.sp) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { shareLine = null }) { Text("关闭") } },
        )
    }

    if (dictLine != null) {
        AlertDialog(
            onDismissRequest = { dictLine = null },
            title = { Text("歌词词典") },
            text = {
                Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
                    if (dictLoading) {
                        Text("查询中…", fontSize = 12.sp, color = TextLo)
                    } else if (dictEntries.isEmpty()) {
                        Text("这句没有生词解析", fontSize = 12.sp, color = TextLo)
                    } else {
                        dictEntries.forEach { e ->
                            Text(e.phrase, fontSize = 14.sp, color = Color(0xFF6FE7A5))
                            Text(e.explain, fontSize = 12.sp, color = TextHi)
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { dictLine = null }) { Text("好") } },
        )
    }

    if (showAbout) {
        AlertDialog(
            onDismissRequest = { showAbout = false },
            title = { Text("关于") },
            text = {
                Column {
                    Text("QQ音乐播放器 " + BuildConfig.VERSION_NAME, fontSize = 14.sp)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "全部请求在设备本地完成，不经过任何中转服务器。\n开源许可：MIT License\n接口参考：QQMusicApi",
                        fontSize = 12.sp,
                        color = TextLo,
                    )
                }
            },
            confirmButton = { TextButton(onClick = { showAbout = false }) { Text("好") } },
        )
    }
}

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
@Composable
private fun PlayerTopBar(
    song: MusicApi.Song,
    qualityText: String,
    sleepRemainingMs: Long,
    sleepAfterCurrent: Boolean,
    onBack: () -> Unit,
    onMore: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(46.dp)) {
            Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "收起", tint = TextHi)
        }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                song.name,
                fontSize = 15.sp,
                color = TextHi,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
                textAlign = TextAlign.Center,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    song.singers,
                    fontSize = 11.sp,
                    color = TextLo,
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                )
                if (qualityText.isNotEmpty()) {
                    Spacer(Modifier.width(5.dp))
                    Text(
                        qualityText,
                        fontSize = 9.sp,
                        color = Color(0xFF6FE7A5),
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0x221FC365))
                            .padding(horizontal = 5.dp, vertical = 1.dp),
                    )
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (sleepRemainingMs > 0 || sleepAfterCurrent) {
                Icon(Icons.Rounded.Bedtime, contentDescription = null, tint = Color(0xFF8FB8FF), modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(3.dp))
                Text(
                    if (sleepAfterCurrent) "当前曲后" else fmtShort(sleepRemainingMs),
                    fontSize = 10.sp,
                    color = Color(0xFF8FB8FF),
                )
                Spacer(Modifier.width(2.dp))
            }
            IconButton(onClick = onMore, modifier = Modifier.size(46.dp)) {
                Icon(Icons.Rounded.MoreVert, contentDescription = "更多", tint = TextHi)
            }
        }
    }
}

@Composable
private fun PlayerToolbar(
    fav: Boolean,
    qualityLabel: String,
    offsetMs: Int,
    lyricEnabled: Boolean,
    onFav: () -> Unit,
    onAddTo: () -> Unit,
    onQuality: () -> Unit,
    onSync: () -> Unit,
) {
    val favScale by animateFloatAsState(
        targetValue = if (fav) 1.16f else 1f,
        animationSpec = spring(dampingRatio = 0.4f, stiffness = Spring.StiffnessMediumLow),
        label = "favScale",
    )
    Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp)) {
        ToolItem(
            icon = { tint, size ->
                Icon(
                    if (fav) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                    null,
                    tint = tint,
                    modifier = Modifier.size(size).scale(favScale),
                )
            },
            label = if (fav) "已收藏" else "收藏",
            tint = if (fav) Color(0xFFFF6B81) else TextHi,
            onClick = onFav,
            modifier = Modifier.weight(1f),
        )
        ToolItem(
            icon = { tint, size -> Icon(Icons.Rounded.Add, null, tint = tint, modifier = Modifier.size(size)) },
            label = "加到歌单",
            tint = TextHi,
            onClick = onAddTo,
            modifier = Modifier.weight(1f),
        )
        ToolItem(
            icon = { tint, size -> Icon(Icons.Rounded.Equalizer, null, tint = tint, modifier = Modifier.size(size)) },
            label = qualityLabel,
            tint = TextHi,
            onClick = onQuality,
            modifier = Modifier.weight(1f),
        )
        ToolItem(
            icon = { tint, size ->
                Box {
                    Icon(Icons.Rounded.Sync, null, tint = if (lyricEnabled) tint else Color(0xFF4A515E), modifier = Modifier.size(size))
                    if (offsetMs != 0) {
                        Box(
                            Modifier.align(Alignment.TopEnd).size(6.dp).clip(CircleShape).background(Brand)
                        )
                    }
                }
            },
            label = offsetLabel(offsetMs),
            tint = if (lyricEnabled) TextHi else Color(0xFF5A6270),
            onClick = onSync,
            modifier = Modifier.weight(1f),
        )
    }
}

private fun offsetLabel(ms: Int): String {
    if (ms == 0) return "同步"
    val v = ms / 1000f
    val s = if (v > 0) "+" + String.format("%.1f", v) else String.format("%.1f", v)
    return s + "s"
}

@Composable
private fun ToolItem(
    icon: @Composable (tint: Color, size: androidx.compose.ui.unit.Dp) -> Unit,
    label: String,
    tint: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .height(56.dp)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        icon(tint, 21.dp)
        Spacer(Modifier.height(3.dp))
        Text(label, fontSize = 10.sp, color = tint.copy(alpha = 0.82f), maxLines = 1)
    }
}

@Composable
private fun SeekRow(
    positionMs: Long,
    bufferedMs: Long,
    durationMs: Long,
    pulse: Int,
    onPreview: (Long?) -> Unit,
    onSeek: (Long) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp)) {
        SeekBar(
            positionMs = positionMs,
            bufferedMs = bufferedMs,
            durationMs = durationMs,
            pulse = pulse,
            onPreview = onPreview,
            onSeek = onSeek,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp)) {
            Text(fmtMs(positionMs), fontSize = 10.5.sp, color = TextLo)
            Spacer(Modifier.weight(1f))
            Text(fmtMs(durationMs), fontSize = 10.5.sp, color = TextLo)
        }
    }
}

@Composable
private fun SeekBar(
    positionMs: Long,
    bufferedMs: Long,
    durationMs: Long,
    pulse: Int,
    onPreview: (Long?) -> Unit,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    var dragMs by remember { mutableStateOf<Long?>(null) }
    var widthPx by remember { mutableFloatStateOf(1f) }
    val pulseScale = remember { Animatable(1f) }
    LaunchedEffect(pulse) {
        if (pulse > 0) {
            pulseScale.snapTo(1.55f)
            pulseScale.animateTo(1f, spring(dampingRatio = 0.35f, stiffness = Spring.StiffnessMedium))
        }
    }
    LaunchedEffect(dragMs) { onPreview(dragMs) }

    val shown = dragMs ?: positionMs
    val frac = if (durationMs <= 0) 0f else (shown.toFloat() / durationMs).coerceIn(0f, 1f)
    val bufFrac = if (durationMs <= 0) 0f else (bufferedMs.toFloat() / durationMs).coerceIn(0f, 1f)
    val dragging = dragMs != null

    Box(
        modifier
            .height(34.dp)
            .onSizeChanged { widthPx = it.width.toFloat() }
            .pointerInput(durationMs) {
                detectHorizontalDragGestures(
                    onDragStart = { off ->
                        dragMs = ((off.x / widthPx) * durationMs).toLong().coerceIn(0L, durationMs)
                    },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        dragMs = ((change.position.x / widthPx) * durationMs).toLong().coerceIn(0L, durationMs)
                    },
                    onDragEnd = {
                        dragMs?.let { onSeek(it) }
                        dragMs = null
                    },
                    onDragCancel = { dragMs = null },
                )
            }
            .pointerInput(durationMs) {
                detectTapGestures { off ->
                    onSeek(((off.x / widthPx) * durationMs).toLong().coerceIn(0L, durationMs))
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val cy = size.height / 2f
            val h = 3.5.dp.toPx()
            drawRoundRect(
                color = Color(0xFF3A4150),
                topLeft = Offset(0f, cy - h / 2),
                size = Size(size.width, h),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(h / 2),
            )
            // 已缓冲（白）
            drawRoundRect(
                color = Color(0x8CE6ECF5),
                topLeft = Offset(0f, cy - h / 2),
                size = Size(size.width * bufFrac, h),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(h / 2),
            )
            // 已播放（绿）
            drawRoundRect(
                color = Brand,
                topLeft = Offset(0f, cy - h / 2),
                size = Size(size.width * frac, h),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(h / 2),
            )
            val r = (if (dragging) 9f else 5.5f).dp.toPx() * pulseScale.value
            drawCircle(color = Color(0xFF0B0D10), radius = r + 2.dp.toPx(), center = Offset(size.width * frac, cy))
            drawCircle(color = Brand, radius = r, center = Offset(size.width * frac, cy))
        }
    }
}

@Composable
private fun PlayerControls(
    playing: Boolean,
    loading: Boolean,
    mode: PlayerController.Mode,
    onMode: () -> Unit,
    onPrev: () -> Unit,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onQueue: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp).navigationBarsPadding(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onMode, modifier = Modifier.size(44.dp)) {
            val icon = when (mode) {
                PlayerController.Mode.LIST -> Icons.Rounded.Repeat
                PlayerController.Mode.SINGLE -> Icons.Rounded.RepeatOne
                PlayerController.Mode.SHUFFLE -> Icons.Rounded.Shuffle
            }
            Icon(icon, contentDescription = "播放模式", tint = TextHi, modifier = Modifier.size(21.dp))
        }
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onPrev, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Rounded.SkipPrevious, contentDescription = "上一首", tint = TextHi, modifier = Modifier.size(32.dp))
        }
        PlayPauseButton(playing, loading, onToggle)
        IconButton(onClick = onNext, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Rounded.SkipNext, contentDescription = "下一首", tint = TextHi, modifier = Modifier.size(32.dp))
        }
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onQueue, modifier = Modifier.size(44.dp)) {
            Icon(Icons.Rounded.QueueMusic, contentDescription = "播放列表", tint = TextHi, modifier = Modifier.size(22.dp))
        }
    }
}

@Composable
private fun PlayPauseButton(playing: Boolean, loading: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.9f else 1f, tween(120), label = "playScale")
    Box(
        Modifier
            .padding(horizontal = 10.dp)
            .size(62.dp)
            .scale(scale)
            .clip(CircleShape)
            .background(Brand)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            Text("…", fontSize = 22.sp, color = Color(0xFF06230F))
        } else {
            Icon(
                if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = if (playing) "暂停" else "播放",
                tint = Color(0xFF06230F),
                modifier = Modifier.size(34.dp),
            )
        }
    }
}

// QQ 的封面 CDN 只认特定尺寸（300/500/800），200/400 会 404
internal fun coverUrl(albumMid: String, size: Int = 500): String =
    if (albumMid.isEmpty()) "" else "https://y.gtimg.cn/music/photo_new/T002R" + size + "x" + size + "M000" + albumMid + ".jpg"

internal fun fmtMs(ms: Long): String {
    val total = (ms.coerceAtLeast(0)) / 1000
    return String.format("%02d:%02d", total / 60, total % 60)
}

private fun fmtShort(ms: Long): String {
    val total = (ms.coerceAtLeast(0) + 999) / 1000
    return String.format("%02d:%02d", total / 60, total % 60)
}

internal fun shareSong(context: android.content.Context, song: MusicApi.Song) {
    val text = song.name + " - " + song.singers + "\nhttps://y.qq.com/n/ryqq/songDetail/" + song.mid
    val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    context.startActivity(Intent.createChooser(i, "分享歌曲"))
}

internal fun openUrl(context: android.content.Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }.onFailure {
        Toast.makeText(context, "没有可以打开链接的应用", Toast.LENGTH_SHORT).show()
    }
}
