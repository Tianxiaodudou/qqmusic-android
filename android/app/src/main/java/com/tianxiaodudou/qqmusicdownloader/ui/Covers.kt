package com.tianxiaodudou.qqmusicdownloader.ui

import android.content.Context
import coil3.SingletonImageLoader
import coil3.request.ImageRequest

/** 封面预加载：进入列表/切歌时把接下来要看的图先丢进 Coil 缓存。 */
object Covers {

    fun prefetch(context: Context, urls: List<String>, limit: Int = 40) {
        val loader = runCatching { SingletonImageLoader.get(context) }.getOrNull() ?: return
        urls.asSequence().filter { it.isNotEmpty() }.distinct().take(limit).forEach {
            runCatching { loader.enqueue(ImageRequest.Builder(context).data(it).build()) }
        }
    }
}
