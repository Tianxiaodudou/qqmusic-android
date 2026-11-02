package com.tianxiaodudou.qqmusicdownloader.player

import android.content.Context
import android.net.Uri
import com.tianxiaodudou.qqmusicdownloader.net.Extras
import com.tianxiaodudou.qqmusicdownloader.net.MusicApi
import com.tianxiaodudou.qqmusicdownloader.net.Settings
import java.util.concurrent.ConcurrentHashMap

/**
 * 懒解析：MediaItem 的 uri 写成 qqmusic://track/<mid>，真正播放时（ResolvingDataSource）再换成直链。
 * 这样整个队列可以一次性交给 ExoPlayer 变成真·播放列表，系统（通知/锁屏/蓝牙）才有原生的上一首/下一首。
 */
object TrackResolver {

    private const val TTL = 8 * 60_000L
    private var appContext: Context? = null
    private val cache = ConcurrentHashMap<String, Pair<String, Long>>()
    private val qualities = ConcurrentHashMap<String, String>()

    val SCHEME = "qqmusic"

    /** 解析出某一首的实际音质时回调，用于刷新播放页顶栏的音质徽章。 */
    var onResolved: ((mid: String, quality: String) -> Unit)? = null

    fun attach(ctx: Context) { appContext = ctx.applicationContext }

    fun uriOf(mid: String): Uri = Uri.parse(SCHEME + "://track/" + mid)

    fun midOf(uri: Uri): String = uri.pathSegments.lastOrNull().orEmpty()

    fun qualityOf(mid: String): String = qualities[mid].orEmpty()

    /** 解析直链（带音质上限、替换音源兜底、8 分钟缓存）。失败返回 null。 */
    fun resolve(mid: String): String? {
        val ctx = appContext ?: return null
        cache[mid]?.let { if (System.currentTimeMillis() - it.second < TTL) return it.first }
        val song = TrackCatalog.get(mid) ?: return null
        val wanted = Settings.quality(ctx)
        val chain = Settings.Quality.entries
            .filter { it.ordinal <= wanted.ordinal }
            .sortedByDescending { it.ordinal }
        val codes = chain.map { it.code }.filter { it.isNotEmpty() }.distinct()
        val ref = runCatching { MusicApi.streamBatchEx(song, codes) }
            .getOrDefault(MusicApi.StreamRef(emptyMap(), emptyMap(), emptyList()))
        val batch = ref.urls
        for (q in chain) {
            val url = batch[q.code]
            if (!url.isNullOrEmpty()) {
                // 官方客户端会从 sip 里择优；我们这里测速后选最快的节点
                val picked = runCatching {
                    CdnPicker.pick(ref.sips, ref.raws[q.code].orEmpty(), url)
                }.getOrDefault(url)
                cache[mid] = picked to System.currentTimeMillis()
                val text = when (q.code) {
                    "M500" -> "标准"; "M800" -> "高品质"; "F000" -> "无损"
                    "O801" -> "Hi-Res"; "AI00" -> "母带"; "D004" -> "杜比"; else -> ""
                }
                qualities[mid] = text
                onResolved?.invoke(mid, text)
                return picked
            }
        }
        // 版权受限：换可播版本
        val replaced = runCatching { Extras.replacement(song) }.getOrNull() ?: return null
        for (q in chain) {
            val url = runCatching { MusicApi.streamUrlOf(replaced, q.code) }.getOrNull()
            if (!url.isNullOrEmpty()) {
                cache[mid] = url to System.currentTimeMillis()
                qualities[mid] = "替换音源"
                onResolved?.invoke(mid, "替换音源")
                return url
            }
        }
        return null
    }

    /** 提前解析下一首，切歌时不用等。 */
    fun prefetch(mid: String) {
        if (cache[mid]?.let { System.currentTimeMillis() - it.second < TTL } == true) return
        runCatching { resolve(mid) }
    }

    fun clear() {
        cache.clear()
        qualities.clear()
    }
}
