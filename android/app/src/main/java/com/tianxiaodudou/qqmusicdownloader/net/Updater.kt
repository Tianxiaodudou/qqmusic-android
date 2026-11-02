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
            if (!isNewer(rel.version, currentVersion)) return@withContext null
            // API 走通但正文为空（偶发）时也去捞一次更新日志
            return@withContext if (rel.notes.isNotBlank()) rel else rel.copy(notes = notesOf(rel.version))
        }
        val ver = runCatching { versionFromRedirect() }.getOrNull()
            ?: throw java.io.IOException("连不上更新服务器（api.github.com / github.com 都不通）")
        if (!isNewer(ver, currentVersion)) return@withContext null
        // 只拿到版本号时（API 被墙/限流）也不能丢更新日志：从仓库的 CHANGELOG / atom 里取
        Release(ver, assetUrl(ver), notesOf(ver), REPO + "/releases/tag/v" + ver)
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

    /**
     * 取某个版本的更新日志：先读仓库里的 CHANGELOG.md（和 Release 正文同源），
     * 再退到 releases.atom（GitHub 不封 atom，API 被限流时仍可用）。
     */
    private fun notesOf(version: String): String {
        runCatching { notesFromChangelog(version) }.getOrNull()?.takeIf { it.isNotBlank() }?.let { return it }
        return runCatching { notesFromAtom(version) }.getOrDefault("")
    }

    private fun notesFromChangelog(version: String): String {
        // 国内直连 raw.githubusercontent 经常不通，先用 jsDelivr CDN 取同一份 CHANGELOG
        val v = version.removePrefix("v").trim()
        val sources = listOf(
            // jsDelivr 按 tag 取：不会被 @main 的缓存拖成旧内容
            "https://cdn.jsdelivr.net/gh/Tianxiaodudou/qqmusic-android@v" + v + "/CHANGELOG.md",
            "https://cdn.jsdelivr.net/gh/Tianxiaodudou/qqmusic-android@main/CHANGELOG.md",
            "https://raw.githubusercontent.com/Tianxiaodudou/qqmusic-android/v" + v + "/CHANGELOG.md",
            "https://raw.githubusercontent.com/Tianxiaodudou/qqmusic-android/main/CHANGELOG.md",
        )
        for (url in sources) {
            val md = runCatching { fetchText(url) }.getOrDefault("")
            if (md.isNotEmpty()) {
                sectionOf(md, version).takeIf { it.isNotBlank() }?.let { return it }
            }
        }
        return ""
    }

    private fun fetchText(url: String): String {
        val req = Request.Builder().url(url).header("User-Agent", QqClient.userAgent()).build()
        QqClient.http.newCall(req).execute().use { resp ->
            return if (resp.isSuccessful) resp.body?.string().orEmpty() else ""
        }
    }

    private fun sectionOf(text: String, version: String): String {
        if (text.isEmpty()) return ""
        val v = version.removePrefix("v").trim()
        val lines = text.lines()
        // 精确匹配「## v2.5.9」，避免 2.5.9 命中 2.5.90 之类
        val head = Regex("""^##\s*v?""" + Regex.escape(v) + """(\s|$)""")
        val start = lines.indexOfFirst { head.containsMatchIn(it.trim()) }
        if (start < 0) return ""
        val out = StringBuilder()
        for (i in start + 1 until lines.size) {
            if (lines[i].startsWith("## ")) break
            out.appendLine(lines[i])
        }
        return out.toString().trim()
    }

    private fun notesFromAtom(version: String): String {
        val req = Request.Builder()
            .url(REPO + "/releases.atom")
            .header("User-Agent", QqClient.userAgent())
            .build()
        QqClient.http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return ""
            val xml = resp.body?.string().orEmpty()
            val v = version.removePrefix("v").trim()
            if (!xml.contains("v" + v)) return ""
            val entry = Regex("<entry>(.*?)</entry>", RegexOption.DOT_MATCHES_ALL)
                .findAll(xml)
                .map { it.groupValues[1] }
                .firstOrNull { it.contains("v" + v) } ?: return ""
            val content = Regex("<content[^>]*>(.*?)</content>", RegexOption.DOT_MATCHES_ALL)
                .find(entry)?.groupValues?.get(1) ?: return ""
            return content
                .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
                .replace("&quot;", "\"").replace("<br />", "\n").replace("<br/>", "\n")
                .replace(Regex("<[^>]+>"), "")
                .trim()
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
            // 网络抖动/超时/磁盘写失败都不应该让调用方的协程炸掉（那会直接导致应用崩溃）
            runCatching { downloadInner(context, release, onProgress) }
                .getOrElse { t ->
                    android.util.Log.w("Updater", "download failed: " + t.message)
                    null
                }
        }

    private fun downloadInner(context: Context, release: Release, onProgress: (Int) -> Unit): File? {
            val dir = File(context.cacheDir, "update").apply { mkdirs() }
            val out = File(dir, "qqmusic-player-" + release.version + ".apk")
            if (out.exists()) out.delete()
            val req = Request.Builder()
                .url(release.apkUrl)
                .header("User-Agent", QqClient.userAgent())
                .build()
            var ok = false
            QqClient.http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body ?: return null
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
            return if (!ok || out.length() < 1024) {
                runCatching { out.delete() }
                null
            } else out
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
