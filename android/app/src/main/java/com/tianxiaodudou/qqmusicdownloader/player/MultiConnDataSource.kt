package com.tianxiaodudou.qqmusicdownloader.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.TransferListener
import com.tianxiaodudou.qqmusicdownloader.net.QqClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicLong

/**
 * 多连接分段下载 + 本地文件喂给播放器。
 *
 * ExoPlayer 默认是单连接拉流，母带这种 200MB 的文件单连接跑不满带宽就会一直缓冲。
 * 这里在 DataSource 层把文件切成 N 段并行下载到本地缓存文件，播放器读到哪，
 * 就等哪一段下载到那里再从本地文件读字节——对外仍然是一个普通 DataSource。
 */
class MultiConnDataSource(private val context: Context) : DataSource {

    companion object {
        /** 并发连接数：再高对家用网络收益递减，还容易被 CDN 限速 */
        private const val SEGMENTS = 6
        /** 小于这个大小没必要多连接 */
        private const val MIN_SIZE = 6L * 1024 * 1024
        private const val READ_WAIT_MS = 120L
        private const val MAX_WAIT_MS = 90_000L
    }

    private var fallback: DefaultHttpDataSource? = null
    private var url: String = ""
    private var length = C.LENGTH_UNSET.toLong()
    private var position = 0L
    private var file: File? = null
    private lateinit var raf: RandomAccessFile

    private val segStart = LongArray(SEGMENTS)
    private val segLen = LongArray(SEGMENTS)
    private val segDone = Array(SEGMENTS) { AtomicLong(0) }
    private val lock = Object()
    private var failure: IOException? = null
    private var threads: List<Thread> = emptyList()

    override fun addTransferListener(transferListener: TransferListener) {
        fallback?.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        url = dataSpec.uri.toString()
        position = dataSpec.position
        val size = probeSize(url)
        // 小文件 / 探测失败 / 非 Range：退回普通单连接，保证一定能播
        if (size <= MIN_SIZE) {
            return openFallback(dataSpec)
        }
        return runCatching { openParallel(size) }.getOrElse { openFallback(dataSpec) }
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
        val key = Integer.toHexString(url.hashCode()) + "_" + size
        val dir = File(context.cacheDir, "parallel").apply { mkdirs() }
        prune(dir)
        val target = File(dir, key + ".bin")
        file = target
        raf = RandomAccessFile(target, "rw")
        if (target.length() != size) raf.setLength(size)
        val done = File(dir, key + ".done")
        if (done.exists() && target.length() == size) {
            // 整首已经下完：直接本地读，不再发请求（切回这首歌是秒开的）
            for (i in 0 until SEGMENTS) segDone[i].set(segLen[i])
            length = size
            return size
        }
        val part = size / SEGMENTS
        for (i in 0 until SEGMENTS) {
            segStart[i] = i * part
            segLen[i] = if (i == SEGMENTS - 1) size - segStart[i] else part
        }
        threads = (0 until SEGMENTS).map { i -> startSegment(i) }
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
        runCatching {
            QqClient.http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    synchronized(lock) { failure = IOException("HTTP " + resp.code) }
                    return@use
                }
                val body = resp.body ?: return@use
                body.byteStream().use { input ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        synchronized(raf) {
                            raf.seek(start + segDone[i].get())
                            raf.write(buf, 0, n)
                        }
                        segDone[i].addAndGet(n.toLong())
                        synchronized(lock) { lock.notifyAll() }
                    }
                }
            }
        }.onFailure {
            synchronized(lock) {
                if (failure == null && segDone[i].get() < segLen[i]) failure = IOException(it)
                lock.notifyAll()
            }
        }
        synchronized(lock) {
            if (segDone[i].get() >= segLen[i]) {
                val allDone = (0 until SEGMENTS).all { segDone[it].get() >= segLen[it] }
                if (allDone) runCatching { File(file!!.parentFile, file!!.name.removeSuffix(".bin") + ".done").createNewFile() }
            }
            lock.notifyAll()
        }
    }.apply {
        isDaemon = true
        name = "audio-seg"
        start()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        fallback?.let { return it.read(buffer, offset, length) }
        if (position >= this.length) return C.RESULT_END_OF_INPUT
        waitAvailable(position)
        val n = synchronized(raf) {
            raf.seek(position)
            raf.read(buffer, offset, minOf(length.toLong(), this.length - position).toInt())
        }
        if (n > 0) position += n
        return if (n <= 0) C.RESULT_END_OF_INPUT else n
    }

    /** 等本地文件里 position 处已经下载好。 */
    private fun waitAvailable(position: Long) {
        val startedAt = System.currentTimeMillis()
        while (true) {
            failure?.let { throw it }
            var idx = -1
            for (i in 0 until SEGMENTS) {
                if (position >= segStart[i] && position < segStart[i] + segLen[i]) { idx = i; break }
            }
            if (idx < 0) return
            val ready = segStart[idx] + segDone[idx].get()
            if (ready > position) return
            if (System.currentTimeMillis() - startedAt > MAX_WAIT_MS) {
                throw IOException("分段下载超时")
            }
            synchronized(lock) { runCatching { lock.wait(READ_WAIT_MS) } }
        }
    }

    override fun getUri(): Uri? = if (fallback != null) fallback?.uri else Uri.parse(url)

    override fun close() {
        fallback?.close()
        fallback = null
        threads.forEach { runCatching { it.interrupt() } }
        threads = emptyList()
        runCatching { raf.close() }
    }

    /** 只留最近 6 首的分段缓存，避免把手机存储吃满。 */
    private fun prune(dir: File) {
        runCatching {
            val files = dir.listFiles()?.filter { it.name.endsWith(".bin") } ?: return
            if (files.size <= 6) return
            files.sortedByDescending { it.lastModified() }.drop(6).forEach { old ->
                runCatching { old.delete() }
                runCatching { File(dir, old.name.removeSuffix(".bin") + ".done").delete() }
            }
        }
    }

    private fun probeSize(url: String): Long {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", QqClient.userAgent())
            .header("Range", "bytes=0-0")
            .build()
        return runCatching {
            QqClient.http.newCall(req).execute().use { resp ->
                if (resp.code != 206) return 0L
                resp.header("Content-Range")?.substringAfter('/')?.trim()?.toLongOrNull() ?: 0L
            }
        }.getOrDefault(0L)
    }

    class Factory(private val context: Context) : DataSource.Factory {
        override fun createDataSource(): DataSource = MultiConnDataSource(context)
    }
}
