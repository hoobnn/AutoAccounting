package net.ankio.tap.columbus

/**
 * TapTap Columbus 运行时对外最小接口：
 * IMU 数据进入 [updateData]，由 [checkDoubleTapTiming] 判断是否形成双击背部时间窗。
 */
interface BaseTapRT {

    /** 投喂一次加速度计(type=1)或陀螺仪(type=4)样本。 */
    fun updateData(
        type: Int,
        lastX: Float,
        lastY: Float,
        lastZ: Float,
        lastT: Long,
        interval: Long,
        isHeuristic: Boolean,
    )

    /**
     * 基于当前时间与内部 Back 击打时间队列，返回状态码：
     * 0 — 无双击候选；1 — 有候选；2 — 已满足双击间隔（TapTap 内部用于触发动作）。
     */
    fun checkDoubleTapTiming(timestamp: Long): Int

    /** 重置内部缓冲；[justClearFv] 语义与 GestureSensorImpl 一致。 */
    fun reset(justClearFv: Boolean)
}
