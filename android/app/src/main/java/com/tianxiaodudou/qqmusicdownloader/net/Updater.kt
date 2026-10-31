package com.tianxiaodudou.qqmusicdownloader.net

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.File

/** 检查更新 + 内置下载器下载 APK + 拉起系统安装器。 */
object Updater {

    data class Release(val version: String, val apkUrl: String, val notes: String, val page: String)

    private const val API = "https://api.github.com/repos/Tianxiaodudou/qqmusic-android/releases/latest"

    private const val REPO = "https://github.com/Tianxiaodudou/qqmusic-android"

    /**
     * 有新版本才返回，已是最新返回 null；两条链路都失败时抛异常（不要误报「已是最新」）。
     * 1) GitHub API；2) 打不开 API 时退回 releases/latest 的 302 跳转解析版本号。
     */
    suspend fun latest(currentVersion: String): Release? = withContext(Dispatchers.IO) {
        val byApi = runCatching { fromApi() }
        if (byApi.isSuccess) {
            val rel = byApi.getOrNull() ?: return@withContext null
            return@withContext if (isNewer(rel.version, currentVersion)) rel else null
        }
        val ver = runCatching { versionFromRedirect() }.getOrNull()
            ?: throw java.io.IOException("连不上更新服务器（api.github.com / github.com 都不通）")
        if (!isNewer(ver, currentVersion)) return@withContext null
        Release(ver, assetUrl(ver), "", REPO + "/releases/tag/v" + ver)
    }

    private fun fromApi(): Release? {
        val req = Request.Builder()
            .url(API)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", QqClient.userAgent())
            .build()
        QqClient.http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw java.io.IOException("GitHub API " + resp.code)
            val text = resp.body?.string() ?: throw java.io.IOException("空响应")
            val j = JSONObject(text)
            val tag = j.optString("tag_name").removePrefix("v")
            if (tag.isEmpty()) return null
            val assets = j.optJSONArray("assets")
            var url = ""
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val a = assets.optJSONObject(i) ?: continue
                    if (a.optString("name").endsWith(".apk")) {
                        url = a.optString("browser_download_url")
                        break
                    }
                }
            }
            if (url.isEmpty()) url = assetUrl(tag)
            return Release(tag, url, j.optString("body"), j.optString("html_url"))
        }
    }

    /** 跟随 releases/latest 的 302，从最终地址里取 tag 版本号。 */
    private fun versionFromRedirect(): String? {
        val req = Request.Builder()
            .url(REPO + "/releases/latest")
            .header("User-Agent", QqClient.userAgent())
            .build()
        QqClient.http.newCall(req).execute().use { resp ->
            val finalUrl = resp.request.url.toString()
            val m = Regex("""/releases/tag/v?([0-9][0-9.]*)""").find(finalUrl) ?: return null
            return m.groupValues[1]
        }
    }

    /** 发布包的命名是固定的：<版本>-qqmusic-player.apk。 */
    private fun assetUrl(version: String): String =
        REPO + "/releases/download/v" + version + "/" + version + "-qqmusic-player.apk"

    fun isNewer(remote: String, local: String): Boolean {
        fun parts(v: String) = v.trim().removePrefix("v").split(".", "-").mapNotNull { it.toIntOrNull() }
        val a = parts(remote)
        val b = parts(local)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** 下载到 cache/update，返回文件；失败返回 null。进度回调在 IO 线程。 */
    suspend fun download(context: Context, release: Release, onProgress: (Int) -> Unit): File? =
        withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "update").apply { mkdirs() }
            val out = File(dir, "qqmusic-player-" + release.version + ".apk")
            if (out.exists()) out.delete()
            val req = Request.Builder()
                .url(release.apkUrl)
                .header("User-Agent", QqClient.userAgent())
                .build()
            var ok = false
            QqClient.http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val body = resp.body ?: return@withContext null
                val total = body.contentLength()
                var read = 0L
                var last = -1
                body.byteStream().use { input ->
                    out.outputStream().use { os ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            os.write(buf, 0, n)
                            read += n
                            if (total > 0) {
                                val p = (read * 100 / total).toInt()
                                if (p != last) {
                                    last = p
                                    onProgress(p)
                                }
                            }
                        }
                    }
                }
                ok = true
            }
            if (!ok || out.length() < 1024) null else out
        }

    /** 唤起系统安装器；没有「安装未知应用」权限时先带用户去授权页。 */
    fun install(context: Context, file: File): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + context.packageName))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            return false
        }
        return runCatching {
            val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }
}
