package com.tianxiaodudou.qqmusicdownloader.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.tianxiaodudou.qqmusicdownloader.BuildConfig
import com.tianxiaodudou.qqmusicdownloader.net.DirStore
import com.tianxiaodudou.qqmusicdownloader.net.Downloader
import com.tianxiaodudou.qqmusicdownloader.net.History
import com.tianxiaodudou.qqmusicdownloader.net.MusicApi
import com.tianxiaodudou.qqmusicdownloader.net.Session
import com.tianxiaodudou.qqmusicdownloader.net.Settings
import com.tianxiaodudou.qqmusicdownloader.player.PlayerController
import com.tianxiaodudou.qqmusicdownloader.ui.player.PlayerScreen
import com.tianxiaodudou.qqmusicdownloader.ui.player.coverUrl
import com.tianxiaodudou.qqmusicdownloader.ui.player.fmtMs
import kotlinx.coroutines.launch

enum class Tab(val label: String) {
    Home("首页"),
    Search("搜索"),
    Library("歌单"),
    Mine("我的"),
}

@Composable
fun AppRoot() {
    val context = LocalContext.current
    var session by remember { mutableStateOf(Session.load(context)) }
    PlayerController.attach(context)
    val current = session

    if (current == null) {
        LoginScreen(onLoggedIn = { s ->
            // 必须落盘：之前只更新内存，进程一重启就要重新扫码。
            Session.save(context, s)
            session = s
        })
    } else {
        MainScaffold(current, onLogout = {
            Session.clear(context)
            session = null
        })
    }
}

@Composable
private fun MainScaffold(session: Session, onLogout: () -> Unit) {
    val context = LocalContext.current
    var tab by rememberSaveable { mutableStateOf(Tab.Home) }
    var showPlayer by remember { mutableStateOf(false) }
    var showDownloads by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    var dirLabel by remember { mutableStateOf(DirStore.get(context)?.lastPathSegment?.take(40) ?: "") }
    val pickDir = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            DirStore.save(context, uri)
            dirLabel = uri.lastPathSegment?.take(40) ?: "已选择"
        }
    }

    fun playAt(list: List<MusicApi.Song>, i: Int) {
        PlayerController.play(list, i)
        showPlayer = true
    }

    BackHandler(enabled = showDownloads && !showPlayer) { showDownloads = false }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                Column {
                    if (PlayerController.current != null) {
                        MiniPlayer(onOpen = { showPlayer = true })
                    }
                    NavigationBar(containerColor = Color(0xFF11141A), tonalElevation = 0.dp) {
                        Tab.entries.forEach { item ->
                            NavigationBarItem(
                                selected = tab == item,
                                onClick = { tab = item },
                                icon = {
                                    Icon(
                                        when (item) {
                                            Tab.Home -> Icons.Rounded.Home
                                            Tab.Search -> Icons.Rounded.Search
                                            Tab.Library -> Icons.Rounded.LibraryMusic
                                            Tab.Mine -> Icons.Rounded.Person
                                        },
                                        contentDescription = item.label,
                                        modifier = Modifier.size(22.dp),
                                    )
                                },
                                label = { Text(item.label, fontSize = 10.sp) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = MaterialTheme.colorScheme.primary,
                                    selectedTextColor = MaterialTheme.colorScheme.primary,
                                    indicatorColor = Color(0xFF1B2A22),
                                ),
                            )
                        }
                    }
                }
            },
        ) { inner ->
            Box(Modifier.fillMaxSize().padding(inner)) {
                when (tab) {
                    Tab.Home -> HomeScreen(onPlay = { list, i -> playAt(list, i) }, onDownload = { Downloader.enqueue(context, it) })
                    Tab.Search -> SearchScreen(onPlay = { list, i -> playAt(list, i) }, onDownload = { Downloader.enqueue(context, it) })
                    Tab.Library -> LibraryScreen(onPlay = { list, i -> playAt(list, i) }, onDownload = { Downloader.enqueue(context, it) })
                    Tab.Mine -> MinePage(
                        session = session,
                        dirLabel = dirLabel,
                        onPickDir = { pickDir.launch(null) },
                        onDownloads = { showDownloads = true },
                        onLogout = onLogout,
                    )
                }
            }
        }

        if (showDownloads && !showPlayer) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                Column(Modifier.fillMaxSize().statusBarsPadding()) {
                    Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { showDownloads = false }, modifier = Modifier.size(44.dp)) {
                            Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "返回")
                        }
                        Text("下载管理", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    }
                    DownloadsScreen()
                }
            }
        }

        AnimatedVisibility(
            visible = showPlayer,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(tween(160)),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(tween(140)),
        ) {
            PlayerScreen(onExit = { showPlayer = false })
        }
    }
}

