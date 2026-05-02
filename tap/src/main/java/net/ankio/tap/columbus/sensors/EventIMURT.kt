package net.ankio.tap.columbus.sensors

import java.util.ArrayDeque

/** IMU 共享状态：重采样缓冲区、滤波器与特征占位（Columbus EventIMURT）。 */
open class EventIMURT {

    protected var _fv = ArrayList<Float>()
    protected var _gotAcc = false
    protected var _gotGyro = false
    protected var _highpassAcc = Highpass3C()
    protected var _highpassGyro = Highpass3C()
    protected var _lowpassAcc = Lowpass3C()
    protected var _lowpassGyro = Lowpass3C()
    protected var _numberFeature = 0
    protected var _resampleAcc = Resample3C()
    protected var _resampleGyro = Resample3C()
    protected var _sizeFeatureWindow = 0
    protected var _sizeWindowNs = 0L
    protected var _slopeAcc = Slope3C()
    protected var _slopeGyro = Slope3C()
    protected var _syncTime = 0L
    protected var _xsAcc = ArrayDeque<Float>()
    protected var _xsGyro = ArrayDeque<Float>()
    protected var _ysAcc = ArrayDeque<Float>()
    protected var _ysGyro = ArrayDeque<Float>()
    protected var _zsAcc = ArrayDeque<Float>()
    protected var _zsGyro = ArrayDeque<Float>()

    /** 陀螺仪分支：与加速度计并行维护等长deque。 */
    fun processGyro() {
        val resamplePoint = _resampleGyro.results.point
        val slopePoint = _slopeGyro.update(
            resamplePoint,
            2500000.0f / _resampleGyro.getInterval().toFloat(),
        )
        val lowpassPoint = _lowpassGyro.update(slopePoint)
        val highpassPoint = _highpassGyro.update(lowpassPoint)
        _xsGyro.add(highpassPoint.x)
        _ysGyro.add(highpassPoint.y)
        _zsGyro.add(highpassPoint.z)
        val interval = (_sizeWindowNs / _resampleGyro.getInterval()).toInt()
        while (_xsGyro.size > interval) {
            _xsGyro.removeFirst()
            _ysGyro.removeFirst()
            _zsGyro.removeFirst()
        }
    }

    /** 清空队列与对齐状态。 */
    fun reset() {
        _xsAcc.clear()
        _ysAcc.clear()
        _zsAcc.clear()
        _xsGyro.clear()
        _ysGyro.clear()
        _zsGyro.clear()
        _gotAcc = false
        _gotGyro = false
        _syncTime = 0L
    }

    /** gyro 半边特征缩放（后半段乘以 [scale]）。 */
    fun scaleGyroData(data: ArrayList<Float>, scale: Float): ArrayList<Float> {
        for (i in data.size / 2 until data.size) {
            data[i] = data[i] * scale
        }
        return data
    }
}
