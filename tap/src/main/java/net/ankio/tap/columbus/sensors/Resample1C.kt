package net.ankio.tap.columbus.sensors

/**
 * 单通道不规则时间序列重采样基类：[tInterval] 为目标步长纳秒，
 * Columbus 中与 2_500_000 ns（400Hz）配合使用。
 */
open class Resample1C {

    protected var tInterval = 0L
    protected var tRawLast: Long = 0
    protected var tResampledLast: Long = 0
    protected var xRawLast = 0f
    protected var xResampledThis = 0.0f

    fun init(x: Float, t: Long, interval: Long) {
        xRawLast = x
        tRawLast = t
        xResampledThis = x
        tResampledLast = t
        tInterval = interval
    }

    fun getInterval(): Long {
        return tInterval
    }

    /** 将重采样时钟与另一传感器对齐。 */
    fun setSyncTime(arg1: Long) {
        tResampledLast = arg1
    }
}
