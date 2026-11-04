package com.tianxiaodudou.qqmusicdownloader.ui.player

import com.tianxiaodudou.qqmusicdownloader.ui.AppInput

import com.tianxiaodudou.qqmusicdownloader.ui.AppSpinner

import com.tianxiaodudou.qqmusicdownloader.ui.AppTextButton

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Feedback
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import com.tianxiaodudou.qqmusicdownloader.ui.theme.AppTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tianxiaodudou.qqmusicdownloader.net.LyricApi
import com.tianxiaodudou.qqmusicdownloader.net.MusicApi
import com.tianxiaodudou.qqmusicdownloader.net.PlaylistApi
import com.tianxiaodudou.qqmusicdownloader.net.Settings
import com.tianxiaodudou.qqmusicdownloader.player.PlayerController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

private val SheetTitle: Color
    @Composable get() = AppTheme.colors.textPrimary
private val SheetSub: Color
    @Composable get() = AppTheme.colors.textSecondary
private val SheetLine: Color
    @Composable get() = AppTheme.colors.line
private val SheetRow: Color
    @Composable get() = AppTheme.colors.surface2
private val Accent: Color
    @Composable get() = AppTheme.colors.brand

@Composable
private fun SheetHeader(title: String, subtitle: String = "", trailing: @Composable (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(start = AppTheme.dimens.spacing22, end = AppTheme.dimens.spacingMd, top = AppTheme.dimens.spacing2, bottom = AppTheme.dimens.spacingMd),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = AppTheme.typography.sectionTitle.fontSize, fontWeight = FontWeight.SemiBold, color = SheetTitle)
            if (subtitle.isNotEmpty()) Text(subtitle, fontSize = AppTheme.typography.caption.fontSize, color = SheetSub)
        }
        trailing?.invoke()
    }
}

@Composable
fun QualitySheet(current: Settings.Quality, song: MusicApi.Song? = null, onPick: (Settings.Quality) -> Unit) {
    // 两种不可用要区分开：这首歌没有该档位（灰）/ 有档位但账号没权限（红）
    fun hasSource(q: Settings.Quality): Boolean {
        val s = song ?: return true
        return when (q) {
            Settings.Quality.STANDARD -> s.size128 > 0 || s.size320 > 0
            Settings.Quality.HIGH -> s.size320 > 0
            Settings.Quality.LOSSLESS -> s.sizeFlac > 0
            Settings.Quality.HIRES -> s.sizeFlac > 0 && s.sizeNew.isNotEmpty()
            Settings.Quality.MASTER -> s.sizeNew.isNotEmpty()
            Settings.Quality.DOLBY -> s.sizeDolby > 0
        }
    }

    // 与「音频状态卡片」共用同一份大小映射，避免两处显示不一致
    fun bytesOf(q: Settings.Quality): Long =
        song?.let { com.tianxiaodudou.qqmusicdownloader.player.SongSize.bytesOf(it, q) } ?: 0L

    fun fmtSize(bytes: Long): String = com.tianxiaodudou.qqmusicdownloader.player.SongSize.fmt(bytes)

    fun hasPermission(q: Settings.Quality): Boolean {
        val acc = com.tianxiaodudou.qqmusicdownloader.net.UserApi.Account
        return when (q) {
            Settings.Quality.STANDARD, Settings.Quality.HIGH -> true
            Settings.Quality.LOSSLESS, Settings.Quality.HIRES -> acc.isVip
            Settings.Quality.MASTER, Settings.Quality.DOLBY -> acc.isSuper
        }
    }

    Column(Modifier.fillMaxWidth().padding(bottom = AppTheme.dimens.spacingMd)) {
        SheetHeader("音质上限", "灰色=这首歌没有该档位；红色=账号无权限（自动降级）")
        Settings.Quality.entries.forEach { q ->
            val source = hasSource(q)
            val allowed = hasPermission(q)
            val usable = source && allowed
            Row(
                Modifier.fillMaxWidth()
                    .clickable(enabled = usable) { onPick(q) }
                    .padding(horizontal = AppTheme.dimens.spacing22, vertical = AppTheme.dimens.spacing13),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        q.label,
                        fontSize = AppTheme.typography.title.fontSize,
                        color = when {
                            !source -> AppTheme.colors.textDisabled
                            !allowed -> AppTheme.colors.error
                            else -> SheetTitle
                        },
                    )
                    Text(
                        when {
                            !source -> "这首歌没有该音质"
                            !allowed -> "账号无权限，需要会员"
                            else -> {
                                val size = fmtSize(bytesOf(q))
                                if (size.isEmpty()) q.detail else q.detail + " · " + size
                            }
                        },
                        fontSize = AppTheme.typography.caption.fontSize,
                        color = when {
                            !source -> AppTheme.colors.textDisabled
                            !allowed -> AppTheme.colors.error
                            else -> SheetSub
                        },
                    )
                }
                if (q == current) {
                    Icon(
                        Icons.Rounded.Check,
                        null,
                        tint = if (usable) Accent else AppTheme.colors.textDisabled,
                        modifier = Modifier.size(AppTheme.dimens.iconMd),
                    )
                }
            }
        }
    }
}

