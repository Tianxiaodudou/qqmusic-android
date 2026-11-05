package com.tianxiaodudou.qqmusicdownloader.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.TransferListener
import com.tianxiaodudou.qqmusicdownloader.net.Diag
import com.tianxiaodudou.qqmusicdownloader.net.QqClient
import com.tianxiaodudou.qqmusicdownloader.net.Settings
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicLong

/**
 * 多连接分段下载（实验）。
 *
 * 单连接被 CDN/运营商限速时，用 N 条 Range 连接并行拉同一首歌的不同区间，
 * 播放器按需从本地临时文件读取。N 由用户在设置里选（4–32，默认 6）。
 * 任何一步失败（不支持 Range、HTTP 错误、分段线程异常、起播超时）都立刻退回普通单连接。
 */
class MultiConnDataSource(private val context: Context) : DataSource {

    private companion object {
        const val MIN_SIZE = 4L * 1024 * 1024
        /** 每段至少 512KB，避免文件小的时候开一堆连接 */
        const val MIN_SEG_BYTES = 512L * 1024
        const val WAIT_MS = 60L
        /** 熔断：多连接卡死后 5 分钟内不再尝试（避免反复卡） */
        @Volatile
        var disabledUntil = 0L
    }

    private var fallback: DefaultHttpDataSource? = null
    private var url = ""
    private var length = C.LENGTH_UNSET.toLong()
    private var position = 0L
    private var raf: RandomAccessFile? = null
    private var file: File? = null

    private var segments = 0
    private var segStart = LongArray(0)
    private var segLen = LongArray(0)
    private var done = Array(0) { AtomicLong(0) }
    private var bytesRead = Array(0) { AtomicLong(0) }
    private var threads: List<Thread> = emptyList()
    @Volatile
    private var closing = false
    private val lock = Object()

    override fun addTransferListener(transferListener: TransferListener) {
        fallback?.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        url = dataSpec.uri.toString()
        position = dataSpec.position
        val enabled = Settings.multiConn(context) && System.currentTimeMillis() > disabledUntil
        val want = Settings.multiConnSegments(context)
        val size = if (enabled) probeSize(url) else 0L
        if (!enabled || size < MIN_SIZE) return openFallback(dataSpec)
        return runCatching { openParallel(size, want) }.getOrElse {
            AudioStats.mcFallbacks += 1
            Diag.log("mc", "parallel open failed: " + it.message)
            stopThreads()
            openFallback(dataSpec)
        }
    }

    private fun openFallback(dataSpec: DataSpec): Long {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(QqClient.userAgent())
            .setAllowCrossProtocolRedirects(true)
            .createDataSource()
        fallback = http
        return http.open(dataSpec)
    }

