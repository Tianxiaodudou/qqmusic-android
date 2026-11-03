package com.tianxiaodudou.qqmusicdownloader.player

/**
 * 只保留预设名与「设置预设」的入口。
 *
 * 真正的音频处理已经迁到播放器内部的 AudioProcessor（见 DspProcessors.kt），
 * 不再使用系统 AudioEffect：会话上挂效果器会强制 AudioFlinger 走混音+效果链，
 * 亮屏/回桌面时容易被系统动画抢调度而产生 underrun 爆音。
 */
object AudioFx {

    val PRESETS = EqPresets.NAMES

    /** 改均衡器预设：只改处理器参数，下一个 buffer 立即生效，不重配播放器。 */
    fun setPreset(preset: Int) {
        ProcessorState.eqPreset = preset.coerceIn(0, PRESETS.lastIndex)
    }

    /** 改音量增益档位（0..9 dB），同样即时生效。 */
    fun setGain(level: Int) {
        ProcessorState.gainLevel = level.coerceIn(0, 9)
    }
}
