package com.tianxiaodudou.qqmusicdownloader.net

import android.content.Context
import okhttp3.Request
import java.io.File

/**
 * 加密音源（mflac / mgg / mmp4）：先整首下载再按 QMC2 解密，缓存到应用私有目录。
 * 普通直链（mp3/flac/ogg）不需要走这里，播放器可以直接流式播放。
 */
object Lossless {

    /** 兼容旧调用：按当前设置取加密档位。 */
    fun playableFile(context: Context, song: MusicApi.Song): File? =
        fileFor(context, song, Settings.quality(context))

    fun fileFor(context: Context, song: MusicApi.Song, quality: Settings.Quality): File? {
        if (quality.encryptedCode.isEmpty()) return null
        val cache = File(cacheDir(context), song.mid + "-" + quality.encryptedCode + "." + quality.outputExt)
        if (cache.exists() && cache.length() > 1024) return cache

        val pair = MusicApi.losslessOf(song, quality.encryptedCode, quality.encryptedExt) ?: return null
        val key = Qmc2.deriveKey(pair.second) ?: return null

        val tmp = File(cacheDir(context), song.mid + "-" + quality.encryptedCode + ".src")
        val request = Request.Builder().url(pair.first).header("User-Agent", QqClient.userAgent()).build()
        QqClient.http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val body = resp.body ?: return null
            tmp.outputStream().use { out -> body.byteStream().copyTo(out) }
        }
        val raw = tmp.readBytes()
        tmp.delete()
        if (raw.size < 1024) return null
        val head = String(raw, 0, 4, Charsets.ISO_8859_1)
        if (head != "fLaC" && head != "OggS" && head != "ftyp") {
            Qmc2.decrypt(raw, key, 0)
        }
        cache.writeBytes(raw)
        return if (cache.length() > 1024) cache else null
    }

    private fun cacheDir(context: Context): File {
        val dir = File(context.cacheDir, "lossless")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }
}
