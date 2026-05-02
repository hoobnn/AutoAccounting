package net.ankio.tap.columbus.sensors

/** 三维浮点向量，用于滤波链中间状态（原 Columbus Sample3C / slope 管线）。 */
data class Point3f(var x: Float, var y: Float, var z: Float)
