package com.tianxiaodudou.qqmusicdownloader.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 尺寸定义层：间距阶梯、控件尺寸、热区。 */
@Immutable
data class AppDimens(
    val spacingXs: Dp,
    val spacingSm: Dp,
    val spacingMd: Dp,
    val spacingLg: Dp,
    val spacingXl: Dp,
    val pagePadding: Dp,
    val rowVertical: Dp,
    val divider: Dp,
    val buttonHeight: Dp,
    val iconSm: Dp,
    val iconMd: Dp,
    val iconLg: Dp,
    val touchTarget: Dp,
    val coverSm: Dp,
    val coverMd: Dp,
    val coverLg: Dp,
    // 组件级：入口卡与图标块
    val tileHeight: Dp,
    val tileIconBlock: Dp,
    val tileIcon: Dp,
    val spacing2: Dp,
    val spacing5: Dp,
    val spacing6: Dp,
    val spacing14: Dp,
    val coverRadius: Dp,
)

val DefaultDimens = AppDimens(
    spacingXs = 4.dp,
    spacingSm = 8.dp,
    spacingMd = 12.dp,
    spacingLg = 16.dp,
    spacingXl = 24.dp,
    pagePadding = 16.dp,
    rowVertical = 10.dp,
    divider = 1.dp,
    buttonHeight = 44.dp,
    iconSm = 16.dp,
    iconMd = 20.dp,
    iconLg = 22.dp,
    touchTarget = 44.dp,
    coverSm = 42.dp,
    coverMd = 48.dp,
    coverLg = 64.dp,
    tileHeight = 76.dp,
    tileIconBlock = 30.dp,
    tileIcon = 17.dp,
    spacing2 = 2.dp,
    spacing5 = 5.dp,
    spacing6 = 6.dp,
    spacing14 = 14.dp,
    coverRadius = 8.dp,
)
