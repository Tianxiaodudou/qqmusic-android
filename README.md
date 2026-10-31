# QQ音乐下载器（安卓）

完全独立运行的 QQ 音乐下载器：登录、搜索、歌单、推荐、播放（含无损解密）、逐字歌词、下载到手机本地，全部在设备上完成，不依赖任何服务器。

## 安装

从 [Releases](https://github.com/Tianxiaodudou/qqmusic-android/releases) 下载 `*-qqmusic-downloader.apk` 直接安装（Android 8.0+）。

固定签名，新版本可直接覆盖安装，不需要卸载。

签名证书 SHA-256：

```
A0:F7:A1:C4:57:95:D1:0A:DB:31:41:AD:ED:77:03:16:FC:20:4E:08:E2:F7:F2:30:1E:20:F1:C3:47:B3:A9:CA
```

## 功能

- **登录**：QQ 扫码 / 微信扫码 / 手机验证码，登录态本地持久化
- **首页**：胶囊入口墙 + 为你推荐信息流；推荐歌单、新歌速递、每日推荐、新碟上架、热门歌手、热搜榜、排行榜、猜你喜欢
- **搜索**：按歌名 / 歌手 / 专辑搜索
- **歌单**：我的歌单、我喜欢、收藏到我喜欢、加到歌单
- **播放**：标准 / 高品质 / 无损三档，无损走 QMC2 解密并缓存；播放队列、自动续播
- **歌词**：QRC 逐字歌词（QQ 自有的 3DES 变体），无逐字时回退整行 LRC，带翻译
- **下载**：系统文件选择器选目录（SAF），写入标签 / 封面 / 歌词，下载历史

## 构建

CI 在推 tag 时自动构建并发布 Release。

本地构建需要 JDK 17 + Android SDK 36：

```bash
cd android
gradle assembleRelease -PappVersion=1.18.6
```

签名通过环境变量注入（`ANDROID_KEYSTORE_PATH` / `ANDROID_KEYSTORE_PASSWORD` / `ANDROID_KEY_ALIAS` / `ANDROID_KEY_PASSWORD`），未设置时回退 debug 签名。

单元测试（3DES 移植与参考实现对拍）：

```bash
cd android && gradle testDebugUnitTest -PappVersion=1.18.6
```

## 目录

- `android/` —— Kotlin + Jetpack Compose 工程
- `.github/workflows/build-apk.yml` —— 打 tag 即出包并发布 Release