    private fun openParallel(size: Long, want: Int): Long {
        length = size
        // 按文件大小收敛连接数：每段不少于 512KB
        segments = minOf(want.coerceIn(4, 8), (size / MIN_SEG_BYTES).toInt().coerceAtLeast(1))
        segStart = LongArray(segments)
        segLen = LongArray(segments)
        done = Array(segments) { AtomicLong(0) }
        bytesRead = Array(segments) { AtomicLong(0) }

        val dir = File(context.cacheDir, "mc").apply { mkdirs() }
        // 清理：保留最近 3 个，另外任何超过 10 分钟的残留一律删掉
        val now = System.currentTimeMillis()
        dir.listFiles()?.forEach { f2 ->
            if (now - f2.lastModified() > 10 * 60_000L) runCatching { f2.delete() }
        }
        dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(3)?.forEach { runCatching { it.delete() } }
        // 每个实例一个独立文件：之前按 URL 哈希命名，同一首歌被两个 DataSource 同时打开时
        // 会往同一个文件里交错写，读出来的字节就是错的 —— 这正是“破音/断续”的来源。
        val f = File(dir, Integer.toHexString(url.hashCode()) + "-" + java.util.UUID.randomUUID().toString().take(8) + ".bin")
        file = f
        AudioStats.mcOpened += 1
        AudioStats.mcSegments = segments
        raf = RandomAccessFile(f, "rw").apply { setLength(size) }

        val per = size / segments
        for (i in 0 until segments) {
            segStart[i] = per * i
            segLen[i] = if (i == segments - 1) size - segStart[i] else per
        }
        threads = (0 until segments).map { i -> startSegment(i) }

        // 所有分段都要真的开始下，否则播放器会卡在 waitFor 等一个永不到来的段
        val deadline = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < deadline) {
            if ((0 until segments).all { done[it].get() > 0 }) break
            synchronized(lock) { runCatching { lock.wait(WAIT_MS) } }
        }
        val stalled = (0 until segments).filter { done[it].get() == 0L }
        if (stalled.isNotEmpty()) throw IOException("分段未就绪: " + stalled.joinToString(","))
        Diag.log("mc", "open size=" + size / 1024 / 1024 + "MB segs=" + segments)
        return size
    }

    private fun startSegment(i: Int): Thread = Thread {
        val start = segStart[i]
        val end = start + segLen[i] - 1
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", QqClient.userAgent())
            .header("Range", "bytes=" + start + "-" + end)
            .build()
        try {
            QqClient.http.newCall(req).execute().use { resp ->
                // 必须真的 206：有的节点无视 Range 返回 200 + 整包，按段写会把字节写到错误偏移
                if (resp.code != 206) throw IOException("HTTP " + resp.code + "（不支持 Range）")
                val body = resp.body ?: throw IOException("empty body")
                body.byteStream().use { input ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        val r = raf ?: break
                        synchronized(r) {
                            r.seek(start + done[i].get())
                            r.write(buf, 0, n)
                        }
                        done[i].addAndGet(n.toLong())
                        bytesRead[i].addAndGet(n.toLong())
                        synchronized(lock) { lock.notifyAll() }
                    }
                }
            }
        } catch (t: Throwable) {
            if (!closing && t !is InterruptedException) {
                Diag.log("mc", "seg" + i + " error: " + t.message + " done=" + done[i].get())
            }
        }
    }.apply {
        isDaemon = true
        name = "mc-$i"
        start()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        fallback?.let { return it.read(buffer, offset, length) }
        if (position >= this.length) return C.RESULT_END_OF_INPUT
        waitFor(position)
        val r = raf ?: return C.RESULT_END_OF_INPUT
        val n = synchronized(r) {
            r.seek(position)
            r.read(buffer, offset, minOf(length.toLong(), this.length - position).toInt())
        }
        if (n > 0) position += n
        return if (n <= 0) C.RESULT_END_OF_INPUT else n
    }

    private fun waitFor(pos: Long) {
        val started = System.currentTimeMillis()
        while (true) {
            var idx = -1
            for (i in 0 until segments) {
                if (pos >= segStart[i] && pos < segStart[i] + segLen[i]) { idx = i; break }
            }
            if (idx < 0) return
            if (segStart[idx] + done[idx].get() > pos) return
            if (System.currentTimeMillis() - started > 10_000) {
                // 熔断 5 分钟：避免每次切歌都在这里等 30 秒，直接退回单连接
                disabledUntil = System.currentTimeMillis() + 5 * 60_000L
                AudioStats.mcFallbacks += 1
                Diag.log("mc", "read timeout pos=" + pos + " seg=" + idx + " → 熔断 5 分钟")
                throw IOException("分段下载超时 pos=" + pos)
            }
            synchronized(lock) { runCatching { lock.wait(WAIT_MS) } }
        }
    }

    override fun getUri(): Uri? = fallback?.uri ?: Uri.parse(url)

    override fun close() {
        closing = true
        fallback?.close()
        fallback = null
        val total = bytesRead.sumOf { it.get() }
        if (total > 0) {
            Diag.log(
                "mc",
                "closed segs=" + segments + " bytes=" + total / 1024 + "KB per-seg=" +
                    bytesRead.joinToString(",") { (it.get() / 1024).toString() } + "KB",
            )
        }
        stopThreads()
        runCatching { raf?.close() }
        raf = null
        runCatching { file?.delete() }
    }

    private fun stopThreads() {
        threads.forEach { runCatching { it.interrupt() } }
        threads = emptyList()
    }

    private fun probeSize(url: String): Long = runCatching {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", QqClient.userAgent())
            .header("Range", "bytes=0-1")
            .build()
        QqClient.http.newCall(req).execute().use { resp ->
            if (resp.code != 206) return 0L
            resp.header("Content-Range")?.substringAfter('/')?.toLongOrNull() ?: 0L
        }
    }.getOrDefault(0L)

    class Factory(private val context: Context) : DataSource.Factory {
        override fun createDataSource(): DataSource = MultiConnDataSource(context)
    }
}
