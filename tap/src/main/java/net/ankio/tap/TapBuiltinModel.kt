/*
 * Copyright (C) 2026 ankio(ankio@ankio.net)
 * Licensed under the Apache License, Version 3.0 (the "License");
 */

package net.ankio.tap

/**
 * 与 TapTap `TapModel` 对齐的内置 Columbus / 三星 RegiStar 模型条目。
 *
 * @property assetPath assets 内相对路径（与 TapTap columbus / samsung 模块一致）
 * @property referenceHeightMm 机身高度标定值（毫米），仅用于无网络时就近匹配 Columbus；三星 OEM 填 0
 */
enum class TapBuiltinModel(
    val id: String,
    val assetPath: String,
    val referenceHeightMm: Double,
) {
    REDFIN("redfin", "columbus/12/tap7cls_redfin.tflite", 144.7),
    FLAME("flame", "columbus/12/tap7cls_flame.tflite", 147.1),
    BRAMBLE("bramble", "columbus/12/tap7cls_bramble.tflite", 153.9),
    CROSSHATCH("crosshatch", "columbus/12/tap7cls_crosshatch.tflite", 158.0),
    CORAL("coral", "columbus/12/tap7cls_coral.tflite", 160.4),

    PIXEL4("pixel4", "columbus/11/tap7cls_pixel4.tflite", 147.1),
    PIXEL3_XL("pixel3_xl", "columbus/11/tap7cls_pixel3xl.tflite", 158.0),
    PIXEL4_XL("pixel4_xl", "columbus/11/tap7cls_pixel4xl.tflite", 160.4),

    SAMSUNG_REGI("samsung", "samsung/backtap_20221018-160917.tflite", 0.0),
    ;

    /** 是否使用三星 RegiStar 管线（与 Columbus [TapRT] 互斥）。 */
    fun isSamsungRegi(): Boolean = this == SAMSUNG_REGI

    /** Columbus v11 扁平输入拓扑（否则为 v12 四维输入）。 */
    fun usesColumbusPredict11(): Boolean = assetPath.contains("columbus/11/")

    companion object {
        /** TapTap 默认 Columbus 机型。 */
        val TAP_TAP_DEFAULT_COLUMBUS: TapBuiltinModel = BRAMBLE

        /** 参与按屏高就近匹配的 Columbus 条目（排除三星）。 */
        val COLUMBUS_BY_HEIGHT: List<TapBuiltinModel> = entries.filter { !it.isSamsungRegi() }
    }
}
