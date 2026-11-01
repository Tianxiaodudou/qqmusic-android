package com.tianxiaodudou.qqmusicdownloader.player

import android.content.Context
import com.tianxiaodudou.qqmusicdownloader.net.QqClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile

/**
 * 多连接音频缓存：把整首歌按 HTTP Range 切成若干段并行下载到本地，
 * 播这首时直接用本地文件（不再走网络流）。
 *
 * ExoPlayer 自己只能单连接拉流，高码率（母带/Hi-Res）单连接跑不满带宽时就会一卡一卡；
 * 这里用 4 个连接分片下载，下完切本地播放，等于把「多线程缓冲」做在播放器外面。
 * 只对下一首/上一首预热，文件过大（>80MB）或服务器不支持 Range 时自动放弃。
 */
object AudioFetch {

    private const val CONNECTIONS = 4
    private const val MAX_BYTES = 80L * 1024 * 1024

    private fun dir(ctx: Context): File = File(ctx.cacheDir, "audio_cache").apply { mkdirs() }
    private fun audioFile(ctx: Context, mid: String): File = File(dir(ctx), mid + ".audio")
    private fun metaFile(ctx: Context, mid: String): File = File(dir(ctx), mid + ".meta")

    /** 本地已下好的整首音频：文件、音质说明、音质名称、音质代码。 */
    data class Cached(val file: File, val label: String, val quality: String, val code: String)

    /** 已经完整下好的本地文件，没有则 null。 */
    fun cached(ctx: Context, mid: String): Cached? {
        val f = audioFile(ctx, mid)
        if (!f.exists() || f.length() <= 0) return null
        val meta = runCatching { JSONObject(metaFile(ctx, mid).readText()) }.getOrNull()
        return Cached(
            file = f,
            label = meta?.optString("label").orEmpty(),
            quality = meta?.optString("quality").orEmpty(),
            code = meta?.optString("code").orEmpty(),
        )
    }

    fun sizeBytes(ctx: Context, mid: String): Long = audioFile(ctx, mid).let { if (it.exists()) it.length() else 0 }

    fun drop(ctx: Context, mid: String) {
        runCatching { audioFile(ctx, mid).delete() }
        runCatching { metaFile(ctx, mid).delete() }
    }

    private val running: MutableSet<String> =
        java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    fun isRunning(mid: String): Boolean = running.contains(mid)

    /** 后台把整首并行下载到本地；成功返回文件。已存在则直接返回。 */
    suspend fun ensure(
        ctx: Context,
        mid: String,
        url: String,
        label: String,
        quality: String,
        code: String = "",
    ): File? = withContext(Dispatchers.IO) {
        cached(ctx, mid)?.let { return@withContext it.file }
        if (!running.add(mid)) return@withContext null
        try {
            download(ctx, mid, url, label, quality, code)
        } finally {
            running.remove(mid)
        }
    }

    private suspend fun download(
        ctx: Context,
        mid: String,
        url: String,
        label: String,
        quality: String,
        code: String,
    ): File? = withContext(Dispatchers.IO) {
        val tmp = File(dir(ctx), mid + ".part")
        runCatching { tmp.delete() }
        val total = probeSize(url) ?: return@withContext null
        if (total <= 0 || total > MAX_BYTES) return@withContext null
        val raf = runCatching { RandomAccessFile(tmp, "rw") }.getOrNull() ?: return@withContext null
        val ok = runCatching {
            raf.setLength(total)
            // 太小的文件没必要拆
            val parts = if (total < 4L * 1024 * 1024) 1 else CONNECTIONS
            val chunk = total / parts
            coroutineScope {
                (0 until parts).map { i ->
                    async(Dispatchers.IO) {
                        val start = i * chunk
                        val end = if (i == parts - 1) total - 1 else start + chunk - 1
                        fetchRange(url, start, end, raf)
                    }
                }.awaitAll().all { it }
            }
        }.getOrDefault(false)
        raf.close()
        if (!ok) {
            runCatching { tmp.delete() }
            return@withContext null
        }
        val out = audioFile(ctx, mid)
        runCatching { out.delete() }
        if (!tmp.renameTo(out)) {
            runCatching { tmp.delete() }
            return@withContext null
        }
        runCatching {
            metaFile(ctx, mid).writeText(
                JSONObject().put("label", label).put("quality", quality)
                    .put("code", code).put("size", total).toString(),
            )
        }
        prune(ctx)
        out
    }

    /** 只留最近用的 20 首 / 1.2GB，避免把手机缓存撑爆。 */
    private fun prune(ctx: Context, maxFiles: Int = 20, maxBytes: Long = 1200L * 1024 * 1024) {
        runCatching {
            val files = dir(ctx).listFiles()?.filter { it.name.endsWith(".audio") } ?: return
            if (files.size <= maxFiles && files.sumOf { it.length() } <= maxBytes) return
            var total = files.sumOf { it.length() }
            files.sortedByDescending { it.lastModified() }.forEachIndexed { i, f ->
                if (i >= maxFiles || total > maxBytes) {
                    total -= f.length()
                    runCatching { f.delete() }
                    runCatching { File(dir(ctx), f.name.removeSuffix(".audio") + ".meta").delete() }
                }
            }
        }
    }

    /** 取总长度：用 Range: bytes=0-0 只看 Content-Range，不消耗整文件。 */
    private fun probeSize(url: String): Long? {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", QqClient.userAgent())
            .header("Range", "bytes=0-0")
            .build()
        return runCatching {
            QqClient.http.newCall(req).execute().use { resp ->
                if (resp.code != 206) return@use null
                val cr = resp.header("Content-Range") ?: return@use null
                cr.substringAfter('/').trim().toLongOrNull()
            }
        }.getOrNull()
    }

    private fun fetchRange(url: String, start: Long, end: Long, raf: RandomAccessFile): Boolean {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", QqClient.userAgent())
            .header("Range", "bytes=" + start + "-" + end)
            .build()
        return runCatching {
            QqClient.http.newCall(req).execute().use { resp ->
                if (resp.code != 206 && resp.code != 200) return@use false
                val body = resp.body ?: return@use false
                body.byteStream().use { input ->
                    val buf = ByteArray(64 * 1024)
                    var written = start
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        synchronized(raf) {
                            raf.seek(written)
                            raf.write(buf, 0, n)
                        }
                        written += n
                    }
                }
                true
            }
        }.getOrDefault(false)
    }
}
