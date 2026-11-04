package com.tianxiaodudou.qqmusicdownloader.player

import com.tianxiaodudou.qqmusicdownloader.net.QqClient
import com.tianxiaodudou.qqmusicdownloader.net.Diag
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

        // 用「持续吞吐」而不是「首个包延迟」来挑节点：母带这种 1MB/s 级的流，延迟低但带宽小的节点最坑
        val candidates = bases.take(4).map { it to urlOf(it, raw) }
        val results = ArrayList<Triple<String, String, Long>>()
        for ((base, url) in candidates) {
            val kbps = throughput(url)
            if (kbps > 0) results.add(Triple(base, url, kbps))
        }
        results.forEach { scores[hostOf(it.first)] = -it.third }
        val best = results.maxByOrNull { it.third }
        Diag.log(
            "cdn",
            "pick " + (best?.first?.let { hostOf(it) } ?: "fallback") +
                " " + (best?.third ?: 0) + "KB/s, candidates=" +
                results.joinToString(",") { hostOf(it.first) + "=" + it.third + "KB/s" },
        )
        return best?.second ?: candidates.first().second
    }

    private fun normalize(sips: List<String>): List<String> =
        sips.filter { it.isNotEmpty() }
            .map { it.replaceFirst("http://", "https://").trimEnd('/') + "/" }
            .distinct()

    /** 拉 256KB 测持续吞吐（KB/s），失败返回 -1。 */
    private fun throughput(url: String): Long {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", QqClient.userAgent())
            .header("Range", "bytes=0-262143")
            .build()
        val start = System.nanoTime()
        return runCatching {
            QqClient.http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use -1L
                var total = 0L
                resp.body?.byteStream()?.use { input ->
                    val buf = ByteArray(32 * 1024)
                    var idle = 0
                    while (total < 256 * 1024) {
                        val n = input.read(buf)
                        if (n <= 0) { idle++; if (idle > 2) break; continue }
                        total += n
                    }
                }
                val ms = (System.nanoTime() - start) / 1_000_000
                if (total <= 0 || ms <= 0) -1L else total * 1000 / ms
            }
        }.getOrDefault(-1L)
    }

    /** 旧的延迟探测，保留给其它调用。 */
    @Suppress("unused")
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
