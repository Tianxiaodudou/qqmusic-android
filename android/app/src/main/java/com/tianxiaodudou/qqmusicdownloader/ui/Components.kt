package com.tianxiaodudou.qqmusicdownloader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.tianxiaodudou.qqmusicdownloader.ui.theme.AppTheme

/**
 * 基础组件层：颜色、圆角、字号、间距全部来自 AppTheme，页面只调组件。
 * 这三个 val 仅用于尚未改造的页面做兜底，新代码请用 AppTheme。
 */
internal val CardBg
    @Composable get() = AppTheme.colors.surface1
internal val CardBorder
    @Composable get() = AppTheme.colors.line
internal val DimText
    @Composable get() = AppTheme.colors.textSecondary
internal val BrandGreen
    @Composable get() = AppTheme.colors.brand

/** 首页入口小方块：图标 + 文字。 */
@Composable
fun EntryTile(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    val shapes = AppTheme.shapes
    val dimens = AppTheme.dimens
    val type = AppTheme.typography
    Column(
        modifier
            .height(dimens.tileHeight)
            .clip(shapes.cardMedium)
            .background(colors.surface1)
            .clickable(onClick = onClick)
            .padding(vertical = dimens.spacingMd),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier.size(dimens.tileIconBlock).clip(shapes.iconBlock).background(colors.brandBorder),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = colors.brand, modifier = Modifier.size(dimens.tileIcon))
        }
        Spacer(Modifier.height(dimens.spacingXs))
        Text(
            label,
            style = type.caption,
            color = colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 方形封面：空地址显示 ♪ 占位。 */
@Composable
fun SquareCover(url: String, dim: androidx.compose.ui.unit.Dp? = null, radius: androidx.compose.ui.unit.Dp? = null) {
    val colors = AppTheme.colors
    val shapes = AppTheme.shapes
    val dimens = AppTheme.dimens
    val size = dim ?: dimens.coverMd
    val corner = radius ?: dimens.coverRadius
    Box(
        Modifier.size(size).clip(androidx.compose.foundation.shape.RoundedCornerShape(corner))
            .background(colors.coverPlaceholder),
        contentAlignment = Alignment.Center,
    ) {
        Text("♪", style = AppTheme.typography.title, color = colors.textSecondary)
        if (url.isNotEmpty()) {
            coil3.compose.AsyncImage(
                model = url,
                contentDescription = null,
                modifier = Modifier.size(size).clip(androidx.compose.foundation.shape.RoundedCornerShape(corner)),
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
    val colors = AppTheme.colors
    val shapes = AppTheme.shapes
    val dimens = AppTheme.dimens
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = dimens.pagePadding, vertical = dimens.spacing6)
            .clip(shapes.cardLarge)
            .background(colors.surface1),
    ) {
        if (title.isNotEmpty()) {
            Text(
                title,
                style = AppTheme.typography.caption,
                color = colors.textSecondary,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(
                    start = dimens.pagePadding, top = dimens.spacingLg,
                    end = dimens.pagePadding, bottom = dimens.spacing2,
                ),
            )
        }
        content()
    }
}

/** 可折叠分组卡片：标题行点击展开/收起，同一屏只留标题，避免「我的」页越滚越长。 */
@Composable
fun FoldSection(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = AppTheme.colors
    val shapes = AppTheme.shapes
    val dimens = AppTheme.dimens
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = dimens.pagePadding, vertical = dimens.spacing5)
            .clip(shapes.cardLarge)
            .background(colors.surface1),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onToggle)
                .padding(horizontal = dimens.pagePadding, vertical = dimens.spacing14),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title,
                style = AppTheme.typography.body,
                fontWeight = FontWeight.Medium,
                color = colors.textPrimary,
                modifier = Modifier.weight(1f),
            )
            Icon(
                if (expanded) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
                contentDescription = null,
                tint = colors.textSecondary,
                modifier = Modifier.size(dimens.iconMd),
            )
        }
        if (expanded) {
            content()
            Spacer(Modifier.height(dimens.spacingXs))
        }
    }
}

/** 小节标题（首页用）。 */
@Composable
fun BlockTitle(text: String) {
    Text(
        text,
        style = AppTheme.typography.title,
        fontWeight = FontWeight.SemiBold,
        color = AppTheme.colors.textPrimary,
        modifier = Modifier.padding(
            start = AppTheme.dimens.pagePadding, end = AppTheme.dimens.pagePadding,
            top = AppTheme.dimens.spacingMd, bottom = AppTheme.dimens.spacingSm,
        ),
    )
}