@Composable
fun SyncSheet(offsetMs: Int, onChange: (Int) -> Unit, onReset: () -> Unit) {
    var value by remember { mutableStateOf(offsetMs / 1000f) }
    Column(Modifier.fillMaxWidth().padding(horizontal = AppTheme.dimens.spacing22).padding(bottom = AppTheme.dimens.spacing18)) {
        SheetHeader("歌词同步", "拖动让歌词和声音对齐，仅对当前歌曲生效")
        Text(
            text = offsetText(offsetMs),
            fontSize = AppTheme.typography.pageTitle.fontSize,
            fontWeight = FontWeight.SemiBold,
            color = Accent,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
        Slider(
            value = value,
            onValueChange = {
                value = it
                onChange((it * 10).roundToInt() * 100)
            },
            valueRange = -5f..5f,
            colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("−5.0s", fontSize = AppTheme.typography.caption.fontSize, color = SheetSub)
            Text("0", fontSize = AppTheme.typography.caption.fontSize, color = SheetSub)
            Text("+5.0s", fontSize = AppTheme.typography.caption.fontSize, color = SheetSub)
        }
        Row(
            Modifier.fillMaxWidth().padding(top = AppTheme.dimens.spacingSm),
            horizontalArrangement = Arrangement.spacedBy(AppTheme.dimens.spacingMd),
        ) {
            OutlinedAction("−0.5s", Modifier.weight(1f)) {
                val next = (offsetMs - 500).coerceIn(-5000, 5000)
                value = next / 1000f
                onChange(next)
            }
            OutlinedAction("+0.5s", Modifier.weight(1f)) {
                val next = (offsetMs + 500).coerceIn(-5000, 5000)
                value = next / 1000f
                onChange(next)
            }
        }
        Spacer(Modifier.height(AppTheme.dimens.spacingMd))
        Text("仅对当前歌曲生效", fontSize = AppTheme.typography.caption.fontSize, color = SheetSub)
        AppTextButton(onClick = { value = 0f; onReset() }) { Text("重置为 0", fontSize = AppTheme.typography.bodySmall.fontSize, color = Accent) }
    }
}

private fun offsetText(ms: Int): String {
    val v = ms / 1000f
    val s = if (v >= 0) "+" + String.format("%.1f", v) else String.format("%.1f", v)
    return s + "s"
}

@Composable
private fun OutlinedAction(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .height(AppTheme.dimens.coverSm)
            .clip(AppTheme.shapes.cardSmall)
            .background(SheetRow)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, fontSize = AppTheme.typography.bodySmall.fontSize, color = SheetTitle) }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun QueueSheet(
    queue: List<MusicApi.Song>,
    index: Int,
    playing: Boolean,
    onPick: (Int) -> Unit,
    onRemove: (Int) -> Unit,
    onClear: () -> Unit,
    onShuffleAll: () -> Unit,
) {
    var pendingDelete by remember { mutableStateOf<Int?>(null) }
    Column(Modifier.fillMaxWidth().fillMaxHeight(0.62f).padding(bottom = AppTheme.dimens.spacingSm)) {
        SheetHeader("播放队列 · " + queue.size + " 首", trailing = {
            AppTextButton(onClick = onClear) { Text("清空", fontSize = AppTheme.typography.bodySmall.fontSize, color = SheetSub) }
        })
        if (queue.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text("队列是空的", fontSize = AppTheme.typography.bodySmall.fontSize, color = SheetSub)
            }
        } else {
            LazyColumn(Modifier.weight(1f)) {
                itemsIndexed(queue) { i, song ->
                    val current = i == index
                    Row(
                        Modifier.fillMaxWidth()
                            .combinedClickable(onClick = { onPick(i) }, onLongClick = { pendingDelete = i })
                            .padding(horizontal = AppTheme.dimens.spacing22, vertical = AppTheme.dimens.spacing11),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.width(AppTheme.dimens.spacingXl + AppTheme.dimens.spacing2)) {
                            if (current) {
                                Icon(
                                    if (playing) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                                    null,
                                    tint = Accent,
                                    modifier = Modifier.size(AppTheme.dimens.iconSm),
                                )
                            } else {
                                Text((i + 1).toString().padStart(2, '0'), fontSize = AppTheme.typography.caption.fontSize, color = SheetSub)
                            }
                        }
                        Column(Modifier.weight(1f)) {
                            Text(
                                song.name,
                                fontSize = AppTheme.typography.body.fontSize,
                                color = if (current) Accent else SheetTitle,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                song.singers,
                                fontSize = AppTheme.typography.caption.fontSize,
                                color = SheetSub,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(fmtMs(song.interval * 1000L), fontSize = AppTheme.typography.caption.fontSize, color = SheetSub)
                    }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = AppTheme.dimens.spacing18, vertical = AppTheme.dimens.spacing6),
            horizontalArrangement = Arrangement.spacedBy(AppTheme.dimens.spacingMd),
        ) {
            OutlinedAction("随机播放全部", Modifier.weight(1f)) { onShuffleAll() }
        }
    }
    pendingDelete?.let { i ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("从队列移除") },
            text = { Text(queue.getOrNull(i)?.name ?: "") },
            confirmButton = {
                AppTextButton(onClick = {
                    onRemove(i)
                    pendingDelete = null
                }) { Text("移除") }
            },
            dismissButton = { AppTextButton(onClick = { pendingDelete = null }) { Text("取消") } },
        )
    }
}

