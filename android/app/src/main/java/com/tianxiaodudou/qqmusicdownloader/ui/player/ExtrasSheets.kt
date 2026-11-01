package com.tianxiaodudou.qqmusicdownloader.ui.player

import androidx.compose.foundation.clickable
import com.tianxiaodudou.qqmusicdownloader.ui.theme.AppTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tianxiaodudou.qqmusicdownloader.net.Extras
import com.tianxiaodudou.qqmusicdownloader.net.MusicApi
import com.tianxiaodudou.qqmusicdownloader.net.PlaylistApi
import com.tianxiaodudou.qqmusicdownloader.ui.Nav
import com.tianxiaodudou.qqmusicdownloader.ui.Route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val SheetText: Color
    @Composable get() = AppTheme.colors.textPrimary
private val SheetDim: Color
    @Composable get() = AppTheme.colors.textSecondary

@Composable
private fun SheetLoading() {
    Box(Modifier.fillMaxWidth().fillMaxHeight(0.5f), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

@Composable
private fun SheetEmpty(text: String) {
    Box(Modifier.fillMaxWidth().fillMaxHeight(0.4f), contentAlignment = Alignment.Center) {
        Text(text, fontSize = 13.sp, color = SheetDim)
    }
}

@Composable
fun SimilarSongsSheet(songId: Int, onPlay: (List<MusicApi.Song>, Int) -> Unit, onClose: () -> Unit) {
    var list by remember(songId) { mutableStateOf<List<MusicApi.Song>?>(null) }
    LaunchedEffect(songId) {
        list = withContext(Dispatchers.IO) { runCatching { Extras.similarSongs(songId) }.getOrDefault(emptyList()) }
    }
    Column(Modifier.fillMaxWidth().fillMaxHeight(0.7f).padding(bottom = 10.dp)) {
        Text("相似歌曲", fontSize = 16.sp, color = SheetText, modifier = Modifier.padding(horizontal = 22.dp, vertical = 10.dp))
        val data = list
        if (data == null) SheetLoading()
        else if (data.isEmpty()) SheetEmpty("没有找到相似歌曲")
        else LazyColumn(Modifier.fillMaxSize()) {
            items(data.size) { i ->
                val s = data[i]
                Row(
                    Modifier.fillMaxWidth().clickable {
                        onClose()
                        onPlay(data, i)
                    }.padding(horizontal = 22.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(s.name, fontSize = 14.sp, color = SheetText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(s.singers, fontSize = 11.sp, color = SheetDim, maxLines = 1)
                    }
                    Text(fmtMs(s.interval * 1000L), fontSize = 10.sp, color = SheetDim)
                }
            }
        }
    }
}

@Composable
fun RelatedPlaylistsSheet(songId: Int, onClose: () -> Unit) {
    var list by remember(songId) { mutableStateOf<List<PlaylistApi.Item>?>(null) }
    LaunchedEffect(songId) {
        list = withContext(Dispatchers.IO) { runCatching { Extras.relatedPlaylists(songId) }.getOrDefault(emptyList()) }
    }
    Column(Modifier.fillMaxWidth().fillMaxHeight(0.65f).padding(bottom = 10.dp)) {
        Text("相关歌单", fontSize = 16.sp, color = SheetText, modifier = Modifier.padding(horizontal = 22.dp, vertical = 10.dp))
        val data = list
        if (data == null) SheetLoading()
        else if (data.isEmpty()) SheetEmpty("没有相关歌单")
        else LazyColumn(Modifier.fillMaxSize()) {
            items(data) { p ->
                Row(
                    Modifier.fillMaxWidth().clickable {
                        onClose()
                        Nav.push(Route.Playlist(p.id, p.name))
                    }.padding(horizontal = 22.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(p.name, fontSize = 14.sp, color = SheetText, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text(p.count.toString() + " 首", fontSize = 11.sp, color = SheetDim)
                }
            }
        }
    }
}

@Composable
fun OtherVersionsSheet(song: MusicApi.Song, onPlay: (List<MusicApi.Song>, Int) -> Unit, onClose: () -> Unit) {
    var list by remember(song.mid) { mutableStateOf<List<MusicApi.Song>?>(null) }
    LaunchedEffect(song.mid) {
        list = withContext(Dispatchers.IO) { runCatching { Extras.otherVersions(song) }.getOrDefault(emptyList()) }
    }
    Column(Modifier.fillMaxWidth().fillMaxHeight(0.6f).padding(bottom = 10.dp)) {
        Text("其他版本", fontSize = 16.sp, color = SheetText, modifier = Modifier.padding(horizontal = 22.dp, vertical = 10.dp))
        val data = list
        if (data == null) SheetLoading()
        else if (data.isEmpty()) SheetEmpty("没有其他版本")
        else LazyColumn(Modifier.fillMaxSize()) {
            items(data.size) { i ->
                val s = data[i]
                Row(
                    Modifier.fillMaxWidth().clickable {
                        onClose()
                        onPlay(listOf(s), 0)
                    }.padding(horizontal = 22.dp, vertical = 11.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(s.name, fontSize = 14.sp, color = SheetText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(s.album, fontSize = 11.sp, color = SheetDim, maxLines = 1)
                    }
                    Spacer(Modifier.width(8.dp))
                }
            }
        }
    }
}
