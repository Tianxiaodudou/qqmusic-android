package com.tianxiaodudou.qqmusicdownloader.ui

import androidx.compose.foundation.background
import com.tianxiaodudou.qqmusicdownloader.ui.theme.AppTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.tianxiaodudou.qqmusicdownloader.net.Extras
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun SocialScreen() {
    var tab by remember { mutableIntStateOf(0) }
    var list by remember { mutableStateOf<List<Extras.RelUser>?>(null) }
    LaunchedEffect(tab) {
        list = null
        list = withContext(Dispatchers.IO) { runCatching { Extras.relations(tab) }.getOrDefault(emptyList()) }
    }
    DetailScaffold("关注与粉丝", onBack = { Nav.pop() }) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                listOf("关注歌手", "关注用户", "粉丝", "好友").forEachIndexed { i, t ->
                    TextButton(onClick = { tab = i }) {
                        Text(
                            t,
                            fontSize = 12.sp,
                            color = if (tab == i) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            val data = list
            if (data == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else if (data.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("这里还没有人", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(data) { u ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(40.dp).clip(CircleShape).background(AppTheme.colors.coverPlaceholder)) {
                                if (u.avatar.startsWith("http")) {
                                    AsyncImage(model = u.avatar, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                }
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(u.name, fontSize = 14.sp)
                                if (u.desc.isNotEmpty()) {
                                    Text(u.desc, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