@Composable
fun MoreSheet(
    qualityLabel: String,
    sleepActive: Boolean,
    offsetCount: Int,
    onShare: () -> Unit,
    onInfo: () -> Unit,
    onAlbum: () -> Unit,
    onSinger: () -> Unit,
    onComments: () -> Unit,
    onSimilar: () -> Unit,
    onRelated: () -> Unit,
    onVersions: () -> Unit,
    onSleep: () -> Unit,
    onStyle: () -> Unit,
    onClearOffsets: () -> Unit,
    onFeedback: () -> Unit,
    onAbout: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(bottom = AppTheme.dimens.pagePadding)) {
        SheetHeader("更多")
        MenuRow(Icons.Rounded.Share, "分享歌曲", onClick = onShare)
        MenuRow(Icons.Rounded.Info, "歌曲信息", onClick = onInfo)
        MenuRow(Icons.Rounded.Album, "查看专辑", onClick = onAlbum)
        MenuRow(Icons.Rounded.Person, "查看歌手", onClick = onSinger)
        MenuRow(Icons.Rounded.ChatBubbleOutline, "评论", onClick = onComments)
        MenuRow(Icons.Rounded.MusicNote, "相似歌曲", onClick = onSimilar)
        MenuRow(Icons.Rounded.QueueMusic, "相关歌单", onClick = onRelated)
        MenuRow(Icons.Rounded.RestartAlt, "其他版本", onClick = onVersions)
        MenuDivider()
        MenuRow(Icons.Rounded.Bedtime, "睡眠定时", value = if (sleepActive) "已开启" else "", onClick = onSleep)
        MenuRow(Icons.Rounded.TextFields, "歌词样式", onClick = onStyle)
        if (offsetCount > 0) {
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onClearOffsets)
                    .padding(horizontal = AppTheme.dimens.spacing22, vertical = AppTheme.dimens.spacing9),
            ) {
                Text("清除所有歌曲的歌词偏移（" + offsetCount + " 首）", fontSize = AppTheme.typography.caption.fontSize, color = SheetSub)
            }
        }
        MenuDivider()
        MenuRow(Icons.Rounded.Feedback, "反馈问题", onClick = onFeedback)
        MenuRow(Icons.Rounded.Info, "关于", onClick = onAbout)
    }
}