@Composable
private fun MiniPlayer(onOpen: () -> Unit) {
    val song = PlayerController.current ?: return
    val dur = PlayerController.durationMs
    val pos = PlayerController.positionMs
    Column(Modifier.fillMaxWidth().background(Color(0xFF171B22))) {
        Box(Modifier.fillMaxWidth().height(1.5.dp).background(Color(0xFF262C36))) {
            if (dur > 0) {
                Box(Modifier.fillMaxWidth((pos.toFloat() / dur).coerceIn(0f, 1f)).height(1.5.dp).background(MaterialTheme.colorScheme.primary))
            }
        }
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val cover = coverUrl(song.albumMid, 200)
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF222833)),
                contentAlignment = Alignment.Center,
            ) {
                if (cover.isNotEmpty()) {
                    AsyncImage(model = cover, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else {
                    Text("♪", color = Color(0xFF8C96A6), fontSize = 16.sp)
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(song.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.5.sp, color = Color(0xFFE9EDF4))
                Text(
                    if (PlayerController.loading) "正在准备音源…" else song.singers,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 10.5.sp,
                    color = Color(0xFF8C96A6),
                )
            }
            IconButton(onClick = { PlayerController.toggle() }, modifier = Modifier.size(44.dp)) {
                Icon(
                    if (PlayerController.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = "播放/暂停",
                    tint = Color(0xFFE9EDF4),
                )
            }
            IconButton(onClick = { PlayerController.next() }, modifier = Modifier.size(44.dp)) {
                Icon(Icons.Rounded.SkipNext, contentDescription = "下一首", tint = Color(0xFFE9EDF4))
            }
        }
    }
}

@Composable
private fun MinePage(
    session: Session,
    dirLabel: String,
    onPickDir: () -> Unit,
    onDownloads: () -> Unit,
    onLogout: () -> Unit,
) {
    val context = LocalContext.current
    var toast by remember { mutableStateOf("") }
    var offsetCount by remember { mutableStateOf(Settings.offsetCount(context)) }

    Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
        Text(
            text = session.nickname.ifEmpty { "已登录" },
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text("QQ 音乐 uin " + session.uin, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

        Spacer(Modifier.height(22.dp))
        MineRow("下载管理", "下载的歌曲与历史记录", onDownloads)
        MineRow("下载目录", dirLabel.ifEmpty { "未选择" }, onPickDir)
        MineRow("清除所有歌词偏移", offsetCount.toString() + " 首已调整") {
            Settings.clearOffsets(context)
            offsetCount = 0
            toast = "已清除"
        }
        MineRow("清理缓存", "无损解密缓存与临时文件") {
            val freed = Settings.clearCache(context)
            toast = "已清理 " + (freed / 1024 / 1024) + " MB"
        }

        if (toast.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text(toast, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Spacer(Modifier.weight(1f))
        Text("QQ音乐播放器 " + BuildConfig.VERSION_NAME, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(10.dp))
        OutlinedButton(onClick = onLogout) { Text("退出登录") }
    }
}

@Composable
private fun MineRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
            Text(subtitle, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    HorizontalDivider(color = Color(0xFF222833), thickness = 0.6.dp)
}
