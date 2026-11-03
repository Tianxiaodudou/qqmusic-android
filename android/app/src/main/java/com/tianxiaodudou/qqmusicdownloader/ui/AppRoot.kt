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
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.tianxiaodudou.qqmusicdownloader.net.Extras
import com.tianxiaodudou.qqmusicdownloader.net.History
import com.tianxiaodudou.qqmusicdownloader.net.LoginApi
import com.tianxiaodudou.qqmusicdownloader.net.MusicApi
import com.tianxiaodudou.qqmusicdownloader.net.Session
import com.tianxiaodudou.qqmusicdownloader.net.Settings
import com.tianxiaodudou.qqmusicdownloader.net.Updater
import com.tianxiaodudou.qqmusicdownloader.net.UserApi
import com.tianxiaodudou.qqmusicdownloader.player.PlayerController
import com.tianxiaodudou.qqmusicdownloader.ui.player.PlayerScreen
import com.tianxiaodudou.qqmusicdownloader.ui.player.QueueSheet
import com.tianxiaodudou.qqmusicdownloader.ui.player.coverUrl
import com.tianxiaodudou.qqmusicdownloader.ui.player.fmtMs
import com.tianxiaodudou.qqmusicdownloader.ui.theme.AppTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
            // 退出登录必须把播放也停掉，否则服务继续放着、系统媒体键还能切歌
            com.tianxiaodudou.qqmusicdownloader.player.PlayerController.stopAndClear()
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
    // 从迷你播放器点「播放列表」时，打开播放器后直接弹队列面板
    var showQueueSheet by remember { mutableStateOf(false) }
    var showDownloads by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // 顺手把账号资料拉一次：既预热「我的」页，也能补上加密 uin（收藏歌单要用）
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            runCatching { LoginApi.ensureFresh(context) }
            runCatching { UserApi.fetch(context) }
        }
    }

    var dirLabel by remember { mutableStateOf(DirStore.get(context)?.lastPathSegment?.take(40) ?: "") }
    val pickDir = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            DirStore.save(context, uri)
            dirLabel = uri.lastPathSegment?.take(40) ?: "已选择"
        }
    }

    fun playAt(list: List<MusicApi.Song>, i: Int) {
        Nav.clear()
        PlayerController.play(list, i)
        showPlayer = true
    }

    // 统一返回链：下载管理 -> 播放页 -> 详情页栈 -> 回到首页标签；到了首页才交给系统退出。
    val canBack = showDownloads || showPlayer || Nav.stack.isNotEmpty() || tab != Tab.Home
    BackHandler(enabled = canBack) {
        when {
            showDownloads -> showDownloads = false
            showPlayer -> showPlayer = false
            Nav.stack.isNotEmpty() -> Nav.pop()
            else -> tab = Tab.Home
        }
    }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                Column {
                    // 全局播放器常驻，不再随是否有歌曲而隐藏
                    MiniPlayer(
                        onOpen = { showPlayer = true },
                        onQueue = { showQueueSheet = true },
                    )
                    NavigationBar(containerColor = AppTheme.colors.bgElevated, tonalElevation = AppTheme.dimens.none) {
                        Tab.entries.forEach { item ->
                            if (item == Tab.Search) {
                                // 搜索入口已挪到首页右上角，这个位置改成手动刷新首页
                                NavigationBarItem(
                                    selected = false,
                                    onClick = {
                                        if (tab != Tab.Home) tab = Tab.Home
                                        HomeConfig.refresh()
                                        Toast.makeText(context, "正在刷新首页…", Toast.LENGTH_SHORT).show()
                                    },
                                    icon = {
                                        Icon(
                                            Icons.Rounded.Refresh,
                                            contentDescription = "刷新首页",
                                            modifier = Modifier.size(AppTheme.dimens.iconLg),
                                        )
                                    },
                                    label = { Text("刷新", fontSize = AppTheme.typography.badge.fontSize) },
                                    colors = NavigationBarItemDefaults.colors(
                                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                        indicatorColor = AppTheme.colors.brandSoft,
                                    ),
                                )
                                return@forEach
                            }
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
                                        modifier = Modifier.size(AppTheme.dimens.iconLg),
                                    )
                                },
                                label = { Text(item.label, fontSize = AppTheme.typography.badge.fontSize) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = MaterialTheme.colorScheme.primary,
                                    selectedTextColor = MaterialTheme.colorScheme.primary,
                                    indicatorColor = AppTheme.colors.brandSoft,
                                ),
                            )
                        }
                    }
                }
            },
        ) { inner ->
            Box(Modifier.fillMaxSize().padding(inner)) {
                when (tab) {
                    Tab.Home -> HomeScreen(
                        onPlay = { list, i -> playAt(list, i) },
                        onDownload = { Downloader.enqueue(context, it) },
                        onSearch = { kw ->
                            SearchBus.submit(kw)
                            tab = Tab.Search
                        },
                        onOpenSearch = { tab = Tab.Search },
                    )
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

        if (showQueueSheet) {
            AppSheet(onDismiss = { showQueueSheet = false }) {
                QueueSheet(
                    queue = PlayerController.queue,
                    index = PlayerController.index,
                    playing = PlayerController.playing,
                    onPick = { i ->
                        PlayerController.playAt(i)
                        showQueueSheet = false
                    },
                    onRemove = { i -> PlayerController.removeAt(i) },
                    onClear = {
                        PlayerController.clearQueue()
                        showQueueSheet = false
                    },
                    onShuffleAll = {
                        PlayerController.changeMode(PlayerController.Mode.SHUFFLE)
                        if (PlayerController.queue.isNotEmpty()) PlayerController.playAt(0)
                    },
                )
            }
        }

        if (showDownloads && !showPlayer) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                Column(Modifier.fillMaxSize().statusBarsPadding()) {
                    Row(Modifier.fillMaxWidth().height(AppTheme.dimens.topBarHeight).padding(horizontal = AppTheme.dimens.spacingXs), verticalAlignment = Alignment.CenterVertically) {
                        AppIconButton(onClick = { showDownloads = false }, modifier = Modifier.size(AppTheme.dimens.touchTarget)) {
                            Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "返回")
                        }
                        Text("下载管理", fontSize = AppTheme.typography.sectionTitle.fontSize, fontWeight = FontWeight.SemiBold)
                    }
                    DownloadsScreen()
                }
            }
        }

        if (Nav.stack.isNotEmpty()) {
            RouteHost(
                onPlay = { list, i -> playAt(list, i) },
                onDownload = { Downloader.enqueue(context, it) },
            )
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
private fun MiniPlayer(onOpen: () -> Unit, onQueue: () -> Unit) {
    val song = PlayerController.current
    val dur = PlayerController.durationMs
    val pos = PlayerController.positionMs
    Column(Modifier.fillMaxWidth().background(AppTheme.colors.surface1)) {
        Box(Modifier.fillMaxWidth().height(AppTheme.dimens.trackThin).background(AppTheme.colors.progressTrack)) {
            if (dur > 0) {
                Box(Modifier.fillMaxWidth((pos.toFloat() / dur).coerceIn(0f, 1f)).height(AppTheme.dimens.trackThin).background(MaterialTheme.colorScheme.primary))
            }
        }
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = AppTheme.dimens.spacingMd, vertical = AppTheme.dimens.spacingSm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val cover = if (song == null) "" else coverUrl(song.albumMid, 300)
            Box(
                Modifier.size(AppTheme.dimens.coverSm).clip(AppTheme.shapes.cover).background(AppTheme.colors.coverPlaceholder),
                contentAlignment = Alignment.Center,
            ) {
                if (cover.isNotEmpty()) {
                    AsyncImage(model = cover, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else {
                    Text("♪", color = AppTheme.colors.textSecondary, style = AppTheme.typography.title)
                }
            }
            Spacer(Modifier.width(AppTheme.dimens.spacing10))
            Column(Modifier.weight(1f)) {
                Text(
                    song?.name ?: "未在播放",
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = AppTheme.typography.bodySmall, color = AppTheme.colors.textPrimary,
                )
                Text(
                    when {
                        song == null -> "点这里打开播放器"
                        PlayerController.loading -> "正在准备音源…"
                        else -> song.singers
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = AppTheme.typography.badge.fontSize,
                    color = AppTheme.colors.textSecondary,
                )
            }
            AppIconButton(onClick = { PlayerController.prev() }, modifier = Modifier.size(AppTheme.dimens.touchTarget)) {
                Icon(Icons.Rounded.SkipPrevious, contentDescription = "上一首", tint = AppTheme.colors.textPrimary)
            }
            AppIconButton(onClick = { PlayerController.toggle() }, modifier = Modifier.size(AppTheme.dimens.touchTarget)) {
                Icon(
                    if (PlayerController.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = "播放/暂停",
                    tint = AppTheme.colors.textPrimary,
                )
            }
            AppIconButton(onClick = { PlayerController.next() }, modifier = Modifier.size(AppTheme.dimens.touchTarget)) {
                Icon(Icons.Rounded.SkipNext, contentDescription = "下一首", tint = AppTheme.colors.textPrimary)
            }
            AppIconButton(onClick = onQueue, modifier = Modifier.size(AppTheme.dimens.touchTarget)) {
                Icon(Icons.Rounded.QueueMusic, contentDescription = "播放列表", tint = AppTheme.colors.textPrimary)
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
    var profile by remember { mutableStateOf<UserApi.Profile?>(null) }
    var paidPolicy by remember { mutableStateOf(Settings.paidPolicy(context)) }
    var showPaid by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    var update by remember { mutableStateOf<Updater.Release?>(null) }
    var downloading by remember { mutableStateOf(false) }
    var progress by remember { mutableIntStateOf(0) }
    var updateError by remember { mutableStateOf("") }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val main = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    var loginState by remember { mutableStateOf("检查中…") }
    // 「我的」页分组折叠：默认全收起，同一时间只允许展开一个
    var openSection by remember { mutableStateOf("") }
    var autoDowngrade by remember { mutableStateOf(Settings.autoDowngrade(context)) }
    var offload by remember { mutableStateOf(Settings.audioOffload(context)) }
    var desktopLyrics by remember { mutableStateOf(Settings.desktopLyrics(context)) }
    var btLyrics by remember { mutableStateOf(Settings.btLyrics(context)) }
    var themeMode by remember { mutableStateOf(Settings.themeMode(context)) }
    var showTheme by remember { mutableStateOf(false) }
    // 悬浮窗权限走系统授权页，回来后再真正拉起悬浮歌词
    val overlayPermission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val ok = android.provider.Settings.canDrawOverlays(context)
        desktopLyrics = ok
        Settings.setDesktopLyrics(context, ok)
        com.tianxiaodudou.qqmusicdownloader.player.DesktopLyrics.setEnabled(context, ok)
        if (!ok) toast = "没有悬浮窗权限，桌面歌词无法显示"
    }
    fun toggleSection(key: String) { openSection = if (openSection == key) "" else key }

    LaunchedEffect(Unit) {
        val ok = runCatching { LoginApi.ensureFresh(context) }.getOrDefault(false)
        loginState = if (ok) "正常" else "已过期，点此重新登录"
    }

    fun checkUpdate() {
        if (checking || downloading) return
        checking = true
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching { Updater.latest(BuildConfig.VERSION_NAME) }
            }
            checking = false
            r.onSuccess { rel ->
                if (rel == null) toast = "已是最新版本 v" + BuildConfig.VERSION_NAME else update = rel
            }.onFailure {
                toast = "检查更新失败：" + (it.message ?: "网络异常")
            }
        }
    }

    fun startDownload(rel: Updater.Release) {
        if (downloading) return
        downloading = true
        progress = 0
        scope.launch {
            // 任何异常都在这里收口：下载失败只提示，不让整个应用崩掉
            val f = runCatching {
                Updater.download(context, rel) { pr -> main.post { progress = pr } }
            }.getOrNull()
            downloading = false
            if (f == null) {
                toast = "下载失败，请检查网络后重试"
                update = null
            } else {
                val ok = runCatching { Updater.install(context, f) }.getOrDefault(false)
                update = null
                if (!ok) toast = "已下载完成，请在系统设置里允许安装后重试"
            }
        }
    }

    LaunchedEffect(Unit) {
        profile = withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { UserApi.fetch(context) }.getOrNull()
        }
    }
    val p = profile
    val nickname = p?.nickname?.ifEmpty { session.nickname } ?: session.nickname.ifEmpty { "已登录" }
    val avatar = p?.avatar?.ifEmpty { null } ?: UserApi.avatarOf(session.uin)
    val vipLabel = p?.vipLabel ?: "未开通"
    val isVip = p?.isVip == true

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(AppTheme.dimens.spacing20),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = AppTheme.dimens.pagePadding)
                .clip(AppTheme.shapes.dialog).background(CardBg).padding(AppTheme.dimens.pagePadding),
        ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = avatar,
                contentDescription = "头像",
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(AppTheme.dimens.coverLg).clip(CircleShape).background(AppTheme.colors.coverPlaceholder),
            )
            Spacer(Modifier.width(AppTheme.dimens.spacing14))
            Column(Modifier.weight(1f)) {
                Text(
                    text = nickname,
                    fontSize = AppTheme.typography.pageTitle.fontSize,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(Modifier.height(AppTheme.dimens.spacing3))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        vipLabel,
                        fontSize = AppTheme.typography.caption.fontSize,
                        color = if (isVip) AppTheme.colors.warning else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(if (isVip) AppTheme.colors.warningSoft else AppTheme.colors.chipBg)
                            .padding(horizontal = AppTheme.dimens.spacingSm, vertical = AppTheme.dimens.spacing3),
                    )
                    Spacer(Modifier.width(AppTheme.dimens.spacingSm))
                    Text(
                        if (isVip) "VIP Lv." + (p?.vipLevel ?: 0) else "听歌 Lv." + (p?.musicLevel ?: 0),
                        fontSize = AppTheme.typography.caption.fontSize,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(AppTheme.dimens.spacingSm))
                    Text(
                        // loginType：2=QQ 扫码、1=微信、0=手机号
                        when (session.loginType) {
                            2 -> "QQ 登录"
                            1 -> "微信登录"
                            0 -> "手机号登录"
                            else -> "QQ 登录"
                        },
                        fontSize = AppTheme.typography.caption.fontSize,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(AppTheme.colors.chipBg)
                            .padding(horizontal = AppTheme.dimens.spacingSm, vertical = AppTheme.dimens.spacing3),
                    )
                }
            }
        }
        Spacer(Modifier.height(AppTheme.dimens.spacingSm))
        val dateFmt = remember { java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()) }
        val normalEnd = p?.normalVipEnd ?: 0L
        val superEnd = p?.superVipEnd ?: 0L
        if (normalEnd > 0) {
            Text(
                "普通会员 " + dateFmt.format(java.util.Date(normalEnd)) + " 到期",
                fontSize = AppTheme.typography.caption.fontSize,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (superEnd > 0) {
            Text(
                "超级会员 " + dateFmt.format(java.util.Date(superEnd)) + " 到期",
                fontSize = AppTheme.typography.caption.fontSize,
                color = AppTheme.colors.warning,
            )
        }
        if (normalEnd <= 0 && superEnd <= 0) {
            Text(
                if (isVip) "会员到期时间未知" else "未开通会员 · 开通可听无损",
                fontSize = AppTheme.typography.caption.fontSize,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        }

        Spacer(Modifier.height(AppTheme.dimens.spacing6))
        FoldSection("内容与发现", openSection == "content", onToggle = { toggleSection("content") }) {
            MineRow("收藏的专辑", "收藏的数字专辑") { Nav.push(Route.FavLibrary(true)) }
            MineRow("关注与粉丝", "关注的歌手 / 用户、粉丝、好友") { Nav.push(Route.Social) }
            MineRow("首页模块", "各模块显示 / 隐藏与条数", divider = false) { Nav.push(Route.HomeModules) }
        }
        FoldSection("播放与下载", openSection == "play", onToggle = { toggleSection("play") }) {
            MineRow(
                "硬件音频卸载",
                if (offload) "已开启 · 压缩帧直送 DSP，省 CPU（重启播放器生效）" else "已关闭 · 走应用解码普通路径",
            ) {
                offload = !offload
                Settings.setAudioOffload(context, offload)
                toast = if (offload) "下次播放生效：已启用硬件卸载" else "下次播放生效：已改为普通解码路径"
            }
            MineRow(
                "缓冲卡顿自动降音质",
                if (autoDowngrade) "已开启 · 卡住 6 秒自动降一档" else "已关闭 · 卡顿也保持当前音质",
            ) {
                autoDowngrade = !autoDowngrade
                Settings.setAutoDowngrade(context, autoDowngrade)
            }
            MineRow(
                "桌面歌词",
                if (desktopLyrics) "已开启 · 悬浮窗可拖动" else "已关闭 · 需要悬浮窗权限",
            ) {
                val want = !desktopLyrics
                if (want && !android.provider.Settings.canDrawOverlays(context)) {
                    runCatching {
                        overlayPermission.launch(
                            android.content.Intent(
                                android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                android.net.Uri.parse("package:" + context.packageName),
                            )
                        )
                    }
                } else {
                    desktopLyrics = want
                    Settings.setDesktopLyrics(context, want)
                    com.tianxiaodudou.qqmusicdownloader.player.DesktopLyrics.setEnabled(context, want)
                }
            }
            MineRow("主题", themeMode.label) { showTheme = true }
            MineRow(
                "蓝牙歌词",
                if (btLyrics) "已开启 · 车机标题显示当前歌词" else "已关闭 · 车机显示歌名",
            ) {
                btLyrics = !btLyrics
                Settings.setBtLyrics(context, btLyrics)
                com.tianxiaodudou.qqmusicdownloader.player.PlayerController.refreshMetadata()
            }
            MineRow("付费内容", paidPolicy.label) { showPaid = true }
            MineRow("下载管理", "下载的歌曲与历史记录") { onDownloads() }
            MineRow("下载目录", dirLabel.ifEmpty { "未选择" }) { onPickDir() }
            MineRow("清除所有歌词偏移", offsetCount.toString() + " 首已调整") {
                Settings.clearOffsets(context)
                offsetCount = 0
                toast = "已清除"
            }
            MineRow("清理缓存", "无损解密缓存与临时文件", divider = false) {
                val freed = Settings.clearCache(context)
                toast = "已清理 " + (freed / 1024 / 1024) + " MB"
            }
        }
        FoldSection("账号与关于", openSection == "about", onToggle = { toggleSection("about") }) {
            MineRow("登录状态", loginState) {
                if (loginState.contains("过期")) onLogout()
            }
            MineRow(
                "检查更新",
                if (checking) "检查中…" else "当前 v" + BuildConfig.VERSION_NAME,
            ) { checkUpdate() }
            MineRow("关于", "版本、开源许可与反馈", divider = false) { showAbout = true }
        }

        if (toast.isNotEmpty()) {
            Spacer(Modifier.height(AppTheme.dimens.spacing10))
            Text(
                toast,
                fontSize = AppTheme.typography.bodySmall.fontSize,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = AppTheme.dimens.pagePadding),
            )
        }

        Spacer(Modifier.height(AppTheme.dimens.spacing18))
        Text(
            "QQ音乐播放器 " + BuildConfig.VERSION_NAME,
            fontSize = AppTheme.typography.caption.fontSize,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(horizontal = AppTheme.dimens.pagePadding),
        )
        Spacer(Modifier.height(AppTheme.dimens.spacing10))
        AppSecondaryButton(
            onClick = {
                scope.launch { withContext(Dispatchers.IO) { runCatching { Extras.logout() } } }
                onLogout()
            },
            modifier = Modifier.fillMaxWidth().padding(horizontal = AppTheme.dimens.pagePadding),
        ) { Text("退出登录") }
    }

    if (showPaid) {
        AlertDialog(
            onDismissRequest = { showPaid = false },
            title = { Text("付费内容") },
            text = {
                Column {
                    Text(
                        "付费单曲 / 需单独购买的数字专辑在列表里怎么处理（已购买的不受影响）",
                        fontSize = AppTheme.typography.bodySmall.fontSize,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(AppTheme.dimens.spacing6))
                    Settings.PaidPolicy.entries.forEach { opt ->
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable {
                                    paidPolicy = opt
                                    Settings.setPaidPolicy(context, opt)
                                    showPaid = false
                                }
                                .padding(vertical = AppTheme.dimens.spacingMd),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                if (paidPolicy == opt) "● " else "○ ",
                                color = if (paidPolicy == opt) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(opt.label, fontSize = AppTheme.typography.body.fontSize)
                        }
                    }
                }
            },
            confirmButton = { AppTextButton(onClick = { showPaid = false }) { Text("关闭") } },
        )
    }

    if (showTheme) {
        AlertDialog(
            onDismissRequest = { showTheme = false },
            title = { Text("主题") },
            text = {
                Column {
                    com.tianxiaodudou.qqmusicdownloader.ui.theme.ThemeMode.entries.forEach { opt ->
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable {
                                    themeMode = opt
                                    com.tianxiaodudou.qqmusicdownloader.ui.theme.ThemeStore.set(context, opt)
                                    com.tianxiaodudou.qqmusicdownloader.player.DesktopLyrics.refresh(context)
                                    showTheme = false
                                }
                                .padding(vertical = AppTheme.dimens.spacingMd),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                if (themeMode == opt) "● " else "○ ",
                                color = if (themeMode == opt) AppTheme.colors.brand else AppTheme.colors.textSecondary,
                            )
                            Text(opt.label, style = AppTheme.typography.body)
                        }
                    }
                }
            },
            confirmButton = { AppTextButton("关闭") { showTheme = false } },
        )
    }

    if (showAbout) {
        AlertDialog(
            onDismissRequest = { showAbout = false },
            title = { Text("关于") },
            text = {
                Column {
                    Text("QQ音乐播放器 " + BuildConfig.VERSION_NAME, fontSize = AppTheme.typography.body.fontSize)
                    Spacer(Modifier.height(AppTheme.dimens.spacing6))
                    Text(
                        "完全本地运行：登录、搜索、歌单、播放、歌词都在设备上完成，不经过任何中转服务器。\n\n开源许可：MIT License\n接口参考：QQMusicApi\n问题反馈：github.com/Tianxiaodudou/qqmusic-android/issues",
                        fontSize = AppTheme.typography.bodySmall.fontSize,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = { AppTextButton(onClick = { showAbout = false }) { Text("好") } },
        )
    }

    update?.let { rel ->
        AlertDialog(
            onDismissRequest = { if (!downloading) update = null },
            title = { Text("发现新版本 v" + rel.version) },
            text = {
                Column {
                    if (downloading) {
                        AppLinearProgress(progress / 100f, Modifier.fillMaxWidth())
                        Spacer(Modifier.height(AppTheme.dimens.spacingSm))
                        Text("正在下载 $progress%", fontSize = AppTheme.typography.bodySmall.fontSize, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        Text("更新日志", fontSize = AppTheme.typography.bodySmall.fontSize, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(AppTheme.dimens.spacingXs))
                        Box(
                            Modifier.heightIn(max = AppTheme.dimens.detailCover).verticalScroll(rememberScrollState()),
                        ) {
                            Text(
                                plainNotes(rel.notes).ifEmpty { "这次发布没有取到更新日志，点「浏览器下载」可以看完整说明" },
                                style = AppTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                if (downloading) {
                    AppTextButton(onClick = {}, enabled = false) { Text("下载中…") }
                } else {
                    AppTextButton(onClick = { startDownload(rel) }) { Text("立即更新") }
                }
            },
            dismissButton = {
                if (!downloading) {
                    AppTextButton(onClick = {
                        update = null
                        com.tianxiaodudou.qqmusicdownloader.ui.player.openUrl(
                            context,
                            "https://github.com/Tianxiaodudou/qqmusic-android/releases/latest",
                        )
                    }) { Text("浏览器下载") }
                }
            },
        )
    }

    if (updateError.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { updateError = "" },
            title = { Text("检查更新失败") },
            text = {
                Text(
                    updateError + "\n\n可以直接到 Release 页面下载最新安装包。",
                    fontSize = AppTheme.typography.bodySmall.fontSize,
                )
            },
            confirmButton = {
                AppTextButton(onClick = {
                    com.tianxiaodudou.qqmusicdownloader.ui.player.openUrl(
                        context,
                        "https://github.com/Tianxiaodudou/qqmusic-android/releases/latest",
                    )
                    updateError = ""
                }) { Text("打开浏览器") }
            },
            dismissButton = { AppTextButton(onClick = { updateError = "" }) { Text("关闭") } },
        )
    }
}

/** Release 正文是 Markdown，弹窗里去掉标题井号与加粗星号，保留分条。 */
private fun plainNotes(text: String): String {
    if (text.isBlank()) return ""
    val lines = text.lines().map { raw ->
        var t = raw.trimEnd()
        t = when {
            t.startsWith("###") -> t.removePrefix("###").trim()
            t.startsWith("##") -> t.removePrefix("##").trim()
            t.startsWith("#") -> t.removePrefix("#").trim()
            else -> t
        }
        t.replace("**", "").replace("`", "").trimEnd()
    }
    return lines.dropWhile { it.isBlank() }.joinToString("\n").trim()
}

@Composable
private fun MineRow(title: String, subtitle: String, divider: Boolean = true, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(horizontal = AppTheme.dimens.pagePadding, vertical = AppTheme.dimens.spacingMd),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = AppTheme.typography.body.fontSize, color = MaterialTheme.colorScheme.onSurface)
            if (subtitle.isNotEmpty()) {
                Text(subtitle, fontSize = AppTheme.typography.caption.fontSize, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text("›", style = AppTheme.typography.title, color = AppTheme.colors.textDisabled)
    }
    if (divider) AppDivider(color = AppTheme.colors.line, thickness = AppTheme.dimens.hairline)
}
