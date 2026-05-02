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
 * OCR 封装：依赖 [paddleocr4android](https://jitpack.io/#equationl/paddleocr4android)（Ncnn PP-OCRv5）。
 *
 * - 模型从 `attach` 时通过 assets 初始化；需放置官方命名的 det/rec 的 `.param` 与 `.ncnn.bin`。
 * - 传入的 [Bitmap] 由调用方负责 [Bitmap.recycle]，本类不接管所有权（与上层生命周期一致）。
 */
open class OcrProcessor : Closeable {

    /** 底层 OCR 实例，在 [attach] 成功初始化模型后才非空。 */
    private var ocr: OCR? = null

    /** 应用上下文：用于读取 assets（模型文件名须与库的 README 一致）。 */
    private lateinit var appCtx: Context

    /** 是否保存带检测框的调试图到缓存目录。 */
    private var debug: Boolean = false

    /**
     * 绑定上下文并初始化 Ncnn 模型（Mobile、短边缩放至 720、CPU）。
     * 仅在首次调用且尚未初始化成功时加载模型。
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
            output?.invoke(
                "OCR 模型初始化失败（请检查 assets 中 det/rec 的 .param 与 .ncnn.bin）",
                Log.ERROR
            )
        }
    }

    /** 释放 native 资源并重置实例，可再次 [attach]。 */
    fun release() {
        ocr?.release()
        ocr = null
    }

    fun debug(boolean: Boolean) = apply {
        this.debug = boolean
    }

    private var output: ((string: String, type: Int) -> Unit)? = null

    fun log(output: (string: String, type: Int) -> Unit) = apply {
        this.output = output
    }

    /**
     * 将带框的 Bitmap 写入应用缓存目录，并限制目录内文件数量。
     */
    private fun saveDebugImage(bitmap: Bitmap) {
        val dir = File(appCtx.cacheDir, "images_ocr")
        dir.mkdirs()
        val fileName = "ocr_${System.currentTimeMillis()}.png"
        val file = File(dir, fileName)
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        output?.invoke("Debug image saved: ${file.absolutePath}", Log.DEBUG)
        cleanupOldImages(dir, maxCount = 100)
    }

    private fun cleanupOldImages(dir: File, maxCount: Int) {
        val files = dir.listFiles() ?: return
        if (files.size <= maxCount) return
        files.sortBy { it.lastModified() }
        val deleteCount = files.size - maxCount
        files.take(deleteCount).forEach { file ->
            if (file.delete()) {
                output?.invoke("Deleted old debug image: ${file.name}", Log.DEBUG)
            }
        }
    }

    /**
     * 对整张 [bitmap] 做文字识别。
     *
     * @return 聚合后的纯文本（多行会以库侧默认方式拼接）。
     */
    suspend fun startProcess(bitmap: Bitmap): String {
        val engine =
            ocr ?: throw IllegalStateException("OCR engine is not initialized, call attach() first")

        output?.invoke("OCR开始, 输入尺寸: ${bitmap.width}x${bitmap.height}", Log.DEBUG)

        val drawMode = if (debug) DrawModel.Box else DrawModel.None
        return try {
            val result = engine.detectBitmap(bitmap, drawMode)
                ?: run {
                    output?.invoke("OCR识别返回 null", Log.ERROR)
                    return ""
                }

            output?.invoke(
                "OCR完成: inferenceTime=${result.inferenceTime}ms, textLines=${result.textLines.size}",
                Log.DEBUG
            )

            // 调试：库可能返回新 Bitmap，也可能在原图上绘制，仅回收库单独分配的那张。
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
            output?.invoke("OCR异常: ${e.message}", Log.ERROR)
            throw e
        }
    }

    override fun close() {
        release()
    }
}
