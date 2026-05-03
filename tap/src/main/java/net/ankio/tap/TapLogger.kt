/*
 * Copyright (C) 2026 ankio(ankio@ankio.net)
 * Licensed under the Apache License, Version 3.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-3.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.ankio.tap

import android.util.Log

/**
 * `:tap` 模块统一日志入口：子标签 + 正文，宿主可通过 [hook] 转发到 [net.ankio.auto.storage.Logger]；
 * 未设置 [hook] 时使用 [android.util.Log]，主 tag 为 [LOG_TAG]（默认 `OCR`，与现有 OCR 日志一致）。
 */
object TapLogger {

    /** 与 OCR 主流程同 tag，便于 `adb logcat -s OCR` 一条链路看完。 */
    const val LOG_TAG: String = "OCR"

    /**
     * 可选转发：(priority, line, throwable)，其中 line 已含格式 `[subTag] message`。
     * 设为 null 则走 [Log.println]。
     */
    @Volatile
    var hook: ((priority: Int, line: String, throwable: Throwable?) -> Unit)? = null

    fun d(subTag: String, msg: String) = emit(Log.DEBUG, subTag, msg, null)

    fun i(subTag: String, msg: String) = emit(Log.INFO, subTag, msg, null)

    fun w(subTag: String, msg: String, tr: Throwable? = null) = emit(Log.WARN, subTag, msg, tr)

    fun e(subTag: String, msg: String, tr: Throwable? = null) = emit(Log.ERROR, subTag, msg, tr)

    private fun emit(priority: Int, subTag: String, message: String, tr: Throwable?) {
        val line = "[$subTag] $message"
        val h = hook
        if (h != null) {
            h(priority, line, tr)
        } else if (tr != null) {
            Log.println(priority, LOG_TAG, "$line\n${Log.getStackTraceString(tr)}")
        } else {
            Log.println(priority, LOG_TAG, line)
        }
    }
}
