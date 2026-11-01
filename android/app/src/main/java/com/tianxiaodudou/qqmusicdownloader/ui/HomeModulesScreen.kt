package com.tianxiaodudou.qqmusicdownloader.ui

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tianxiaodudou.qqmusicdownloader.net.Settings

private val COUNT_OPTIONS = listOf(5, 10, 20, 30, 50)

@Composable
fun HomeModulesScreen() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val version = HomeConfig.version
    DetailScaffold("首页模块", onBack = { Nav.pop() }) {
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                Text(
                    "关掉的模块不会出现在首页入口墙，也不会发请求；条数就是点进模块后列表的条数（服务端单页有上限时客户端会翻页凑够）。",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            items(Settings.HOME_MODULES) { m ->
                val enabled = Settings.homeEnabled(context, m.key)
                var count by remember(version, m.key) { mutableIntStateOf(Settings.homeCount(context, m.key)) }
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(m.title, fontSize = 14.sp, modifier = Modifier.weight(1f))
                        Switch(
                            checked = enabled,
                            onCheckedChange = {
                                Settings.setHomeEnabled(context, m.key, it)
                                HomeConfig.bump()
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color(0xFF06230F),
                                checkedTrackColor = Color(0xFF1FC365),
                            ),
                        )
                    }
                    if (enabled) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "当前 " + count + " 条",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 4.dp),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            COUNT_OPTIONS.forEach { opt ->
                                Box(
                                    Modifier
                                        .clip(RoundedCornerShape(50))
                                        .background(if (count == opt) Color(0xFF1B2A22) else MaterialTheme.colorScheme.surfaceVariant)
                                        .clickable {
                                            count = opt
                                            Settings.setHomeCount(context, m.key, opt)
                                            HomeConfig.bump()
                                        }
                                        .padding(horizontal = 12.dp, vertical = 5.dp),
                                ) {
                                    Text(
                                        opt.toString() + " 条",
                                        fontSize = 11.sp,
                                        color = if (count == opt) Color(0xFF6FE7A5) else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
