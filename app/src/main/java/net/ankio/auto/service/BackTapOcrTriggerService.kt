/*
 * Copyright (C) 2026 ankio(ankio@ankio.net)
 * Licensed under the Apache License, Version 3.0 (the "License");
 */

package net.ankio.auto.service

import android.content.Intent
import net.ankio.auto.service.api.ICoreService
import net.ankio.auto.storage.Logger
import net.ankio.auto.utils.PrefManager
import net.ankio.tap.TapBackDetector
import net.ankio.tap.TapLogger
import org.ezbook.server.constant.LogLevel
import org.ezbook.server.intent.IntentType

/**
 * 双击背部触发屏幕识别（OCR）的独立子服务。
 *
 * 与 [OcrService] 解耦：仅负责传感器与 TapTap Columbus/三星管线，通过向 [CoreService] 投递
 * [IntentType.OCR] 启动与通知栏手动识别相同的 [OcrService.onStartCommand] 路径。
 *
 * 在 [CoreService] 的各工作模式列表中注册，**不限于纯 OCR 模式**（Xposed 下若已运行 CoreService 同样生效）。
 */
class BackTapOcrTriggerService : ICoreService() {

    private var detector: TapBackDetector? = null

    override fun onCreate(coreService: CoreService) {
        super.onCreate(coreService)
        if (!PrefManager.ocrBackTapTrigger) {
            Logger.d("BackTapOcrTrigger: disabled by preference")
            return
        }
        ensureTapLoggerHook()
        detector = TapBackDetector(coreService) {
            Logger.d("[TapBack→OCR] double tap, dispatching CoreService OCR intent")
            val intent = Intent(coreService, CoreService::class.java).apply {
                putExtra("intentType", IntentType.OCR.name)
                putExtra("manual", true)
            }
            coreService.startService(intent)
        }.also { d ->
            d.start()
            if (d.isStarted()) {
                Logger.d("BackTapOcrTrigger: sensor listener started")
            } else {
                Logger.e("BackTapOcrTrigger: unavailable (no accelerometer/gyroscope)")
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) {
        // 仅依赖生命周期；OCR 由 Intent 分发给 [OcrService]
    }

    override fun onDestroy() {
        detector?.stop()
        detector = null
    }

    /** 与 [net.ankio.auto.App] 一致：将 :tap 日志接入应用 Logger。 */
    private fun ensureTapLoggerHook() {
        if (TapLogger.hook != null) return
        TapLogger.hook = { level, msg, tr ->
            Logger.log(LogLevel.fromAndroidLevel(level), msg, tr)
        }
    }
}