@Composable
private fun MenuDivider() {
    Box(Modifier.fillMaxWidth().padding(horizontal = AppTheme.dimens.spacing22, vertical = AppTheme.dimens.spacing6).height(AppTheme.dimens.divider).background(SheetLine))
}

@Composable
private fun MenuRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String = "",
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(horizontal = AppTheme.dimens.spacing22, vertical = AppTheme.dimens.spacing13),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = AppTheme.colors.textPrimary, modifier = Modifier.size(AppTheme.dimens.iconMd))
        Spacer(Modifier.width(AppTheme.dimens.spacing14))
        Text(label, fontSize = AppTheme.typography.body.fontSize, color = SheetTitle, modifier = Modifier.weight(1f))
        if (value.isNotEmpty()) Text(value, fontSize = AppTheme.typography.bodySmall.fontSize, color = SheetSub)
        Icon(Icons.Rounded.ChevronRight, null, tint = AppTheme.colors.textDisabled, modifier = Modifier.size(AppTheme.dimens.iconMd))
    }
}


@Composable
fun SleepSheet(
    sleepEndAt: Long,
    afterCurrent: Boolean,
    onMinutes: (Int) -> Unit,
    onAfterCurrent: () -> Unit,
    onCancel: () -> Unit,
) {
    val remaining = ((sleepEndAt - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
    Column(Modifier.fillMaxWidth().padding(bottom = AppTheme.dimens.pagePadding)) {
        SheetHeader(
            "睡眠定时",
            if (afterCurrent) "当前歌曲播完就停" else if (sleepEndAt > 0) "还剩 " + fmtMs(remaining * 1000) else "到点自动停止播放",
        )
        listOf(15, 30, 60, 90).forEach { m ->
            Row(
                Modifier.fillMaxWidth().clickable { onMinutes(m) }.padding(horizontal = AppTheme.dimens.spacing22, vertical = AppTheme.dimens.spacingMd),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(m.toString() + " 分钟", fontSize = AppTheme.typography.body.fontSize, color = SheetTitle, modifier = Modifier.weight(1f))
            }
        }
        Row(
            Modifier.fillMaxWidth().clickable { onAfterCurrent() }.padding(horizontal = AppTheme.dimens.spacing22, vertical = AppTheme.dimens.spacingMd),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("播完当前歌曲", fontSize = AppTheme.typography.body.fontSize, color = SheetTitle, modifier = Modifier.weight(1f))
            if (afterCurrent) Icon(Icons.Rounded.Check, null, tint = Accent, modifier = Modifier.size(AppTheme.dimens.iconMd))
        }
        if (sleepEndAt > 0 || afterCurrent) {
            AppTextButton(onClick = onCancel, modifier = Modifier.padding(horizontal = AppTheme.dimens.spacingMd)) {
                Text("关闭定时", fontSize = AppTheme.typography.bodySmall.fontSize, color = SheetSub)
            }
        }
    }
}

@Composable
fun StyleSheet(style: Settings.LyricStyle, onChange: (Settings.LyricStyle) -> Unit, onReset: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(bottom = AppTheme.dimens.pagePadding)) {
        SheetHeader("歌词样式", "字体大小、对齐、翻译与音译")
        SettingsRow("字体大小") {
            Segment(
                options = listOf("小" to 0.88f, "中" to 1f, "大" to 1.18f),
                selected = style.scale,
                onSelect = { onChange(style.copy(scale = it)) },
            )
        }
        SettingsRow("对齐方式") {
            Segment(
                options = listOf("居中" to 1f, "左对齐" to 0f),
                selected = if (style.center) 1f else 0f,
                onSelect = { onChange(style.copy(center = it == 1f)) },
            )
        }
        ToggleRow("显示翻译", style.showTrans) { onChange(style.copy(showTrans = it)) }
        ToggleRow("显示音译", style.showRoma) { onChange(style.copy(showRoma = it)) }
        AppTextButton(onClick = onReset, modifier = Modifier.padding(horizontal = AppTheme.dimens.spacingMd)) {
            Text("重置为默认", fontSize = AppTheme.typography.bodySmall.fontSize, color = SheetSub)
        }
    }
}

@Composable
private fun SettingsRow(label: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = AppTheme.dimens.spacing22, vertical = AppTheme.dimens.spacingSm)) {
        Text(label, fontSize = AppTheme.typography.bodySmall.fontSize, color = SheetSub)
        Spacer(Modifier.height(AppTheme.dimens.spacing6))
        content()
    }
}

