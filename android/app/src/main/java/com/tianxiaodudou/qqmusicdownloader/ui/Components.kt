package com.tianxiaodudou.qqmusicdownloader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal val CardBg = Color(0xFF171B22)
internal val CardBorder = Color(0xFF232A35)
internal val DimText = Color(0xFF8C96A6)
internal val BrandGreen = Color(0xFF1FC365)

/** 首页入口小方块：图标 + 文字。 */
@Composable
fun EntryTile(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .height(76.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(CardBg)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier.size(30.dp).clip(RoundedCornerShape(9.dp)).background(Color(0x221FC365)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = BrandGreen, modifier = Modifier.size(17.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(label, fontSize = 11.sp, color = Color(0xFFD7DEE8), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** 方形封面：空地址显示 ♪ 占位。 */
@Composable
fun SquareCover(url: String, dim: androidx.compose.ui.unit.Dp = 48.dp, radius: androidx.compose.ui.unit.Dp = 8.dp) {
    Box(
        Modifier.size(dim).clip(RoundedCornerShape(radius)).background(Color(0xFF222833)),
        contentAlignment = Alignment.Center,
    ) {
        Text("♪", fontSize = 15.sp, color = DimText)
        if (url.isNotEmpty()) {
            coil3.compose.AsyncImage(
                model = url,
                contentDescription = null,
                modifier = Modifier.size(dim).clip(RoundedCornerShape(radius)),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            )
        }
    }
}

/** 设置/内容分组卡片。 */
@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(CardBg),
    ) {
        if (title.isNotEmpty()) {
            Text(
                title,
                fontSize = 11.sp,
                color = DimText,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(start = 16.dp, top = 14.dp, end = 16.dp, bottom = 2.dp),
            )
        }
        content()
    }
}

/** 小节标题（首页用）。 */
@Composable
fun BlockTitle(text: String) {
    Text(
        text,
        fontSize = 15.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp),
    )
}
