package com.tianxiaodudou.qqmusicdownloader.ui.player

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
import androidx.compose.material.icons.rounded.GraphicEq
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
import com.tianxiaodudou.qqmusicdownloader.player.AudioFx
import com.tianxiaodudou.qqmusicdownloader.player.PlayerController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

private val SheetTitle = Color(0xFFF0F3F8)
private val SheetSub = Color(0xFF8C96A6)
private val SheetLine = Color(0xFF262C36)
private val SheetRow = Color(0xFF1B2029)
private val Accent = Color(0xFF1FC365)

@Composable
private fun SheetHeader(title: String, subtitle: String = "", trailing: @Composable (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(start = 22.dp, end = 12.dp, top = 2.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = SheetTitle)
            if (subtitle.isNotEmpty()) Text(subtitle, fontSize = 11.sp, color = SheetSub)
        }
        trailing?.invoke()
    }
}

@Composable
fun QualitySheet(current: Settings.Quality, onPick: (Settings.Quality) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        SheetHeader("音质", "只影响播放文件的码率，音效请到「更多 → 均衡器」")
        Settings.Quality.entries.forEach { q ->
            Row(
                Modifier.fillMaxWidth().clickable { onPick(q) }.padding(horizontal = 22.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(q.label, fontSize = 15.sp, color = SheetTitle)
                    Text(q.detail, fontSize = 11.sp, color = SheetSub)
                }
                if (q == current) Icon(Icons.Rounded.Check, null, tint = Accent, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
fun SyncSheet(offsetMs: Int, onChange: (Int) -> Unit, onReset: () -> Unit) {
    var value by remember { mutableStateOf(offsetMs / 1000f) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp).padding(bottom = 18.dp)) {
        SheetHeader("歌词同步", "拖动让歌词和声音对齐，仅对当前歌曲生效")
        Text(
            text = offsetText(offsetMs),
            fontSize = 26.sp,
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
            Text("−5.0s", fontSize = 11.sp, color = SheetSub)
            Text("0", fontSize = 11.sp, color = SheetSub)
            Text("+5.0s", fontSize = 11.sp, color = SheetSub)
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
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
        Spacer(Modifier.height(12.dp))
        Text("仅对当前歌曲生效", fontSize = 11.sp, color = SheetSub)
        TextButton(onClick = { value = 0f; onReset() }) { Text("重置为 0", fontSize = 13.sp, color = Accent) }
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
            .height(40.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(SheetRow)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, fontSize = 13.sp, color = SheetTitle) }
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
    Column(Modifier.fillMaxWidth().fillMaxHeight(0.62f).padding(bottom = 8.dp)) {
        SheetHeader("播放队列 · " + queue.size + " 首", trailing = {
            TextButton(onClick = onClear) { Text("清空", fontSize = 12.sp, color = SheetSub) }
        })
        if (queue.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text("队列是空的", fontSize = 13.sp, color = SheetSub)
            }
        } else {
            LazyColumn(Modifier.weight(1f)) {
                itemsIndexed(queue) { i, song ->
                    val current = i == index
                    Row(
                        Modifier.fillMaxWidth()
                            .combinedClickable(onClick = { onPick(i) }, onLongClick = { pendingDelete = i })
                            .padding(horizontal = 22.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.width(26.dp)) {
                            if (current) {
                                Icon(
                                    if (playing) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                                    null,
                                    tint = Accent,
                                    modifier = Modifier.size(16.dp),
                                )
                            } else {
                                Text((i + 1).toString().padStart(2, '0'), fontSize = 11.sp, color = SheetSub)
                            }
                        }
                        Column(Modifier.weight(1f)) {
                            Text(
                                song.name,
                                fontSize = 14.sp,
                                color = if (current) Accent else SheetTitle,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                song.singers,
                                fontSize = 11.sp,
                                color = SheetSub,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(fmtMs(song.interval * 1000L), fontSize = 11.sp, color = SheetSub)
                    }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
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
                TextButton(onClick = {
                    onRemove(i)
                    pendingDelete = null
                }) { Text("移除") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } },
        )
    }
}

@Composable
fun MoreSheet(
    qualityLabel: String,
    speed: Float,
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
    onEq: () -> Unit,
    onSpeed: () -> Unit,
    onSleep: () -> Unit,
    onStyle: () -> Unit,
    onClearOffsets: () -> Unit,
    onFeedback: () -> Unit,
    onAbout: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
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
        MenuRow(Icons.Rounded.GraphicEq, "均衡器", value = qualityLabel, onClick = onEq)
        MenuRow(Icons.Rounded.Speed, "播放速度", value = String.format("%.1fx", speed), onClick = onSpeed)
        MenuRow(Icons.Rounded.Bedtime, "睡眠定时", value = if (sleepActive) "已开启" else "", onClick = onSleep)
        MenuRow(Icons.Rounded.TextFields, "歌词样式", onClick = onStyle)
        if (offsetCount > 0) {
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onClearOffsets)
                    .padding(horizontal = 22.dp, vertical = 9.dp),
            ) {
                Text("清除所有歌曲的歌词偏移（" + offsetCount + " 首）", fontSize = 11.sp, color = SheetSub)
            }
        }
        MenuDivider()
        MenuRow(Icons.Rounded.Feedback, "反馈问题", onClick = onFeedback)
        MenuRow(Icons.Rounded.Info, "关于", onClick = onAbout)
    }
}

@Composable
private fun MenuDivider() {
    Box(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 6.dp).height(1.dp).background(SheetLine))
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
            .padding(horizontal = 22.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = Color(0xFFB8C1CF), modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(14.dp))
        Text(label, fontSize = 14.5.sp, color = SheetTitle, modifier = Modifier.weight(1f))
        if (value.isNotEmpty()) Text(value, fontSize = 12.sp, color = SheetSub)
        Icon(Icons.Rounded.ChevronRight, null, tint = Color(0xFF4A5262), modifier = Modifier.size(18.dp))
    }
}

@Composable
fun EqSheet(current: Int, onPick: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        SheetHeader("均衡器", "改变声音风格，不影响音质")
        AudioFx.PRESETS.forEachIndexed { i, name ->
            Row(
                Modifier.fillMaxWidth().clickable { onPick(i) }.padding(horizontal = 22.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(name, fontSize = 14.sp, color = SheetTitle, modifier = Modifier.weight(1f))
                if (i == current) Icon(Icons.Rounded.Check, null, tint = Accent, modifier = Modifier.size(19.dp))
            }
        }
    }
}

@Composable
fun SpeedSheet(current: Float, onPick: (Float) -> Unit) {
    val options = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)
    Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        SheetHeader("播放速度")
        options.forEach { v ->
            Row(
                Modifier.fillMaxWidth().clickable { onPick(v) }.padding(horizontal = 22.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (v == 1f) "1.0x（原速）" else v.toString().removeSuffix(".0") + "x",
                    fontSize = 14.sp,
                    color = SheetTitle,
                    modifier = Modifier.weight(1f),
                )
                if (abs(v - current) < 0.01f) Icon(Icons.Rounded.Check, null, tint = Accent, modifier = Modifier.size(19.dp))
            }
        }
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
    Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        SheetHeader(
            "睡眠定时",
            if (afterCurrent) "当前歌曲播完就停" else if (sleepEndAt > 0) "还剩 " + fmtMs(remaining * 1000) else "到点自动停止播放",
        )
        listOf(15, 30, 60, 90).forEach { m ->
            Row(
                Modifier.fillMaxWidth().clickable { onMinutes(m) }.padding(horizontal = 22.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(m.toString() + " 分钟", fontSize = 14.sp, color = SheetTitle, modifier = Modifier.weight(1f))
            }
        }
        Row(
            Modifier.fillMaxWidth().clickable { onAfterCurrent() }.padding(horizontal = 22.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("播完当前歌曲", fontSize = 14.sp, color = SheetTitle, modifier = Modifier.weight(1f))
            if (afterCurrent) Icon(Icons.Rounded.Check, null, tint = Accent, modifier = Modifier.size(19.dp))
        }
        if (sleepEndAt > 0 || afterCurrent) {
            TextButton(onClick = onCancel, modifier = Modifier.padding(horizontal = 12.dp)) {
                Text("关闭定时", fontSize = 13.sp, color = SheetSub)
            }
        }
    }
}

@Composable
fun StyleSheet(style: Settings.LyricStyle, onChange: (Settings.LyricStyle) -> Unit, onReset: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
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
        TextButton(onClick = onReset, modifier = Modifier.padding(horizontal = 12.dp)) {
            Text("重置为默认", fontSize = 13.sp, color = SheetSub)
        }
    }
}