@Composable
private fun Segment(options: List<Pair<String, Float>>, selected: Float, onSelect: (Float) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(AppTheme.shapes.cardSmall).background(SheetRow).padding(AppTheme.dimens.spacing3),
        horizontalArrangement = Arrangement.spacedBy(AppTheme.dimens.spacing3),
    ) {
        options.forEach { (label, v) ->
            val on = abs(v - selected) < 0.01f
            Box(
                Modifier.weight(1f).height(AppTheme.dimens.albumBarHeight).clip(AppTheme.shapes.cover)
                    .background(if (on) AppTheme.colors.surface3 else Color.Transparent)
                    .clickable { onSelect(v) },
                contentAlignment = Alignment.Center,
            ) {
                Text(label, fontSize = AppTheme.typography.bodySmall.fontSize, color = if (on) SheetTitle else SheetSub)
            }
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = AppTheme.dimens.spacing22, vertical = AppTheme.dimens.spacing6),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = AppTheme.typography.body.fontSize, color = SheetTitle, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedThumbColor = AppTheme.colors.switchThumb, checkedTrackColor = Accent),
        )
    }
}

@Composable
fun SongInfoSheet(
    song: MusicApi.Song,
    qualityLabel: String,
    source: String,
    path: String,
    lyricSource: String,
    onClose: () -> Unit,
) {
    var extra by remember(song.id) {
        mutableStateOf<Triple<List<String>, List<Pair<String, String>>, String>?>(null)
    }
    var hasSheet by remember(song.mid) { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(song.id, song.mid) {
        val data = withContext(Dispatchers.IO) {
            Triple(
                runCatching { com.tianxiaodudou.qqmusicdownloader.net.Extras.labels(song.id) }.getOrDefault(emptyList()),
                runCatching { com.tianxiaodudou.qqmusicdownloader.net.Extras.producers(song.id) }.getOrDefault(emptyList()),
                runCatching { com.tianxiaodudou.qqmusicdownloader.net.Extras.favNumber(song.id) }.getOrDefault(""),
            )
        }
        val sheet = withContext(Dispatchers.IO) {
            runCatching { com.tianxiaodudou.qqmusicdownloader.net.Extras.hasSheet(song.mid) }.getOrDefault(false)
        }
        extra = data
        hasSheet = sheet
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = AppTheme.dimens.spacing22).padding(bottom = AppTheme.dimens.spacing20)) {
        SheetHeader("歌曲信息")
        InfoRow("歌名", song.name)
        InfoRow("演唱", song.singers)
        InfoRow("专辑", song.album.ifEmpty { "—" })
        InfoRow("音源", if (source.isEmpty()) qualityLabel else source)
        InfoRow("格式", if (source.contains("FLAC")) "FLAC" else "MP3")
        InfoRow("文件", path.ifEmpty { "在线播放（未缓存）" })
        InfoRow("封面", if (song.albumMid.isEmpty()) "无" else "腾讯 CDN · T002R500x500M000" + song.albumMid)
        InfoRow("歌词", lyricSource.ifEmpty { "未加载" })
        extra?.let { e ->
            if (e.first.isNotEmpty()) InfoRow("标签", e.first.joinToString(" · "))
            if (e.second.isNotEmpty()) InfoRow("制作", e.second.take(3).joinToString(" · ") { it.first + " " + it.second })
            if (e.third.isNotEmpty()) InfoRow("收藏", e.third)
        }
        hasSheet?.let { InfoRow("曲谱", if (it) "有" else "无") }
        AppTextButton(onClick = onClose) { Text("关闭", fontSize = AppTheme.typography.bodySmall.fontSize, color = SheetSub) }
    }
}

@Composable
private fun InfoRow(key: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = AppTheme.dimens.spacing6), verticalAlignment = Alignment.Top) {
        Text(key, fontSize = AppTheme.typography.bodySmall.fontSize, color = SheetSub, modifier = Modifier.width(AppTheme.dimens.spacing52))
        Text(value, fontSize = AppTheme.typography.bodySmall.fontSize, color = SheetTitle, modifier = Modifier.weight(1f))
    }
}

