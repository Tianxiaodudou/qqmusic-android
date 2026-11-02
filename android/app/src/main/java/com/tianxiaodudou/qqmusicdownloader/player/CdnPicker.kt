package com.tianxiaodudou.qqmusicdownloader.player

import com.tianxiaodudou.qqmusicdownloader.net.QqClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap

/**
 * CDN 节点择优：官方客户端从 vkey 响应里的 sip 列表测速后挑最快的节点，
 * 我们之前写死 dl.stream.qqmusic.qq.com，跨网时只有 200KB/s 左右。
 *
 * 做法：对前几个 sip 并发拉 32KB 探测，按耗时排序并记住名次；之后同一首直接走最快节点。
 */
object CdnPicker {

    private val scores = ConcurrentHashMap<String, Long>()

    fun hostOf(base: String): String = base.substringAfter("://").substringBefore('/')

    /** base 形如 https://xxx.qqmusic.qq.com/ ，raw 是 purl（含 ?vkey=...）。 */
    fun urlOf(base: String, raw: String): String =
        if (raw.startsWith("http")) raw else base.trimEnd('/') + "/" + raw.trimStart('/')

    /**
     * 在候选节点里挑最快的，返回可用直链。探测失败时退回默认节点。
     * 阻塞调用，应该在 IO 线程执行。
     */
    fun pick(sips: List<String>, raw: String, fallback: String): String {
        if (raw.isEmpty()) return fallback
        val bases = normalize(sips)
        if (bases.isEmpty()) return fallback
        // 已经有测速结果：直接按名次取
        bases.minByOrNull { scores[hostOf(it)] ?: Long.MAX_VALUE }
            ?.let { best -> if (scores.containsKey(hostOf(best))) return urlOf(best, raw) }

        val candidates = bases.take(3).map { it to urlOf(it, raw) }
        val results = candidates.map { (base, url) ->
            Triple(base, url, probe(url))
        }.filter { it.third > 0 }
        results.forEach { scores[hostOf(it.first)] = it.third }
        return results.minByOrNull { it.third }?.second ?: candidates.first().second
    }

    private fun normalize(sips: List<String>): List<String> =
        sips.filter { it.isNotEmpty() }
            .map { it.replaceFirst("http://", "https://").trimEnd('/') + "/" }
            .distinct()

    /** 拉 32KB 计耗时（毫秒），失败返回 -1。 */
    private fun probe(url: String): Long {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", QqClient.userAgent())
            .header("Range", "bytes=0-32767")
            .build()
        val start = System.nanoTime()
        return runCatching {
            QqClient.http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use -1L
                resp.body?.byteStream()?.use { input ->
                    val buf = ByteArray(8 * 1024)
                    var total = 0
                    while (total < 32 * 1024) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        total += n
                    }
                }
                (System.nanoTime() - start) / 1_000_000
            }
        }.getOrDefault(-1L)
    }
}
