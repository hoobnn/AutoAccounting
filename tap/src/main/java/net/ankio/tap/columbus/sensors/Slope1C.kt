package net.ankio.tap.columbus.sensors

/** 离散微分；[d] 为按实际采样间隔缩放后的刻度因子。 */
class Slope1C {

    private var xDelta = 0.0f
    private var xRawLast = 0f

    fun init(x: Float) {
        xRawLast = x
    }

    fun update(value: Float, d: Float): Float {
        val x = value * d
        val delta = x - xRawLast
        xDelta = delta
        xRawLast = x
        return delta
    }
}