@Composable
fun AddToSheet(song: MusicApi.Song, onDone: (String) -> Unit) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var lists by remember { mutableStateOf<List<PlaylistApi.Item>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        lists = withContext(Dispatchers.IO) { runCatching { PlaylistApi.mine() }.getOrNull() ?: emptyList() }
    }

    fun addTo(item: PlaylistApi.Item?) {
        if (busy) return
        busy = true
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    if (item == null) PlaylistApi.like(song)
                    else PlaylistApi.addSong(item.dirId.toIntOrNull() ?: 0, item.id.toIntOrNull() ?: 0, song)
                }.getOrNull()
            } == true
            busy = false
            onDone(
                when {
                    !ok -> "操作失败，请稍后再试"
                    item == null -> "已收藏到「我喜欢」"
                    else -> "已加到「" + item.name + "」"
                }
            )
        }
    }

    Column(Modifier.fillMaxWidth().fillMaxHeight(0.55f).padding(bottom = AppTheme.dimens.spacingMd)) {
        SheetHeader("加到歌单", "选择要加入的歌单")
        if (busy) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = AppTheme.dimens.spacing22, vertical = AppTheme.dimens.spacing6),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppSpinner(
                    modifier = Modifier.size(AppTheme.dimens.iconSm),
                    strokeWidth = AppTheme.dimens.stroke2,
                    color = Accent,
                )
                Spacer(Modifier.width(AppTheme.dimens.spacingSm))
                Text("正在加入…", fontSize = AppTheme.typography.bodySmall.fontSize, color = SheetSub)
            }
        }
        val data = lists
        if (data == null) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    AppSpinner(
                        modifier = Modifier.size(AppTheme.dimens.iconLg),
                        strokeWidth = AppTheme.dimens.stroke2,
                        color = Accent,
                    )
                    Spacer(Modifier.height(AppTheme.dimens.spacingSm))
                    Text("正在读取歌单…", fontSize = AppTheme.typography.bodySmall.fontSize, color = SheetSub)
                }
            }
        } else if (data.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text("还没有自建歌单", fontSize = AppTheme.typography.bodySmall.fontSize, color = SheetSub)
            }
        } else {
            LazyColumn(Modifier.weight(1f)) {
                items(data) { item ->
                    Row(
                        Modifier.fillMaxWidth().clickable { addTo(item) }
                            .padding(horizontal = AppTheme.dimens.spacing22, vertical = AppTheme.dimens.spacingMd),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(item.name, fontSize = AppTheme.typography.body.fontSize, color = SheetTitle, modifier = Modifier.weight(1f))
                        Text(item.count.toString() + " 首", fontSize = AppTheme.typography.caption.fontSize, color = SheetSub)
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = AppTheme.dimens.spacing18, vertical = AppTheme.dimens.spacingXs), horizontalArrangement = Arrangement.spacedBy(AppTheme.dimens.spacing10)) {
            OutlinedAction("收藏到「我喜欢」", Modifier.weight(1f)) { addTo(null) }
            OutlinedAction("新建歌单", Modifier.weight(1f)) { newName = ""; creating = true }
        }
    }

    if (creating) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { if (!busy) creating = false },
            title = { Text("新建歌单") },
            text = {
                AppInput(
                    value = newName,
                    onValueChange = { newName = it },
                    singleLine = true,
                    placeholder = "歌单名称",
                )
            },
            confirmButton = {
                AppTextButton(onClick = {
                    val n = newName.trim()
                    if (n.isEmpty() || busy) return@AppTextButton
                    busy = true
                    scope.launch {
                        val item = withContext(Dispatchers.IO) { runCatching { PlaylistApi.create(n) }.getOrNull() }
                        val ok = if (item == null) false else withContext(Dispatchers.IO) {
                            runCatching {
                                PlaylistApi.addSong(item.dirId.toIntOrNull() ?: 0, item.id.toIntOrNull() ?: 0, song)
                            }.getOrDefault(false)
                        }
                        busy = false
                        creating = false
                        onDone(if (ok) "已创建「" + n + "」并加入" else "创建或加入失败，可能需要重新登录")
                    }
                }) { Text(if (busy) "处理中…" else "创建并加入") }
            },
            dismissButton = { AppTextButton(onClick = { creating = false }) { Text("取消") } },
        )
    }
}