@Composable
private fun SettingsRow(label: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 8.dp)) {
        Text(label, fontSize = 13.sp, color = SheetSub)
        Spacer(Modifier.height(6.dp))
        content()
    }
}

@Composable
private fun Segment(options: List<Pair<String, Float>>, selected: Float, onSelect: (Float) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(SheetRow).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        options.forEach { (label, v) ->
            val on = abs(v - selected) < 0.01f
            Box(
                Modifier.weight(1f).height(34.dp).clip(RoundedCornerShape(8.dp))
                    .background(if (on) Color(0xFF2A3440) else Color.Transparent)
                    .clickable { onSelect(v) },
                contentAlignment = Alignment.Center,
            ) {
                Text(label, fontSize = 12.5.sp, color = if (on) SheetTitle else SheetSub)
            }
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 14.sp, color = SheetTitle, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF06230F), checkedTrackColor = Accent),
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
    Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp).padding(bottom = 20.dp)) {
        SheetHeader("歌曲信息")
        InfoRow("歌名", song.name)
        InfoRow("演唱", song.singers)
        InfoRow("专辑", song.album.ifEmpty { "—" })
        InfoRow("音源", if (source.isEmpty()) qualityLabel else source)
        InfoRow("格式", if (source.contains("FLAC")) "FLAC" else "MP3")
        InfoRow("文件", path.ifEmpty { "在线播放（未缓存）" })
        InfoRow("封面", if (song.albumMid.isEmpty()) "无" else "腾讯 CDN · T002R400x400M000" + song.albumMid)
        InfoRow("歌词", lyricSource.ifEmpty { "未加载" })
        extra?.let { e ->
            if (e.first.isNotEmpty()) InfoRow("标签", e.first.joinToString(" · "))
            if (e.second.isNotEmpty()) InfoRow("制作", e.second.take(3).joinToString(" · ") { it.first + " " + it.second })
            if (e.third.isNotEmpty()) InfoRow("收藏", e.third)
        }
        hasSheet?.let { InfoRow("曲谱", if (it) "有" else "无") }
        TextButton(onClick = onClose) { Text("关闭", fontSize = 13.sp, color = SheetSub) }
    }
}

