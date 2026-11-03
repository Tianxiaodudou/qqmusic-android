package com.tianxiaodudou.qqmusicdownloader.net

import android.content.Context
import java.io.File

/**
 * 落盘诊断日志：release 包被 R8 去掉了 Log.*，线上问题没法看 logcat。
 * 这里把关键步骤写到应用外部私有目录，可用 adb pull 取回：
 *   /sdcard/Android/data/<包名>/files/diag.log
 */
object Diag {

    @Volatile
    private var file: File? = null
    private val lock = Any()

    fun init(context: Context) {
        if (file != null) return
        file = runCatching {
            val dir = context.getExternalFilesDir(null) ?: context.filesDir
            File(dir, "diag.log")
        }.getOrNull()
    }

    fun log(tag: String, message: String) {
        val f = file ?: return
        synchronized(lock) {
            runCatching {
                if (f.exists() && f.length() > 256 * 1024) f.delete()
                val ts = java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS", java.util.Locale.US)
                    .format(java.util.Date())
                f.appendText(ts + " [" + tag + "] " + message + "\n")
            }
        }
    }
}
