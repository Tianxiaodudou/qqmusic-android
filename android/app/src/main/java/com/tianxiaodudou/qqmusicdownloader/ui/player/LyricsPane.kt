package com.tianxiaodudou.qqmusicdownloader.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import com.tianxiaodudou.qqmusicdownloader.ui.theme.AppTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.tianxiaodudou.qqmusicdownloader.net.LyricApi
import com.tianxiaodudou.qqmusicdownloader.net.Settings
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

private val LyricBright: Color
    @Composable get() = AppTheme.colors.lyricActive
private val LyricSung: Color
    @Composable get() = AppTheme.colors.lyricSung
private val LyricDim: Color
    @Composable get() = AppTheme.colors.lyricDim

/**
 * 滚动歌词区：小封面 + 逐字歌词 + 手动滚动让位 + 点击跳转 + 长按分享。
 * 自动跟随时当前句固定在区域约 1/3 处，手指一碰就暂停跟随，4 秒后自动恢复。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LyricsPane(
    lines: List<LyricApi.Line>,
    positionProvider: () -> Long,
    offsetMs: Int,
    style: Settings.LyricStyle,
    playing: Boolean,
    flashIndex: Int,
    onSeekLine: (LyricApi.Line) -> Unit,
    onLongPressLine: (LyricApi.Line) -> Unit,
    onLoadLyric: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var follow by remember { mutableStateOf(true) }
    var manualTick by remember { mutableIntStateOf(0) }

    // 逐字高亮是逐帧刷新的：按屏幕刷新率从播放器外推当前位置，
    // 既不会 12fps 一跳一跳，也不会落后于音频。
    val clock = remember { mutableLongStateOf(0L) }
    LaunchedEffect(offsetMs) {
        while (true) {
            androidx.compose.runtime.withFrameNanos {
                clock.longValue = positionProvider() + offsetMs
            }
        }
    }
    val currentIndex by remember(lines) {
        androidx.compose.runtime.derivedStateOf {
            if (lines.isEmpty()) -1 else lines.indexOfLast { it.timeMs <= clock.longValue }
        }
    }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(currentIndex, follow) {
        if (follow && currentIndex >= 0 && currentIndex < lines.size) {
            runCatching { listState.animateScrollToItem(currentIndex) }
        }
    }

    // 停止操作 4 秒后恢复跟随
    LaunchedEffect(follow, manualTick) {
        if (!follow) {
            delay(4000)
            follow = true
        }
    }

    val scrollWatcher = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: androidx.compose.ui.geometry.Offset, source: NestedScrollSource): androidx.compose.ui.geometry.Offset {
                if (source == NestedScrollSource.UserInput) {
                    follow = false
                    manualTick += 1
                }
                return androidx.compose.ui.geometry.Offset.Zero
            }
        }
    }

    val contentAlpha by animateFloatAsState(
        targetValue = if (playing) 1f else 0.82f,
        animationSpec = tween(600),
        label = "lyricDim",
    )

    BoxWithConstraints(modifier.fillMaxSize().nestedScroll(scrollWatcher)) {
        val areaHeight = maxHeight

        if (lines.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(horizontal = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Box(
                    Modifier.size(84.dp).clip(CircleShape)
                        .background(Brush.verticalGradient(listOf(AppTheme.colors.surface3, AppTheme.colors.surface1))),
                    contentAlignment = Alignment.Center,
                ) { Text("♪", style = AppTheme.typography.pageTitle, color = AppTheme.colors.textSecondary) }
                Spacer(Modifier.height(18.dp))
                Text("暂无歌词", style = AppTheme.typography.title, color = AppTheme.colors.textPrimary)
                Spacer(Modifier.height(6.dp))
                TextButton(onClick = onLoadLyric) { Text("手动加载歌词", fontSize = 13.sp) }
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().alpha(contentAlpha),
                contentPadding = PaddingValues(
                    top = areaHeight * 0.22f,
                    bottom = areaHeight * 0.55f,
                ),
            ) {
                items(lines.size) { i ->
                    val line = lines[i]
                    val distance = if (currentIndex < 0) 3 else abs(i - currentIndex)
                    val isCurrent = i == currentIndex && currentIndex >= 0
                    val flash = i == flashIndex
                    val alpha by animateFloatAsState(
                        targetValue = when {
                            flash -> 1f
                            isCurrent -> 1f
                            distance == 1 -> 0.52f
                            distance == 2 -> 0.30f
                            else -> 0.16f
                        },
                        animationSpec = tween(260),
                        label = "lyricAlpha",
                    )
                    val fontSize = when {
                        isCurrent -> 23f
                        distance == 1 -> 17f
                        else -> 15f
                    } * style.scale

                    Column(
                        Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                onClick = { onSeekLine(line) },
                                onLongClick = { onLongPressLine(line) },
                            )
                            .padding(horizontal = 24.dp, vertical = if (isCurrent) 9.dp else 7.dp),
                        horizontalAlignment = if (style.center) Alignment.CenterHorizontally else Alignment.Start,
                    ) {
                        if (isCurrent) {
                            val nextStart = lines.getOrNull(i + 1)?.timeMs ?: (line.timeMs + 5000)
                            Box(Modifier.wrapContentWidth().alpha(alpha)) {
                                Text(
                                    text = line.text,
                                    fontSize = fontSize.sp,
                                    lineHeight = (fontSize * 1.35f).sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = AppTheme.colors.textSecondary.copy(alpha = 0.75f),
                                    textAlign = TextAlign.Start,
                                    overflow = TextOverflow.Visible,
                                )
                                var textLayout by remember(line.text, fontSize) {
                                    androidx.compose.runtime.mutableStateOf<androidx.compose.ui.text.TextLayoutResult?>(null)
                                }
                                Text(
                                    text = line.text,
                                    fontSize = fontSize.sp,
                                    lineHeight = (fontSize * 1.35f).sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = LyricSung,
                                    textAlign = TextAlign.Start,
                                    overflow = TextOverflow.Visible,
                                    onTextLayout = { textLayout = it },
                                    modifier = Modifier.drawWithContent {
                                        // 逐字进度在 draw 阶段读时钟：只重绘不重组。
                                        // 超过一行时按「视觉行」分别裁剪，否则第二行会在进度一开始就整行染色。
                                        val progress = lineProgress(line, clock.longValue, nextStart)
                                        val layout = textLayout
                                        if (layout == null || layout.lineCount <= 1) {
                                            clipRect(right = size.width * progress) {
                                                this@drawWithContent.drawContent()
                                            }
                                        } else {
                                            val total = line.text.length.coerceAtLeast(1)
                                            val consumed = (progress * total).toInt()
                                            for (i in 0 until layout.lineCount) {
                                                val start = layout.getLineStart(i)
                                                val end = layout.getLineEnd(i)
                                                if (end <= start) continue
                                                val local = ((consumed - start).toFloat() / (end - start)).coerceIn(0f, 1f)
                                                if (local <= 0f) continue
                                                val left = layout.getLineLeft(i)
                                                val width = layout.getLineRight(i) - left
                                                clipRect(
                                                    left = left,
                                                    top = layout.getLineTop(i),
                                                    right = left + width * local,
                                                    bottom = layout.getLineBottom(i),
                                                ) {
                                                    this@drawWithContent.drawContent()
                                                }
                                            }
                                        }
                                    },
                                )
                            }
                        } else {
                            Text(
                                text = line.text,
                                fontSize = fontSize.sp,
                                lineHeight = (fontSize * 1.35f).sp,
                                color = AppTheme.colors.lyricInactive,
                                textAlign = if (style.center) TextAlign.Center else TextAlign.Start,
                                overflow = TextOverflow.Visible,
                                modifier = Modifier.alpha(alpha).fillMaxWidth(),
                            )
                        }
                        if (style.showTrans && line.translation.isNotEmpty() && distance <= 2) {
                            Text(
                                line.translation,
                                fontSize = (12.5f * style.scale).sp,
                                color = AppTheme.colors.textSecondary,
                                textAlign = if (style.center) TextAlign.Center else TextAlign.Start,
                                modifier = Modifier.alpha(alpha * 0.9f).fillMaxWidth().padding(top = 2.dp),
                            )
                        }
                        if (style.showRoma && line.roma.isNotEmpty() && distance <= 2) {
                            Text(
                                line.roma,
                                fontSize = (12f * style.scale).sp,
                                color = AppTheme.colors.textTertiary,
                                textAlign = if (style.center) TextAlign.Center else TextAlign.Start,
                                modifier = Modifier.alpha(alpha * 0.85f).fillMaxWidth().padding(top = 1.dp),
                            )
                        }
                    }
                }
            }

        }

        // 回到当前
        Box(Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 8.dp)) {
            AnimatedVisibility(visible = !follow && lines.isNotEmpty()) {
                Row(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(AppTheme.colors.surface2.copy(alpha = 0.92f))
                        .combinedClickable(onClick = {
                            follow = true
                            scope.launch { runCatching { listState.animateScrollToItem(currentIndex.coerceAtLeast(0)) } }
                        }, onLongClick = {})
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("回到当前", style = AppTheme.typography.bodySmall, color = AppTheme.colors.success)
                }
            }
        }
    }
}

/**
 * 当前句的连续进度（0..1）：按字时间轴算出已唱到的「字数」（带小数），
 * 再除以总字数。裁剪层再按这个比例取宽度，得到平滑扫过的效果。
 */
private fun lineProgress(line: LyricApi.Line, t: Long, nextStart: Int): Float {
    val total = line.words.sumOf { it.text.length }
    if (total == 0) {
        val start = line.timeMs.toLong()
        val end = maxOf(nextStart.toLong(), start + 2000L)
        return ((t - start).toFloat() / (end - start)).coerceIn(0f, 1f)
    }
    var sung = 0f
    for (w in line.words) {
        val s = w.startMs.toLong()
        val e = s + w.durMs
        if (t >= e) {
            sung += w.text.length
        } else if (t > s) {
            sung += w.text.length * ((t - s).toFloat() / w.durMs.coerceAtLeast(1))
            break
        } else {
            break
        }
    }
    return (sung / total).coerceIn(0f, 1f)
}