@Composable
private fun InfoRow(key: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
        Text(key, fontSize = 12.sp, color = SheetSub, modifier = Modifier.width(52.dp))
        Text(value, fontSize = 12.5.sp, color = SheetTitle, modifier = Modifier.weight(1f))
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

    Column(Modifier.fillMaxWidth().fillMaxHeight(0.55f).padding(bottom = 12.dp)) {
        SheetHeader("加到歌单", "选择要加入的歌单")
        val data = lists
        if (data == null) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text("加载中…", fontSize = 13.sp, color = SheetSub)
            }
        } else if (data.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text("还没有自建歌单", fontSize = 13.sp, color = SheetSub)
            }
        } else {
            LazyColumn(Modifier.weight(1f)) {
                items(data) { item ->
                    Row(
                        Modifier.fillMaxWidth().clickable { addTo(item) }
                            .padding(horizontal = 22.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(item.name, fontSize = 14.sp, color = SheetTitle, modifier = Modifier.weight(1f))
                        Text(item.count.toString() + " 首", fontSize = 11.sp, color = SheetSub)
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedAction("收藏到「我喜欢」", Modifier.weight(1f)) { addTo(null) }
            OutlinedAction("新建歌单", Modifier.weight(1f)) { newName = ""; creating = true }
        }
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
            dismissButton = { TextButton(onClick = { creating = false }) { Text("取消") } },
        )
    }
}
