package net.ankio.tap

import android.content.res.AssetManager
import android.util.Log
import net.ankio.tap.columbus.sensors.TfClassifier
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.channels.FileChannel

/**
 * 从 assets 加载 Columbus v12 七类 tap 模型（输入拓扑使用 [predict12]）。
 * 路径示例：`columbus/12/tap7cls_coral.tflite`。
 */
class TapTfClassifier(
    assetManager: AssetManager,
    private val modelAssetPath: String,
) : TfClassifier() {

    private val interpreter: Interpreter? = try {
        assetManager.openFd(modelAssetPath).let { afd ->
            Triple(
                FileInputStream(afd.fileDescriptor).channel,
                afd.startOffset,
                afd.declaredLength,
            )
        }.run {
            Interpreter(
                first.map(FileChannel.MapMode.READ_ONLY, second, third),
                Interpreter.Options(),
            )
        }.also {
            Log.d(TAG, "tflite loaded: $modelAssetPath")
        }
    } catch (e: Exception) {
        Log.e(TAG, "load tflite failed: $modelAssetPath", e)
        null
    }

    override fun predict(input: ArrayList<Float>, size: Int): ArrayList<ArrayList<Float>> {
        val interp = interpreter ?: return ArrayList()
        return predict12(interp, input, size)
    }

    /** 释放 native 资源；与 [TapBackDetector.stop] 配对调用。 */
    fun close() {
        interpreter?.close()
    }

    private companion object {
        const val TAG = "TapTfClassifier"
    }
}
