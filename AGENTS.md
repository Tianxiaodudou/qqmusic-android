# 项目永久约束（AGENTS.md）

本文件是项目的**永久性开发约束**，任何后续改动（无论人还是 AI）都必须遵守；与其它说明冲突时以本文件为准。

## 0. 最高优先级：遵循谷歌（Android）官方开发规范

**无论如何开发，一律遵循 Google/Android 官方规范。** 具体到本项目：

1. **架构**
   - 播放归 `MediaSessionService`：`ExoPlayer` 与会话由服务创建并持有，UI **只**通过 `MediaController` 观察状态、发送命令（见 `player/PlaybackService.kt`、`player/PlayerController.kt`）。
   - 不要在 Compose/Activity 里 `new ExoPlayer`，也不要让 UI 直接持有播放器实例。
   - 队列即播放列表：每首歌一个 `MediaItem`，直链用 `ResolvingDataSource` 懒解析，网络/解析逻辑放服务侧。
2. **媒体与通知**
   - 前台服务类型 `mediaPlayback`；`MediaSession` 的 metadata/playbackState 由框架同步，不手写。
   - 通知渠道、`POST_NOTIFICATIONS` 运行时权限、`MEDIA_BUTTON`、`MediaButtonReceiver` 按官方文档声明。
   - 不要用自定义按钮/包装器去伪造「上一首/下一首」命令；靠真实播放列表让框架给出标准命令。
3. **Compose / UI**
   - 使用 Material 3 + 单一 `MaterialTheme`，颜色/字号/间距走 `ui/theme` 的 AppTheme 语义 token；页面不写 `Color(0x…)`、`.sp`、`.dp` 字面值。
   - 原生控件统一走 `ui/Components.kt` 的封装组件。
   - 状态用 `StateFlow`/`mutableStateOf` 单向流动；不要在 Composable 里做阻塞 IO。
4. **生命周期与协程**
   - 主线程只做 UI；网络/磁盘一律 `Dispatchers.IO`。
   - 长任务绑定 `lifecycleScope`/结构化并发，不要泄漏协程或监听器。
5. **权限与后台限制**
   - `SYSTEM_ALERT_WINDOW`（桌面歌词）、`FOREGROUND_SERVICE_MEDIA_PLAYBACK` 等按需声明并在运行时引导授权。
   - 遵守 Doze/后台启动限制，不绕系统限制做保活。
6. **兼容与降级**
   - API 差异用 `Build.VERSION.SDK_INT` 分支或 `runCatching` 降级，不要为了新 API 抬高 minSdk。
   - 任何「非官方/实验性」做法（自定义 DataSource、非标准协议等）必须在 PR/CHANGELOG 里显式说明取舍。

## 1. 发布约定

- 每次发版必须先更新 `CHANGELOG.md`（写成 `## vX.Y.Z` 段），CI 会把该段作为 Release 正文。
- 发版流程：改代码 → `assembleDebug assembleRelease testDebugUnitTest -PappVersion=x.y.z` → commit → tag `vX.Y.Z` → push main + tag。
- 只需给出 Release 链接，不必把安装包下载到本地。

## 2. 质量底线

- 合并前必须编译通过 + 单元测试通过（`testDebugUnitTest`）。
- 不引入新的第三方 SDK 除非确有必要，并说明许可证。
- 不提交密钥、Cookie、账号信息；签名材料只允许留在 CI Secrets。
