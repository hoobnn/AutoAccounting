package net.ankio.auto.service.ocr

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.provider.Settings
import android.view.Display
import com.google.android.accessibility.selecttospeak.SelectToSpeakService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import net.ankio.auto.App
import net.ankio.auto.BuildConfig
import net.ankio.auto.R
import net.ankio.auto.storage.Logger
import net.ankio.auto.utils.SystemUtils
import net.ankio.shell.Shell
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.resume

/**
 * OCR 助手：负责截图与前台应用检测
 */
object OcrTools {

    private val SERVICE_CLASS = SelectToSpeakService::class.java
    private val COMPONENT_NAME = "${BuildConfig.APPLICATION_ID}/${SERVICE_CLASS.name}"

    // ======================== 核心功能 ========================

    /**
     * 获取当前前台包名（用于 OCR 传给规则引擎的 app 参数）。
     *
     * 优先使用 [AccessibilityService.rootInActiveWindow]：在触发 OCR 的瞬间反映真实焦点窗口，
     * 避免仅依赖 [SelectToSpeakService.topPackage]（仅靠 WINDOW_STATE_CHANGED 更新，连接初期或
     * 快速切换时可能为空或滞后）。
     *
     * 过滤规则与 [SelectToSpeakService.onAccessibilityEvent] 一致：排除本包、系统框架、无点号包名。
     */
    fun getTopApp(): String? {
        val fromWindow = readForegroundPackageFromActiveWindow()
        if (fromWindow != null) return fromWindow
        val cached = SelectToSpeakService.topPackage
        return cached?.takeIf { isPlausibleForegroundPackage(it) }
    }

    /** 从当前活动窗口根节点读取包名；无效或需排除时返回 null。 */
    private fun readForegroundPackageFromActiveWindow(): String? {
        val root = SelectToSpeakService.instance?.rootInActiveWindow ?: return null
        return try {
            val pkg = root.packageName?.toString()
            if (pkg.isNullOrBlank() || !isPlausibleForegroundPackage(pkg)) null else pkg
        } finally {
            root.recycle()
        }
    }

    /** 与无障碍事件里对包名的过滤保持一致，避免把系统 UI 或本应用当作「待识别 App」。 */
    fun isPlausibleForegroundPackage(pkg: String): Boolean {
        if (pkg == BuildConfig.APPLICATION_ID) return false
        if (pkg.startsWith("com.android.")) return false
        if (!pkg.contains('.')) return false
        return true
    }

    /** 截取当前屏幕 */
    suspend fun takeScreenshot(outFile: File): Boolean = withContext(Dispatchers.IO) {
        val service = SelectToSpeakService.instance ?: return@withContext false

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return@withContext false

        suspendCancellableCoroutine { cont ->
            service.takeScreenshot(
                Display.DEFAULT_DISPLAY,
                service.mainExecutor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                        val bitmap =
                            Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                        val success = bitmap?.let {
                            val saved = saveBitmap(it, outFile)
                            it.recycle()
                            saved
                        } ?: false
                        result.hardwareBuffer.close()
                        cont.resume(success)
                    }

                    override fun onFailure(errorCode: Int) {
                        Logger.e("Screenshot failed: $errorCode")
                        cont.resume(false)
                    }
                })
        }
    }

    private fun saveBitmap(bitmap: Bitmap, file: File): Boolean = try {
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        true
    } catch (e: Exception) {
        false
    }

    // ======================== 权限管理 ========================

    fun hasPermission() = SystemUtils.isAccessibilityServiceEnabled(SERVICE_CLASS)


    /** 尝试开启无障碍：有 Root 用 Root，没 Root 弹设置 */
    suspend fun requestPermission(): Boolean {
        if (hasPermission()) return true

        val shell = Shell(BuildConfig.APPLICATION_ID)
        val hasShell = shell.rootPermission() || shell.shizukuPermission()

        if (hasShell) {
            tryEnableViaShell(shell)
            delay(800) // 等待服务启动
        }

        if (!hasPermission()) {
            withContext(Dispatchers.Main) { openSettings() }
            return false

        }
        return true
    }

    private suspend fun tryEnableViaShell(shell: Shell) {
        val cmdGet = "settings get secure enabled_accessibility_services"
        val current = shell.exec(cmdGet).trim().let { if (it == "null") "" else it }

        if (current.contains(COMPONENT_NAME)) return

        val newList = if (current.isEmpty()) COMPONENT_NAME else "$current:$COMPONENT_NAME"
        shell.exec("settings put secure enabled_accessibility_services $newList")
        shell.exec("settings put secure accessibility_enabled 1")
    }

    private fun openSettings() {
        SystemUtils.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    suspend fun collapseStatusBar() {
        SelectToSpeakService.instance?.performGlobalAction(
            AccessibilityService.GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE
        )
        delay(500)
    }
}