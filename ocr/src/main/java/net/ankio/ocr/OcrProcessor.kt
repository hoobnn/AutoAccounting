/*
 * Copyright (C) 2025 ankio(ankio@ankio.net)
 * Licensed under the Apache License, Version 3.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-3.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package net.ankio.ocr

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.equationl.ncnnandroidppocr.OCR
import com.equationl.ncnnandroidppocr.bean.Device
import com.equationl.ncnnandroidppocr.bean.DrawModel
import com.equationl.ncnnandroidppocr.bean.ImageSize
import com.equationl.ncnnandroidppocr.bean.ModelType
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream

/**
 * 对 [paddleocr4android](https://jitpack.io/#equationl/paddleocr4android)（Ncnn PP-OCRv5）的轻量封装。
 *
 * - 在 [attach] 中从 assets 加载模型，文件名须与上游文档一致（det/rec 的 `.param` 与 `.ncnn.bin`）。
 * - 默认使用 GPU（[Device.GPU]）；仅当设备上 GPU 初始化失败时再考虑改用 CPU。
 * - 传入的 [Bitmap] 由调用方负责回收，本类不调用 [Bitmap.recycle]。
 */
open class OcrProcessor : Closeable {

    /** 模型初始化成功并在 [attach] 完成后非空。 */
    private var ocr: OCR? = null

    private lateinit var appCtx: Context

    private var debug: Boolean = false

    /**
     * 可选日志出口：`(message, androidLogLevel)`。
     * 应短小、尽量避免抛异常；通常与 OCR 调用在同一线程顺序执行。
     */
    private var logSink: ((message: String, androidLogLevel: Int) -> Unit)? = null

    /**
     * 绑定上下文并从 assets 加载 Mobile 端 PP-OCRv5 模型（短边缩放 [ImageSize.Size720]）。
     * 默认 GPU 推理（[Device.GPU]）；在 [release] 之前至多成功初始化一次。
     *
     * @param context 任意 [Context]；内部使用 [Context.getApplicationContext]。
     */
    fun attach(context: Context) = apply {
        appCtx = context.applicationContext
        if (ocr != null) return@apply

        val engine = OCR()
        val ok = engine.initModelFromAssert(
            appCtx.assets,
            ModelType.Mobile,
            ImageSize.Size720,
            Device.GPU
        )
        if (ok) {
            ocr = engine
        } else {
            engine.release()
            emit(
                "OCR model init failed; verify det/rec .param and .ncnn.bin in assets",
                Log.ERROR
            )
        }
    }

    /** 释放 native OCR 资源并清空引擎引用；可重复调用，无副作用。 */
    fun release() {
        ocr?.release()
        ocr = null
    }

    /**
     * 开启或关闭调试：在 [startProcess] 中使用 [DrawModel.Box] 绘制检测框。
     *
     * @param enabled 为 `true` 时将带框图保存为 PNG 至应用缓存目录。
     */
    fun debug(enabled: Boolean) = apply {
        this.debug = enabled
    }

    /**
     * 将内部诊断信息转发到你的日志实现；等级请使用 [android.util.Log] 常量（如 [Log.DEBUG]）。
     */
    fun log(sink: (message: String, androidLogLevel: Int) -> Unit) = apply {
        this.logSink = sink
    }

    /** 若已设置 [logSink] 则转发，否则忽略。 */
    private fun emit(message: String, androidLogLevel: Int) {
        logSink?.invoke(message, androidLogLevel)
    }

    /** 将 [bitmap] 以 PNG 写入应用缓存目录下的 `images_ocr/`，并调用 [cleanupOldImages] 清理旧文件。 */
    private fun saveDebugImage(bitmap: Bitmap) {
        val dir = File(appCtx.cacheDir, "images_ocr")
        dir.mkdirs()
        val file = File(dir, "ocr_${System.currentTimeMillis()}.png")
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        emit("Debug image saved: ${file.absolutePath}", Log.DEBUG)
        cleanupOldImages(dir, maxCount = 100)
    }

    /** 当 [dir] 内文件数超过 [maxCount] 时，按修改时间删除最旧的若干文件。 */
    private fun cleanupOldImages(dir: File, maxCount: Int) {
        val files = dir.listFiles() ?: return
        if (files.size <= maxCount) return
        files.sortBy { it.lastModified() }
        files.take(files.size - maxCount).forEach { file ->
            if (file.delete()) {
                emit("Deleted old debug image: ${file.name}", Log.DEBUG)
            }
        }
    }

    /**
     * 对整张图像执行 OCR。
     *
     * @return 合并后的纯文本；native 无结果时返回空字符串。
     */
    suspend fun startProcess(bitmap: Bitmap): String {
        val engine =
            ocr ?: throw IllegalStateException("OCR not initialized; call attach() first")

        emit("OCR start, input size: ${bitmap.width}x${bitmap.height}", Log.DEBUG)

        val drawMode = if (debug) DrawModel.Box else DrawModel.None
        return try {
            val result = engine.detectBitmap(bitmap, drawMode)
                ?: run {
                    emit("OCR detectBitmap returned null", Log.ERROR)
                    return ""
                }

            emit(
                "OCR done, inferenceTime=${result.inferenceTime}ms, textLines=${result.textLines.size}",
                Log.DEBUG
            )

            val overlay = result.drawBitmap
            if (debug) {
                val toSave = overlay ?: bitmap
                saveDebugImage(toSave)
                if (overlay != null && overlay !== bitmap) {
                    overlay.recycle()
                }
            }

            result.text
        } catch (e: Exception) {
            emit("OCR error: ${e.message}", Log.ERROR)
            throw e
        }
    }

    /** 便于 `use { }`，行为与 [release] 一致。 */
    override fun close() {
        release()
    }
}
