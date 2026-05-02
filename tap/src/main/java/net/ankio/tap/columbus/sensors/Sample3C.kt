package net.ankio.tap.columbus.sensors

/** 一次三轴采样及其时间戳，供 [Resample3C] 输出。 */
class Sample3C(x: Float, y: Float, z: Float, var t: Long) {
    var point: Point3f = Point3f(x, y, z)
}
