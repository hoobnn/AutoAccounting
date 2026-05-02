package net.ankio.tap

import net.ankio.tap.columbus.sensors.TapRT
import net.ankio.tap.columbus.sensors.TfClassifier

/**
 * 注入 [TapTfClassifier] 并在 [reset] 时同步 Columbus 噪声门限（TapTapTapRT 简化版：仅双击）。
 */
internal class ProjectTapRt(
    sizeWindowNs: Long,
    classifier: TfClassifier,
    private val sensitivity: Float,
) : TapRT(sizeWindowNs) {

    init {
        _tflite = classifier
    }

    override fun reset(justClearFv: Boolean) {
        getPositivePeakDetector().setMinNoiseTolerate(sensitivity)
        super.reset(justClearFv)
    }
}
