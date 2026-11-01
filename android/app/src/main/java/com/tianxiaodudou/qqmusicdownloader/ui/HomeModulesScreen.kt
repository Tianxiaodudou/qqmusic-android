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

@Composable
fun HomeModulesScreen() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val version = HomeConfig.version
    DetailScaffold("首页模块", onBack = { Nav.pop() }) {
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                Text(
                    "关掉的模块不会出现在首页入口墙，也不会发请求。每个模块固定每页 " +
                        Settings.PAGE_SIZE + " 条，往下滑会继续加载。",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            items(Settings.HOME_MODULES) { m ->
                val enabled = Settings.homeEnabled(context, m.key)
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
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
            }
        }
    }
}
