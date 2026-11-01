package com.tianxiaodudou.qqmusicdownloader.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.dp

/** 圆角定义层。 */
@Immutable
data class AppShapes(
    val cardSmall: RoundedCornerShape,
    val cardMedium: RoundedCornerShape,
    val cardLarge: RoundedCornerShape,
    val sheet: RoundedCornerShape,
    val dialog: RoundedCornerShape,
    val button: RoundedCornerShape,
    val iconBlock: RoundedCornerShape,
    val pill: RoundedCornerShape,
    val chip: RoundedCornerShape,
    val tag: RoundedCornerShape,
    val input: RoundedCornerShape,
    val cover: RoundedCornerShape,
    val circle: RoundedCornerShape,
)

val DefaultShapes = AppShapes(
    cardSmall = RoundedCornerShape(10.dp),
    cardMedium = RoundedCornerShape(14.dp),
    cardLarge = RoundedCornerShape(16.dp),
    sheet = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
    dialog = RoundedCornerShape(18.dp),
    button = RoundedCornerShape(12.dp),
    iconBlock = RoundedCornerShape(9.dp),
    pill = RoundedCornerShape(50),
    chip = RoundedCornerShape(50),
    tag = RoundedCornerShape(4.dp),
    input = RoundedCornerShape(12.dp),
    cover = RoundedCornerShape(8.dp),
    circle = RoundedCornerShape(50),
)
