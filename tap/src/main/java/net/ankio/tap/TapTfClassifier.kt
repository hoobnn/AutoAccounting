/*
 * Copyright (C) 2026 ankio(ankio@ankio.net)
 * Licensed under the Apache License, Version 3.0 (the "License");
 */

package net.ankio.tap

import android.content.res.AssetManager
import net.ankio.tap.columbus.sensors.TfClassifier
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.channels.FileChannel

/**
 * 从 assets 加载 TapTap 同款 Columbus（v11/v12）或三星 RegiStar `.tflite`。
 *
 * - Columbus：仅 [predict]
 * - 三星：[predictArray] 仅由 [net.ankio.tap.samsung.SamsungBackTapDetectionService] 调用
 */
class TapTfClassifier(
    assetManager: AssetManager,
    private val builtinModel: TapBuiltinModel,
) : TfClassifier() {

    private val interpreter: Interpreter? = try {
        assetManager.openFd(builtinModel.assetPath).let { afd ->
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
            TapLogger.d(
                SUB,
                "model ok: ${builtinModel.id} ${builtinModel.assetPath} v11=${builtinModel.usesColumbusPredict11()}",
            )
        }
    } catch (e: Exception) {
        TapLogger.e(SUB, "model failed: ${builtinModel.assetPath}", e)
        null
    }

    override fun predict(input: ArrayList<Float>, size: Int): ArrayList<ArrayList<Float>> {
        val interp = interpreter ?: return ArrayList()
        if (builtinModel.isSamsungRegi()) return ArrayList()
        return if (builtinModel.usesColumbusPredict11()) {
            predict11(interp, input, size)
        } else {
            predict12(interp, input, size)
        }
    }

    /** 三星 RegiStar：`Interpreter.run` 多数组 I/O。 */
    fun predictArray(input: Array<FloatArray>, output: Array<FloatArray>) {
        val interp = interpreter ?: return
        if (!builtinModel.isSamsungRegi()) return
        runCatching { interp.run(input, output) }
            .onFailure { TapLogger.e(SUB, "Samsung predictArray failed", it) }
    }

    /** 释放 native 资源；与 [TapBackDetector.stop] 配对。 */
    fun close() {
        interpreter?.close()
    }

    private companion object {
        private const val SUB = "TapBack"
    }
}
