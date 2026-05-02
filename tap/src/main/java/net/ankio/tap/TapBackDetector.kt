package net.ankio.tap

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import net.ankio.tap.columbus.sensors.TapRT

/**
 * 双击设备背部检测（Columbus ML + TapTap 「混合模式」参数）。
 *
 * 典型用法：`start()` 后开始监听，`stop()` 卸载传感器并可再次 `start()`（每次会话重建 TFLite 解释器）。
 *
 * @param sensitivity 峰值噪声底，与 TapTap 默认 Columbus `0.05f` 同量级；更小更灵敏。
 */
class TapBackDetector(
    context: Context,
    private val modelAssetPath: String = DEFAULT_MODEL_ASSET,
    private val sensitivity: Float = 0.05f,
    /** 若为 null，则内部创建专用 [HandlerThread] 投递传感器事件。 */
    private val sensorLooper: Looper? = null,
    /** 双击确认后的用户回调所在的 Looper（通常主线程）。 */
    private val callbackLooper: Looper = Looper.getMainLooper(),
    private val onDoubleBackTap: () -> Unit,
) {

    private val appContext = context.applicationContext
    private val sensorManager = appContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroscope: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    private var ownedSensorThread: HandlerThread? = null
    private var sensorHandler: Handler? = null
    private val callbackHandler = Handler(callbackLooper)

    /** 每次 [start] 创建，便于 [stop] 后安全关闭 native 解释器并重入。 */
    private var classifier: TapTfClassifier? = null

    /** 与 [listener] 绑定的单次会话运行时。 */
    private var tapRuntime: TapRT? = null

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent?) {
            val tap = tapRuntime ?: return
            if (event == null) return
            tap.updateData(
                event.sensor.type,
                event.values[0],
                event.values[1],
                event.values[2],
                event.timestamp,
                SAMPLING_INTERVAL_NS,
                isHeuristic = false,
            )
            if (tap.checkDoubleTapTiming(event.timestamp) == 2) {
                callbackHandler.post { onDoubleBackTap() }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    private var started = false

    fun isStarted(): Boolean = started

    /** 混合模式：[applyHybridColumbusInit] + `isHeuristic=false` + SENSOR_DELAY_FASTEST。 */
    fun start() {
        if (started) return
        if (accelerometer == null || gyroscope == null) return

        val looper = sensorLooper ?: HandlerThread("tap-back-sensor").also { t ->
            t.start()
            ownedSensorThread = t
        }.looper
        sensorHandler = Handler(looper)

        classifier = TapTfClassifier(appContext.assets, modelAssetPath)
        tapRuntime = ProjectTapRt(SIZE_WINDOW_NS, classifier!!, sensitivity).also { rt ->
            applyHybridColumbusInit(rt)
        }

        sensorManager.registerListener(
            listener,
            accelerometer,
            SensorManager.SENSOR_DELAY_FASTEST,
            sensorHandler!!,
        )
        sensorManager.registerListener(
            listener,
            gyroscope,
            SensorManager.SENSOR_DELAY_FASTEST,
            sensorHandler!!,
        )
        started = true
    }

    fun stop() {
        if (!started) return
        sensorManager.unregisterListener(listener)
        ownedSensorThread?.quitSafely()
        ownedSensorThread = null
        sensorHandler = null
        classifier?.close()
        classifier = null
        tapRuntime = null
        started = false
    }

    /**
     * 与 TapTap `GestureSensorImpl.startListening(heuristicMode = true)` 分支一致，
     * 再配合 [tap.updateData] 的 ML 分支（文档所谓混合模式）。
     */
    private fun applyHybridColumbusInit(rt: ProjectTapRt) {
        rt.getLowpassKey().setPara(0.2f)
        rt.getHighpassKey().setPara(0.2f)
        rt.getPositivePeakDetector().setMinNoiseTolerate(0.05f)
        rt.getPositivePeakDetector().setWindowSize(0x40)
        rt.reset(false)
    }

    private companion object {
        /** Pixel 4 XL 标定；包内 assets 已含 `columbus/12` 下多机型备选。 */
        const val DEFAULT_MODEL_ASSET = "columbus/12/tap7cls_coral.tflite"
        private const val SIZE_WINDOW_NS = 160_000_000L
        private const val SAMPLING_INTERVAL_NS = 2_500_000L
    }
}
