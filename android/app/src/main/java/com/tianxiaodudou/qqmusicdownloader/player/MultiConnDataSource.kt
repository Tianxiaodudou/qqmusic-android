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
 * 多连接分段下载（实验）。单连接被 CDN/运营商限速时，用 4 条 Range 连接并行拉，
 * 播放器按需从本地临时文件读；任何一步失败都立刻退回普通单连接，保证一定能播。
 */
class MultiConnDataSource(private val context: Context) : DataSource {

    private companion object {
        const val SEGMENTS = 6
        const val MIN_SIZE = 4L * 1024 * 1024
        const val WAIT_MS = 60L
    }

    private var fallback: DefaultHttpDataSource? = null
    private var url = ""
    private var length = C.LENGTH_UNSET.toLong()
    private var position = 0L
    private var raf: RandomAccessFile? = null
    private var file: File? = null

    private val segStart = LongArray(SEGMENTS)
    private val segLen = LongArray(SEGMENTS)
    private val done = Array(SEGMENTS) { AtomicLong(0) }
    private val bytesRead = Array(SEGMENTS) { AtomicLong(0) }
    @Volatile
    private var failed = false
    private var threads: List<Thread> = emptyList()
    private val lock = Object()

    override fun addTransferListener(transferListener: TransferListener) {
        fallback?.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        url = dataSpec.uri.toString()
        position = dataSpec.position
        val enabled = Settings.multiConn(context)
        val size = if (enabled) probeSize(url) else 0L
        if (!enabled || size < MIN_SIZE) {
            return openFallback(dataSpec)
        }
        return runCatching { openParallel(size) }.getOrElse {
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

    private fun openParallel(size: Long): Long {
        length = size
        val dir = File(context.cacheDir, "mc").apply { mkdirs() }
        // 只留最近 3 个临时文件
        dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(3)?.forEach { it.delete() }
        val f = File(dir, Integer.toHexString(url.hashCode()) + ".bin")
        file = f
        raf = RandomAccessFile(f, "rw").apply { setLength(size) }
        for (i in 0 until SEGMENTS) {
            segStart[i] = (size / SEGMENTS) * i
            segLen[i] = if (i == SEGMENTS - 1) size - segStart[i] else size / SEGMENTS
            done[i].set(0)
        }
        threads = (0 until SEGMENTS).map { i -> startSegment(i) }
        // 等第一段有点数据再交给播放器，避免一上来就卡在 read()
        val deadline = System.currentTimeMillis() + 4000
        while (System.currentTimeMillis() < deadline) {
            if (failed) throw IOException("分段连接失败")
            if (done[0].get() >= 64 * 1024) break
            synchronized(lock) { runCatching { lock.wait(WAIT_MS) } }
        }
        if (done[0].get() == 0L) throw IOException("第一段没有数据")
        Diag.log("mc", "open size=" + size / 1024 / 1024 + "MB segs=" + SEGMENTS + " first64k=" + (done[0].get() >= 64 * 1024))
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
                if (!resp.isSuccessful) throw IOException("HTTP " + resp.code)
                val body = resp.body ?: throw IOException("empty body")
                body.byteStream().use { input ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        val raf = this.raf ?: break
                        synchronized(raf) {
                            raf.seek(start + done[i].get())
                            raf.write(buf, 0, n)
                        }
                        done[i].addAndGet(n.toLong())
                        bytesRead[i].addAndGet(n.toLong())
                        synchronized(lock) { lock.notifyAll() }
                    }
                }
            }
        } catch (t: Throwable) {
            if (t !is InterruptedException) {
                failed = true
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
        val raf = this.raf ?: return C.RESULT_END_OF_INPUT
        val n = synchronized(raf) {
            raf.seek(position)
            raf.read(buffer, offset, minOf(length.toLong(), this.length - position).toInt())
        }
        if (n > 0) position += n
        return if (n <= 0) C.RESULT_END_OF_INPUT else n
    }

    private fun waitFor(pos: Long) {
        val started = System.currentTimeMillis()
        while (true) {
            var idx = -1
            for (i in 0 until SEGMENTS) {
                if (pos >= segStart[i] && pos < segStart[i] + segLen[i]) { idx = i; break }
            }
            if (idx < 0) return
            if (segStart[idx] + done[idx].get() > pos) return
            if (System.currentTimeMillis() - started > 30_000) {
                throw IOException("分段下载超时 pos=" + pos)
            }
            synchronized(lock) { runCatching { lock.wait(WAIT_MS) } }
        }
    }

    override fun getUri(): Uri? = fallback?.uri ?: Uri.parse(url)

    override fun close() {
        fallback?.close()
        fallback = null
        val total = bytesRead.sumOf { it.get() }
        if (total > 0) {
            Diag.log("mc", "closed bytes=" + total / 1024 + "KB per-seg=" + bytesRead.joinToString(",") { (it.get() / 1024).toString() } + "KB")
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
