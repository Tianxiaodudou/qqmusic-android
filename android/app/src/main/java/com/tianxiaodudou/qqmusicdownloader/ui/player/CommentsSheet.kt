package com.tianxiaodudou.qqmusicdownloader.ui.player

import com.tianxiaodudou.qqmusicdownloader.ui.AppInput

import com.tianxiaodudou.qqmusicdownloader.ui.AppSpinner

import com.tianxiaodudou.qqmusicdownloader.ui.AppTextButton

import androidx.compose.foundation.background
import com.tianxiaodudou.qqmusicdownloader.ui.theme.AppTheme
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.tianxiaodudou.qqmusicdownloader.net.Extras
import com.tianxiaodudou.qqmusicdownloader.net.MusicApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun CommentsSheet(song: MusicApi.Song, onClose: () -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    var list by remember { mutableStateOf<List<Extras.Comment>?>(null) }
    var text by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    LaunchedEffect(song.id, tab) {
        list = null
        list = withContext(Dispatchers.IO) {
            runCatching { Extras.comments(song.id, tab == 0) }.getOrDefault(emptyList())
        }
    }

    Column(Modifier.fillMaxWidth().fillMaxHeight(0.75f).padding(bottom = AppTheme.dimens.spacingSm)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = AppTheme.dimens.spacing18, vertical = AppTheme.dimens.spacingXs), verticalAlignment = Alignment.CenterVertically) {
            Text("评论 · " + song.name, fontSize = AppTheme.typography.title.fontSize, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            AppTextButton(onClick = { tab = 0 }) {
                Text("热门", fontSize = AppTheme.typography.bodySmall.fontSize, color = if (tab == 0) AppTheme.colors.brand else AppTheme.colors.textSecondary)
            }
            AppTextButton(onClick = { tab = 1 }) {
                Text("最新", fontSize = AppTheme.typography.bodySmall.fontSize, color = if (tab == 1) AppTheme.colors.brand else AppTheme.colors.textSecondary)
            }
        }
        if (toast.isNotEmpty()) {
            Text(toast, style = AppTheme.typography.caption, color = AppTheme.colors.textSecondary, modifier = Modifier.padding(horizontal = AppTheme.dimens.spacing18))
        }
        val data = list
        if (data == null) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) { AppSpinner() }
        } else if (data.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text("还没有评论", style = AppTheme.typography.bodySmall, color = AppTheme.colors.textSecondary)
            }
        } else {
            LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                items(data) { c ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = AppTheme.dimens.spacing18, vertical = AppTheme.dimens.spacingSm)) {
                        Box(Modifier.size(AppTheme.dimens.coverXs).clip(CircleShape).background(AppTheme.colors.coverPlaceholder)) {
                            if (c.avatar.startsWith("http")) {
                                AsyncImage(
                                    model = c.avatar,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                        Spacer(Modifier.width(AppTheme.dimens.spacing10))
                        Column(Modifier.weight(1f)) {
                            Text(c.nick, style = AppTheme.typography.bodySmall, color = AppTheme.colors.textSecondary)
                            Text(c.content, style = AppTheme.typography.bodySmall, color = AppTheme.colors.textPrimary)
                            Row {
                                Text(
                                    if (c.likes > 0) "赞 " + c.likes else "",
                                    fontSize = AppTheme.typography.badge.fontSize,
                                    color = AppTheme.colors.textTertiary,
                                )
                            }
                        }
                    }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = AppTheme.dimens.spacing14, vertical = AppTheme.dimens.spacingXs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppInput(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                placeholder = "说点什么…",
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(AppTheme.dimens.spacingSm))
            AppTextButton(onClick = {
                val content = text.trim()
                if (content.isEmpty() || sending) return@AppTextButton
                sending = true
                scope.launch {
                    val ok = withContext(Dispatchers.IO) { runCatching { Extras.addComment(song.id, content) }.getOrDefault(false) }
                    sending = false
                    if (ok) {
                        text = ""
                        tab = 1
                        list = withContext(Dispatchers.IO) { runCatching { Extras.comments(song.id, false) }.getOrDefault(emptyList()) }
                        toast = "已发送"
                    } else {
                        toast = "发送失败，可能需要重新登录"
                    }
                }
            }) { Text(if (sending) "发送中…" else "发送", fontSize = AppTheme.typography.bodySmall.fontSize) }
        }
    }
}
