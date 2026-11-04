package com.tianxiaodudou.qqmusicdownloader.player

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * root 能力探测与命令执行。
 *
 * 不同 root 管理器的授权方式不一样：
 *  - Magisk / KernelSU / SuperSU：执行第一个 su 命令时会弹授权框（有的管理器默认“自动拒绝”，需手动允许）
 *  - 部分管理器只在特定路径放 su（/system/xbin/su、/sbin/su、ksud、magisk）
 * 所以这里把常见路径都试一遍，并给足 20 秒等用户在弹窗里点“允许”。
 */
object RootShell {

    private val candidates = listOf(
        "su",
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/system/sbin/su",
        "/vendor/bin/su",
        "/debug_ramdisk/su",
        "/data/adb/magisk/magisk",
        "/data/adb/ksu/bin/ksud",
    )

    @Volatile
    var available: Boolean = false
        private set

    @Volatile
    var manager: String = ""

    /** 阻塞调用（最长 20 秒），应在 IO 线程执行；首次调用会触发管理器授权弹窗。 */
    fun request(context: android.content.Context): Boolean {
        for (path in candidates) {
            val ok = runCatching {
                val pb = ProcessBuilder(path, "-c", "id")
                    .redirectErrorStream(true)
                val proc = pb.start()
                val done = proc.waitFor(20, TimeUnit.SECONDS)
                if (!done) {
                    proc.destroyForcibly()
                    return@runCatching false
                }
                val out = proc.inputStream.bufferedReader().readText()
                out.contains("uid=0")
            }.getOrDefault(false)
            if (ok) {
                available = true
                manager = when {
                    path.contains("ksud") -> "KernelSU"
                    path.contains("magisk") -> "Magisk"
                    else -> "su"
                }
                com.tianxiaodudou.qqmusicdownloader.net.Diag.log("root", "granted via " + path + " (" + manager + ")")
                return true
            }
        }
        available = false
        manager = ""
        com.tianxiaodudou.qqmusicdownloader.net.Diag.log("root", "not granted")
        return false
    }

    /** 以 root 执行命令并把标准输出写进文件（用于 onUnderrun 时抓系统层日志）。 */
    fun capture(command: String, out: File, timeoutSeconds: Long = 20): Boolean {
        if (!available) return false
        return runCatching {
            val pb = ProcessBuilder("su", "-c", command).redirectErrorStream(true)
            val proc = pb.start()
            val text = proc.inputStream.bufferedReader().readText()
            proc.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            out.appendText(text.take(200_000))
            true
        }.getOrDefault(false)
    }
}
