package com.tianxiaodudou.qqmusicdownloader.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.dp

/** 描边定义层：粗细 + 三种语义颜色。 */
@Immutable
data class AppStrokes(
    val thin: BorderStroke,
    val normal: BorderStroke,
    val focused: BorderStroke,
    val disabled: BorderStroke,
)

fun appStrokes(colors: AppColors): AppStrokes = AppStrokes(
    thin = BorderStroke(0.5.dp, colors.line),
    normal = BorderStroke(1.dp, colors.line),
    focused = BorderStroke(1.dp, colors.lineFocus),
    disabled = BorderStroke(1.dp, colors.lineDisabled),
)
