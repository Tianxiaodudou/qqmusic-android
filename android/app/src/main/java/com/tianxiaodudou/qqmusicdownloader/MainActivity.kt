package com.tianxiaodudou.qqmusicdownloader

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.tianxiaodudou.qqmusicdownloader.net.QqClient
import com.tianxiaodudou.qqmusicdownloader.net.Session
import com.tianxiaodudou.qqmusicdownloader.ui.AppRoot
import com.tianxiaodudou.qqmusicdownloader.ui.theme.QQMusicTheme

class MainActivity : ComponentActivity() {

    // 媒体通知（媒体会话的前台服务通知）需要通知权限，Android 13+ 必须运行时申请
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        QqClient.init(applicationContext)
        QqClient.session = Session.load(this)
        askNotificationPermission()
        enableEdgeToEdge()
        setContent {
            QQMusicTheme {
                AppRoot()
            }
        }
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
