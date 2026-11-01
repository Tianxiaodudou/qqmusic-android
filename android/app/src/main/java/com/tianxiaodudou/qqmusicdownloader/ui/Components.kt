package com.tianxiaodudou.qqmusicdownloader.ui

import com.tianxiaodudou.qqmusicdownloader.ui.AppSpinner

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


/** 胶囊筛选片（搜索类型、分类切换）。 */
@Composable
fun AppChip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = AppTheme.colors
    val shapes = AppTheme.shapes
    val dimens = AppTheme.dimens
    Box(
        modifier
            .clip(shapes.chip)
            .background(if (selected) colors.brandSoft else colors.surface3)
            .clickable(onClick = onClick)
            .padding(horizontal = dimens.spacing14, vertical = dimens.spacing6),
    ) {
        Text(
            text,
            style = AppTheme.typography.bodySmall,
            color = if (selected) colors.selectedText else colors.textSecondary,
        )
    }
}

/** 分割线。 */
@Composable
fun AppDivider(
    modifier: Modifier = Modifier,
    color: androidx.compose.ui.graphics.Color? = null,
    thickness: androidx.compose.ui.unit.Dp = AppTheme.dimens.divider,
) {
    Box(modifier.fillMaxWidth().height(thickness).background(color ?: AppTheme.colors.line))
}

/** 统一的加载态：转圈 + 可选说明文字。 */
@Composable
fun LoadingBox(modifier: Modifier = Modifier, text: String = "") {
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            androidx.compose.material3.CircularProgressIndicator(
                modifier = Modifier.size(AppTheme.dimens.iconLg),
                strokeWidth = AppTheme.dimens.stroke2,
                color = AppTheme.colors.brand,
            )
            if (text.isNotEmpty()) {
                Spacer(Modifier.height(AppTheme.dimens.spacingSm))
                Text(text, style = AppTheme.typography.caption, color = AppTheme.colors.textSecondary)
            }
        }
    }
}

/** 统一空状态：说明文字 + 可选操作。 */
@Composable
fun EmptyState(text: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text, style = AppTheme.typography.body, color = AppTheme.colors.textSecondary)
            if (action != null) {
                Spacer(Modifier.height(AppTheme.dimens.spacingSm))
                action()
            }
        }
    }
}


/** 主按钮（与 Material Button 同签名，页面只换函数名）。 */
@Composable
fun AppPrimaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    androidx.compose.material3.Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        shape = AppTheme.shapes.button,
        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
            containerColor = AppTheme.colors.brand,
            contentColor = AppTheme.colors.textOnBrand,
            disabledContainerColor = AppTheme.colors.surface3,
            disabledContentColor = AppTheme.colors.textDisabled,
        ),
        content = content,
    )
}

/** 次按钮（描边）。 */
@Composable
fun AppSecondaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    androidx.compose.material3.OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        shape = AppTheme.shapes.button,
        border = AppTheme.strokes.normal,
        colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
            contentColor = AppTheme.colors.textPrimary,
            disabledContentColor = AppTheme.colors.textDisabled,
        ),
        content = content,
    )
}

/** 文字按钮。 */
@Composable
fun AppTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentPadding: androidx.compose.foundation.layout.PaddingValues = androidx.compose.foundation.layout.PaddingValues(
        horizontal = AppTheme.dimens.spacing10,
        vertical = AppTheme.dimens.spacingXs,
    ),
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    androidx.compose.material3.TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        contentPadding = contentPadding,
        content = content,
    )
}

/** 图标按钮：固定 44dp 热区。 */
@Composable
fun AppIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    androidx.compose.material3.IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.size(AppTheme.dimens.touchTarget),
        content = content,
    )
}

/** 显式带文字的快捷重载（内容只是纯文本时更省事）。 */
@Composable
fun AppPrimaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) =
    AppPrimaryButton(onClick = onClick, modifier = modifier, enabled = enabled) {
        Text(text, style = AppTheme.typography.button)
    }

@Composable
fun AppTextButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) =
    AppTextButton(onClick = onClick, modifier = modifier, enabled = enabled) {
        Text(text, style = AppTheme.typography.button)
    }

/** 开关。 */
@Composable
fun AppSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    androidx.compose.material3.Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        colors = androidx.compose.material3.SwitchDefaults.colors(
            checkedThumbColor = AppTheme.colors.switchThumb,
            checkedTrackColor = AppTheme.colors.switchTrack,
        ),
    )
}

/** 进度条（线性）。 */
@Composable
fun AppLinearProgress(progress: Float, modifier: Modifier = Modifier) {
    androidx.compose.material3.LinearProgressIndicator(
        progress = { progress.coerceIn(0f, 1f) },
        modifier = modifier,
        color = AppTheme.colors.progress,
        trackColor = AppTheme.colors.progressTrack,
    )
}

/** 输入框。 */
@Composable
fun AppInput(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    label: String = "",
    singleLine: Boolean = true,
) {
    androidx.compose.material3.OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        singleLine = singleLine,
        shape = AppTheme.shapes.input,
        textStyle = AppTheme.typography.body,
        label = if (label.isEmpty()) null else {
            { Text(label, style = AppTheme.typography.caption, color = AppTheme.colors.textSecondary) }
        },
        placeholder = if (placeholder.isEmpty()) null else {
            { Text(placeholder, style = AppTheme.typography.body, color = AppTheme.colors.textSecondary) }
        },
    )
}

/** 圆形加载指示器（小尺寸内联用）。 */
@Composable
fun AppSpinner(
    modifier: Modifier = Modifier,
    strokeWidth: androidx.compose.ui.unit.Dp = AppTheme.dimens.stroke2,
    color: androidx.compose.ui.graphics.Color? = null,
) {
    androidx.compose.material3.CircularProgressIndicator(
        modifier = modifier,
        strokeWidth = strokeWidth,
        color = color ?: AppTheme.colors.brand,
    )
}

/** 底部弹层容器：统一圆角、拖柄与背景色。 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun AppSheet(onDismiss: () -> Unit, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = AppTheme.colors.surface1,
        shape = AppTheme.shapes.sheet,
        dragHandle = { androidx.compose.material3.BottomSheetDefaults.DragHandle(color = AppTheme.colors.lineStrong) },
        content = content,
    )
}

