package com.tianxiaodudou.qqmusicdownloader.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

/** 首页模块设置变化时 +1，首页据此重载。 */
object HomeConfig {
    var version by mutableIntStateOf(0)
        private set

    fun bump() {
        version += 1
    }
}
