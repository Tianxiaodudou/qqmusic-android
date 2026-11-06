package com.tianxiaodudou.qqmusicdownloader.player

import android.content.Context
import android.net.Uri
import com.tianxiaodudou.qqmusicdownloader.net.Extras
import com.tianxiaodudou.qqmusicdownloader.net.Diag
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

    /** 解析失败（vkey 没给可用直链）时回调，reason 是给用户看的原因。 */
    var onFailed: ((mid: String, reason: String) -> Unit)? = null

    fun attach(ctx: Context) { appContext = ctx.applicationContext }

    /**
     * 直链 URI。带一个 q=<音质上限> 查询参数：
     * 换音质时 URI 变了，ExoPlayer 才会重新打开数据源（同 URI 的 replaceMediaItem 会被当成没变化）。
     */
    fun uriOf(mid: String, qualityTag: String = ""): Uri {
        val base = SCHEME + "://track/" + mid
        return Uri.parse(if (qualityTag.isEmpty()) base else base + "?q=" + qualityTag)
    }

    fun midOf(uri: Uri): String = uri.pathSegments.lastOrNull().orEmpty()

    fun qualityOf(mid: String): String = qualities[mid].orEmpty()

    /** 解析结果：url 非空即可播放/下载；否则 reason 是给用户看的原因。 */
    data class Resolved(val url: String? = null, val reason: String? = null, val quality: String = "")

    /** 解析直链（带音质上限、替换音源兜底、8 分钟缓存）。失败返回 null，并通过 onFailed 给出原因。 */
    fun resolve(mid: String): String? {
        val r = resolveDetailed(mid)
        if (r.url != null) {
            if (r.quality.isNotEmpty()) onResolved?.invoke(mid, r.quality)
        } else {
            onFailed?.invoke(mid, r.reason ?: "无法播放这首歌，请稍后重试")
        }
        return r.url
    }

    /** 同 [resolve]，但不回调 UI —— 下载等非播放场景直接拿直链或失败原因。 */
    fun resolveDetailed(mid: String): Resolved {
        val ctx = appContext ?: return Resolved(reason = "播放器还没准备好，请稍后重试")
        cache[mid]?.let {
            if (System.currentTimeMillis() - it.second < TTL) {
                return Resolved(url = it.first, quality = qualities[mid].orEmpty())
            }
        }
        val song = TrackCatalog.get(mid) ?: return Resolved(reason = "缺少歌曲信息，请重试")
        var wanted = Settings.quality(ctx)
        // 卸载优先 + 设备只支持 MP3 卸载时，播放压到 320k MP3（下载路径不受影响）
        if (Settings.offloadPreferMp3(ctx) && AudioStats.offloadMp3 &&
            wanted.ordinal > Settings.Quality.HIGH.ordinal
        ) {
            wanted = Settings.Quality.HIGH
        }
        val chain = Settings.Quality.entries
            .filter { it.ordinal <= wanted.ordinal }
            .sortedByDescending { it.ordinal }
        val codes = chain.map { it.code }.filter { it.isNotEmpty() }.distinct()
        val ref = runCatching { MusicApi.streamBatchEx(song, codes) }
            .getOrDefault(MusicApi.StreamRef(emptyMap(), emptyMap(), emptyList()))
        // 诊断：vkey 到底给了几个 CDN 节点（为空说明一直在用默认节点，没有择优空间）
        Diag.log(
            "cdn",
            "vkey mid=" + mid + " code=" + codes.joinToString("/") +
                " sips=" + ref.sips.size + " urls=" + ref.urls.size +
                " default=" + (ref.urls.values.firstOrNull()?.substringAfter("://")?.substringBefore('/') ?: "-"),
        )
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
                return Resolved(url = picked, quality = text)
            }
        }
        // 一个 purl 都没拿到：用 vkey 的 result 码给出准确原因（语义见 QQMusicApi）
        val results = ref.results.values
        val reason = when {
            results.contains(104003) -> "因账户权限无法播放或下载（需要会员或购买该歌曲）"
            results.contains(104013) -> "因账户权限无法播放或下载（该歌曲限制了播放设备）"
            results.contains(104004) -> "取不到播放地址，请稍后重试"
            results.isEmpty() -> "取不到播放地址，请稍后重试"
            else -> "无法播放这首歌，请稍后重试"
        }
        // 版权受限：换可播版本
        val replaced = runCatching { Extras.replacement(song) }.getOrNull() ?: return Resolved(reason = reason)
        for (q in chain) {
            val url = runCatching { MusicApi.streamUrlOf(replaced, q.code) }.getOrNull()
            if (!url.isNullOrEmpty()) {
                cache[mid] = url to System.currentTimeMillis()
                qualities[mid] = "替换音源"
                return Resolved(url = url, quality = "替换音源")
            }
        }
        return Resolved(reason = reason)
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
