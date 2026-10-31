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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
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

private val LyricBright = Color(0xFFF5F7FA)
private val LyricSung = Color(0xFF6FE7A5)
private val LyricDim = Color(0xFF9AA3B2)

/**
 * 滚动歌词区：小封面 + 逐字歌词 + 手动滚动让位 + 点击跳转 + 长按分享。
 * 自动跟随时当前句固定在区域约 1/3 处，手指一碰就暂停跟随，4 秒后自动恢复。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LyricsPane(
    lines: List<LyricApi.Line>,
    currentIndex: Int,
    lyricTimeMs: Long,
    style: Settings.LyricStyle,
    coverUrl: String,
    playing: Boolean,
    flashIndex: Int,
    onSeekLine: (LyricApi.Line) -> Unit,
    onLongPressLine: (LyricApi.Line) -> Unit,
    onLoadLyric: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var follow by remember { mutableStateOf(true) }
    var manualTick by remember { mutableIntStateOf(0) }
    var tapFade by remember { mutableStateOf(false) }
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

        // 小封面：视觉锚点，手动滚动/点歌词时淡出
        val coverAlpha by animateFloatAsState(
            targetValue = if (follow && !tapFade) 0.62f else 0f,
            animationSpec = tween(420),
            label = "coverAlpha",
        )
        LaunchedEffect(tapFade) {
            if (tapFade) {
                delay(2600)
                tapFade = false
            }
        }
        val angle = remember { Animatable(0f) }
        LaunchedEffect(playing, coverUrl) {
            if (playing) {
                angle.animateTo(
                    targetValue = angle.value + 360f,
                    animationSpec = infiniteRepeatable(tween(24000, easing = LinearEasing)),
                )
            }
        }

        if (lines.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(horizontal = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Box(
                    Modifier.size(84.dp).clip(CircleShape)
                        .background(Brush.verticalGradient(listOf(Color(0xFF2A3140), Color(0xFF171B22)))),
                    contentAlignment = Alignment.Center,
                ) { Text("♪", fontSize = 30.sp, color = Color(0xFF8B95A7)) }
                Spacer(Modifier.height(18.dp))
                Text("暂无歌词", fontSize = 15.sp, color = Color(0xFFB9C2D0))
                Spacer(Modifier.height(6.dp))
                TextButton(onClick = onLoadLyric) { Text("手动加载歌词", fontSize = 13.sp) }
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().alpha(contentAlpha),
                contentPadding = PaddingValues(
                    top = areaHeight * 0.30f,
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
                                onClick = {
                                    tapFade = true
                                    onSeekLine(line)
                                },
                                onLongClick = { onLongPressLine(line) },
                            )
                            .padding(horizontal = 24.dp, vertical = if (isCurrent) 9.dp else 7.dp),
                        horizontalAlignment = if (style.center) Alignment.CenterHorizontally else Alignment.Start,
                    ) {
                        Text(
                            text = if (isCurrent) karaoke(line, lyricTimeMs) else AnnotatedString(line.text),
                            fontSize = fontSize.sp,
                            lineHeight = (fontSize * 1.35f).sp,
                            fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (isCurrent) LyricBright else Color(0xFFC9D1DE),
                            textAlign = if (style.center) TextAlign.Center else TextAlign.Start,
                            overflow = TextOverflow.Visible,
                            modifier = Modifier.alpha(alpha).fillMaxWidth(),
                        )
                        if (style.showTrans && line.translation.isNotEmpty() && distance <= 2) {
                            Text(
                                line.translation,
                                fontSize = (12.5f * style.scale).sp,
                                color = Color(0xFF8C96A6),
                                textAlign = if (style.center) TextAlign.Center else TextAlign.Start,
                                modifier = Modifier.alpha(alpha * 0.9f).fillMaxWidth().padding(top = 2.dp),
                            )
                        }
                        if (style.showRoma && line.roma.isNotEmpty() && distance <= 2) {
                            Text(
                                line.roma,
                                fontSize = (12f * style.scale).sp,
                                color = Color(0xFF76808F),
                                textAlign = if (style.center) TextAlign.Center else TextAlign.Start,
                                modifier = Modifier.alpha(alpha * 0.85f).fillMaxWidth().padding(top = 1.dp),
                            )
                        }
                    }
                }
            }

            // 小封面叠在歌词上方居中，不占列表空间
            if (coverUrl.isNotEmpty()) {
                Box(
                    Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 6.dp)
                        .size(84.dp)
                        .alpha(coverAlpha)
                        .graphicsLayer { rotationZ = angle.value },
                ) {
                    AsyncImage(
                        model = coverUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().clip(CircleShape),
                    )
                }
            }
        }

        // 回到当前
        Box(Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 8.dp)) {
            AnimatedVisibility(visible = !follow && lines.isNotEmpty()) {
                Row(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(Color(0xCC1B2029))
                        .combinedClickable(onClick = {
                            follow = true
                            scope.launch { runCatching { listState.animateScrollToItem(currentIndex.coerceAtLeast(0)) } }
                        }, onLongClick = {})
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("回到当前", fontSize = 12.sp, color = Color(0xFF9CE8BE))
                }
            }
        }
    }
}

/** 当前句按字时间轴染色：已唱亮、未唱暗。 */
private fun karaoke(line: LyricApi.Line, t: Long): AnnotatedString = buildAnnotatedString {
    if (line.words.isEmpty()) {
        append(line.text)
        return@buildAnnotatedString
    }
    for (w in line.words) {
        val start = w.startMs.toLong()
        val end = start + w.durMs
        val n = w.text.length
        val filled = when {
            t >= end -> n
            t <= start -> 0
            else -> ((t - start).toFloat() / w.durMs.coerceAtLeast(1).toFloat() * n).toInt().coerceIn(0, n)
        }
        withStyle(SpanStyle(color = LyricSung)) { append(w.text.take(filled)) }
        withStyle(SpanStyle(color = Color(0xFF8E98A8))) { append(w.text.drop(filled)) }
    }
}
