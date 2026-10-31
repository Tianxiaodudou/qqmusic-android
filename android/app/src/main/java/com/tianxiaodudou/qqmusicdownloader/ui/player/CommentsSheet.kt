package com.tianxiaodudou.qqmusicdownloader.ui.player

import androidx.compose.foundation.background
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

    Column(Modifier.fillMaxWidth().fillMaxHeight(0.75f).padding(bottom = 8.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("评论 · " + song.name, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            TextButton(onClick = { tab = 0 }) {
                Text("热门", fontSize = 12.sp, color = if (tab == 0) Color(0xFF1FC365) else Color(0xFF8C96A6))
            }
            TextButton(onClick = { tab = 1 }) {
                Text("最新", fontSize = 12.sp, color = if (tab == 1) Color(0xFF1FC365) else Color(0xFF8C96A6))
            }
        }
        if (toast.isNotEmpty()) {
            Text(toast, fontSize = 11.sp, color = Color(0xFF8C96A6), modifier = Modifier.padding(horizontal = 18.dp))
        }
        val data = list
        if (data == null) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else if (data.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text("还没有评论", fontSize = 13.sp, color = Color(0xFF8C96A6))
            }
        } else {
            LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                items(data) { c ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp)) {
                        Box(Modifier.size(34.dp).clip(CircleShape).background(Color(0xFF232A35))) {
                            if (c.avatar.startsWith("http")) {
                                AsyncImage(
                                    model = c.avatar,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(c.nick, fontSize = 12.sp, color = Color(0xFF9AA3B2))
                            Text(c.content, fontSize = 13.sp, color = Color(0xFFE9EDF4))
                            Row {
                                Text(
                                    if (c.likes > 0) "赞 " + c.likes else "",
                                    fontSize = 10.sp,
                                    color = Color(0xFF6F7A8A),
                                )
                            }
                        }
                    }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                placeholder = { Text("说点什么…", fontSize = 12.sp) },
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = {
                val content = text.trim()
                if (content.isEmpty() || sending) return@TextButton
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
            }) { Text(if (sending) "发送中…" else "发送", fontSize = 13.sp) }
        }
    }
}
