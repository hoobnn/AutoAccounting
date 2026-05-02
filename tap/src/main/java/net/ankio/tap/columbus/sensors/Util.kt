package net.ankio.tap.columbus.sensors

/** 分类 logits 中取 argmax，对应 [TapRT.TapClass] 枚举序。 */
object Util {

    fun getMaxId(input: ArrayList<Float>): Int {
        var currentMax = -3.402823E38f
        var id = 0
        for (i in 0 until input.size) {
            if (currentMax < input[i]) {
                currentMax = input[i]
                id = i
            }
        }
        return id
    }
}
