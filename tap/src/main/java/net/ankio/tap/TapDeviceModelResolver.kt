/*
 * Copyright (C) 2026 ankio(ankio@ankio.net)
 * Licensed under the Apache License, Version 3.0 (the "License");
 */

package net.ankio.tap

import android.content.Context
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import kotlin.math.abs

/**
 * 无网络、无外部 API：按 [Build] 与屏幕物理高度近似值自动选择 [TapBuiltinModel]。
 *
 * 策略对齐 TapTap 思路：三星走 RegiStar；Google 设备按 codename；其余按屏高在 Columbus 列表中就近匹配。
 */
object TapDeviceModelResolver {

    /**
     * @param context 用于读取 [DisplayMetrics] 估计机身高度
     */
    fun resolve(context: Context): TapBuiltinModel {
        val manufacturer = Build.MANUFACTURER.lowercase()
        if (manufacturer.contains("samsung")) {
            return TapBuiltinModel.SAMSUNG_REGI
        }

        resolveGoogleByCodename()?.let { return it }

        val heightMm = estimateBodyHeightMm(context)
        val best = TapBuiltinModel.COLUMBUS_BY_HEIGHT.minByOrNull { m ->
            abs(m.referenceHeightMm - heightMm)
        } ?: TapBuiltinModel.TAP_TAP_DEFAULT_COLUMBUS
        TapLogger.d(
            RESOLVER_TAG,
            "pick by height: est=${"%.1f".format(heightMm)}mm -> ${best.id} path=${best.assetPath}",
        )
        return best
    }

    private const val RESOLVER_TAG = "TapModel"

    /** Google / Pixel：优先 [Build.DEVICE]（刷机社区与真机一致）。 */
    private fun resolveGoogleByCodename(): TapBuiltinModel? {
        val device = Build.DEVICE.lowercase()
        val model = Build.MODEL.lowercase()
        val haystack = "$device $model"
        val table = listOf(
            "redfin" to TapBuiltinModel.REDFIN,
            "flame" to TapBuiltinModel.FLAME,
            "bramble" to TapBuiltinModel.BRAMBLE,
            "crosshatch" to TapBuiltinModel.CROSSHATCH,
            "coral" to TapBuiltinModel.CORAL,
            "sunfish" to TapBuiltinModel.PIXEL4,
            "bonito" to TapBuiltinModel.PIXEL3_XL,
            "barbet" to TapBuiltinModel.BRAMBLE,
            "oriole" to TapBuiltinModel.BRAMBLE,
            "raven" to TapBuiltinModel.BRAMBLE,
            "bluejay" to TapBuiltinModel.BRAMBLE,
            "panther" to TapBuiltinModel.BRAMBLE,
            "cheetah" to TapBuiltinModel.CORAL,
            "lynx" to TapBuiltinModel.CORAL,
        )
        for ((needle, builtin) in table) {
            if (device == needle || haystack.contains(needle)) {
                TapLogger.d(
                    RESOLVER_TAG,
                    "pick by codename: $needle -> ${builtin.id} path=${builtin.assetPath}"
                )
                return builtin
            }
        }
        if (manufacturerIsGoogle()) {
            val fallback = TapBuiltinModel.TAP_TAP_DEFAULT_COLUMBUS
            TapLogger.d(
                RESOLVER_TAG,
                "google device but unknown codename device=$device model=$model -> default ${fallback.id}",
            )
            return fallback
        }
        return null
    }

    private fun manufacturerIsGoogle(): Boolean {
        val m = Build.MANUFACTURER.lowercase()
        return m.contains("google") || m == "essential"
    }

    /**
     * 用竖边像素 / ydpi 换算机身长边毫米，近似 GSMArena「高度」；横屏取较大边。
     * 仅为就近匹配 Columbus，非精确规格。
     */
    private fun estimateBodyHeightMm(context: Context): Double {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(dm)
        val longPx = dm.heightPixels.coerceAtLeast(dm.widthPixels).toDouble()
        val ydpi = dm.ydpi.toDouble().coerceAtLeast(1.0)
        val inches = longPx / ydpi
        return inches * 25.4
    }
}
