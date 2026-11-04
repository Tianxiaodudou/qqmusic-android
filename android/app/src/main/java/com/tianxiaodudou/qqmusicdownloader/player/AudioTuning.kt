package com.tianxiaodudou.qqmusicdownloader.player

import android.content.Context
import com.tianxiaodudou.qqmusicdownloader.net.Settings

/**
 * 音频缓冲档位表。
 *
 * 两类缓冲的作用完全不同：
 *  - PCM 缓冲（AudioTrack）：解码后的 PCM 已经写进系统音频队列，队列越长，
 *    系统被别的任务抢 CPU（亮屏、相机、桌面模糊）时越不容易 “断粮” 出咔哒声，代价是操作延迟变大；
 *  - LoadControl 缓冲：播放器**还没解码**的数据要提前下载多少，越长越抗网络抖动，代价是内存与起播时间。
 */
object AudioTuning {

    data class PcmTier(val mult: Int, val label: String, val desc: String)

    /** AudioTrack 缓冲 = 系统最小缓冲 × mult。 */
    val PCM_TIERS = listOf(
        PcmTier(1, "1×（约 20ms）", "延迟最低，但系统一忙就容易出咔哒声，只在设备性能很好时用"),
        PcmTier(2, "2×（约 40ms）", "偏激进，适合只在家用 Wi-Fi 听歌、几乎不同时开相机"),
        PcmTier(4, "4×（约 80ms）", "抗抖动一般，切歌/拖动进度最跟手"),
        PcmTier(6, "6×（约 120ms）", "折中偏快"),
        PcmTier(8, "8×（约 160ms）", "较稳，拖动进度略有延迟"),
        PcmTier(12, "12×（约 240ms）· 默认", "实测在“开相机/回桌面”场景明显减少咔哒声，操作延迟仍可接受"),
        PcmTier(16, "16×（约 320ms）", "很稳，进度反馈开始能感觉到慢"),
        PcmTier(20, "20×（约 400ms）", "追求稳定优先"),
        PcmTier(24, "24×（约 480ms）", "极端稳定，切歌/拖动明显迟钝"),
        PcmTier(32, "32×（约 640ms）", "最后手段，仅在设备极度卡顿时用"),
        PcmTier(48, "48×（约 960ms）", "不建议：延迟接近 1 秒，操作会显得“按了没反应”"),
    )

    data class LoadTier(
        val minBufferMs: Int,
        val maxBufferMs: Int,
        val forPlaybackMs: Int,
        val afterRebufferMs: Int,
        val label: String,
        val desc: String,
    )

    val LOAD_TIERS = listOf(
        LoadTier(15_000, 60_000, 1_000, 3_000, "省流量（15s/60s）", "起播最快、占用内存最小，但网络一抖就容易缓冲"),
        LoadTier(30_000, 90_000, 1_500, 5_000, "轻量（30s/90s）", "适合稳定 Wi-Fi"),
        LoadTier(45_000, 120_000, 2_000, 8_000, "偏轻（45s/120s）", "一般移动网络"),
        LoadTier(60_000, 180_000, 3_000, 12_000, "标准（60s/180s）", "默认偏稳，多数场景够用"),
        LoadTier(60_000, 240_000, 3_000, 12_000, "标准+（60s/240s）· 默认", "抗抖动更好，内存占用略高"),
        LoadTier(90_000, 300_000, 4_000, 15_000, "稳健（90s/300s）", "地铁/弱网推荐"),
        LoadTier(120_000, 420_000, 5_000, 20_000, "大缓冲（120s/420s）", "弱网下更少中断，占内存明显变大"),
        LoadTier(180_000, 600_000, 6_000, 25_000, "超大（180s/600s）", "适合频繁掉线的网络"),
        LoadTier(240_000, 900_000, 8_000, 30_000, "激进（240s/900s）", "高码率母带/弱网，内存占用大"),
        LoadTier(300_000, 1_200_000, 10_000, 40_000, "极端（300s/1200s）", "仅在网络极差时使用"),
        LoadTier(600_000, 1_800_000, 15_000, 60_000, "缓存优先（600s/1800s）", "几乎等于把整首歌预载完，起播慢"),
    )

    fun pcmTier(context: Context): PcmTier = PCM_TIERS.getOrElse(Settings.pcmBufferTier(context)) { PCM_TIERS[5] }
    fun loadTier(context: Context): LoadTier = LOAD_TIERS.getOrElse(Settings.loadBufferTier(context)) { LOAD_TIERS[4] }
}
