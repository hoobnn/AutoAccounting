/*
 * Copyright (C) 2025 ankio(ankio@ankio.net)
 * Licensed under the Apache License, Version 3.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-3.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package net.ankio.auto.storage.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.ankio.auto.BuildConfig
import net.ankio.auto.R
import net.ankio.auto.exceptions.RestoreBackupException
import kotlinx.coroutines.delay
import net.ankio.auto.http.RequestsUtils
import net.ankio.auto.storage.CacheManager
import net.ankio.auto.storage.Logger
import net.ankio.auto.storage.ZipUtils
import net.ankio.auto.ui.utils.LoadingUtils
import net.ankio.auto.utils.SystemUtils
import java.io.File
import java.io.FileOutputStream

/**
 * 备份文件管理器，负责打包和解包备份数据
 */
class BackupFileManager(private val context: Context) {

    companion object {
        const val SUFFIX = "pk"
        const val SUPPORT_VERSION = 203
    }

    private var loading = runCatching {
        LoadingUtils(context)
    }.getOrNull()

    /**
     * 打包数据文件
     * @param filename 目标文件名
     */
    suspend fun packData(filename: String) = withContext(Dispatchers.IO) {
        try {
            loading?.show(context.getString(R.string.backup_preparing))
            val backupDir = prepareBackupDirectory()

            // 下载数据库文件
            loading?.setText(context.getString(R.string.backup_database))
            downloadDatabase(backupDir)

            // 备份配置文件
            loading?.setText(context.getString(R.string.backup_preferences))
            backupDataDirectory(backupDir)

            // 创建索引文件
            loading?.setText(context.getString(R.string.backup_creating_index))
            createIndexFile(backupDir)

            // 压缩所有文件
            loading?.setText(context.getString(R.string.backup_compressing))
            ZipUtils.zipAll(backupDir, filename, excludeRootDir = true)

            loading?.close()
        } catch (e: Exception) {
            loading?.close()
            throw e
        }
    }

    /**
     * 解压备份文件
     * @param file 备份文件
     */
    suspend fun unpackData(file: File) = withContext(Dispatchers.IO) {
        try {
            loading?.show(context.getString(R.string.restore_preparing))
            val backupDir = prepareBackupDirectory()

            // 解压文件
            loading?.setText(context.getString(R.string.restore_extracting))
            ZipUtils.unzip(file.absolutePath, backupDir.absolutePath) {
                Logger.d("解压进度: $it")
            }
            file.delete()

            // 验证备份文件
            loading?.setText(context.getString(R.string.restore_validating))
            validateBackup(backupDir)

            // 恢复数据库
            loading?.setText(context.getString(R.string.restore_database))
            restoreDatabase(backupDir)

            // 恢复配置文件
            loading?.setText(context.getString(R.string.restore_preferences))
            restoreDataDirectory(backupDir)

            // 清空缓存，确保恢复的数据生效
            loading?.setText(context.getString(R.string.restore_clearing_cache))
            CacheManager.clear()

            // 准备重启应用
            loading?.setText(context.getString(R.string.restore_restarting))
            loading?.close()

            // 延迟后重启应用，确保UI有时间关闭
            delay(1000)
            SystemUtils.restart()
        } catch (e: Exception) {
            loading?.close()
            throw e
        }
    }

    /**
     * 准备备份目录
     */
    private fun prepareBackupDirectory(): File {
        val backupDir = File(context.cacheDir, "backup")
        if (backupDir.exists()) {
            backupDir.deleteRecursively()
        }
        backupDir.mkdirs()
        return backupDir
    }

    /**
     * 下载数据库文件
     */
    private suspend fun downloadDatabase(backupDir: File) {
        val requestUtils = RequestsUtils()
        val dbFile = File(backupDir, "auto.db")
        val result = requestUtils.download("http://127.0.0.1:52045/db/export", dbFile)

        if (result.isFailure) {
            val exception = result.exceptionOrNull()
            if (exception !== null) {
                Logger.e(exception)
            }

            throw RestoreBackupException(context.getString(R.string.backup_error))
        }
    }

    /**
     * 创建索引文件
     */
    private fun createIndexFile(backupDir: File) {
        val indexData = mapOf(
            "version" to SUPPORT_VERSION,
            "versionName" to BuildConfig.VERSION_NAME,
            "packageName" to BuildConfig.APPLICATION_ID,
            "packageVersion" to BuildConfig.VERSION_CODE,
            "backupType" to "fullDataDir",
        )

        val json = Gson().toJson(indexData)
        val indexFile = File(backupDir, "auto.index")
        FileOutputStream(indexFile).use { outputStream ->
            outputStream.write(json.toByteArray())
        }
    }

    /**
     * 验证备份文件
     */
    private fun validateBackup(backupDir: File) {
        val indexFile = File(backupDir, "auto.index")
        val json = indexFile.readText()
        indexFile.delete()

        val backupInfo = Gson().fromJson(json, JsonObject::class.java)
        Logger.d("备份文件信息: $backupInfo")

        // 检查版本兼容性
        val version = backupInfo.get("version").asInt
        if (version < SUPPORT_VERSION) {
            throw RestoreBackupException(
                context.getString(
                    R.string.unsupport_backup,
                    backupInfo["versionName"],
                )
            )
        }


    }

    /**
     * 恢复数据库
     */
    private suspend fun restoreDatabase(backupDir: File) {
        val dbFile = File(backupDir, "auto.db")
        // 上传前在 App 侧瘦身：日志是可再生数据，无需跨进程传输。
        // 旧备份的 auto.db 可能高达数百 MB（几乎全是日志），直接经 HTTP 上传会撑爆 server 端内存导致 OOM。
        shrinkDatabaseForImport(dbFile)
        val requestUtils = RequestsUtils()
        val result = requestUtils.put("http://127.0.0.1:52045/db/import", dbFile)
        Logger.d("数据库导入结果: $result")
    }

    /**
     * 导入前瘦身：清空日志表并 VACUUM 回收空间
     *
     * - DELETE FROM LogModel 不带条件会走 SQLite 的 truncate 优化，瞬间完成且几乎不占内存
     * - 保留空表以通过 Room 的 schema 校验（不能 DROP）
     * - VACUUM 所需临时空间约等于有效数据大小（瘦身后仅几 MB）
     * - 全程为 native 层磁盘操作，不经过 Java 堆
     *
     * 失败不阻断导入，退回按原始大小传输（极老备份可能没有 LogModel 表，通常也不大）。
     */
    private fun shrinkDatabaseForImport(dbFile: File) {
        if (!dbFile.exists()) return
        runCatching {
            SQLiteDatabase.openDatabase(
                dbFile.absolutePath,
                null,
                SQLiteDatabase.OPEN_READWRITE
            ).use { db ->
                db.execSQL("DELETE FROM LogModel")
                db.execSQL("VACUUM")
            }
            Logger.d("导入前数据库瘦身完成: ${dbFile.length()} bytes")
        }.onFailure {
            Logger.w("导入前数据库瘦身失败，将按原始大小传输: ${it.message}")
        }
    }

    /**
     * 备份用户配置（shared_prefs）
     *
     * 只备份用户不可再生的配置数据。cache、app_webview、日志等运行时数据由 App 自动重建，
     * 一并打包只会让备份文件无限膨胀，故不再全量快照 dataDir。
     */
    private fun backupDataDirectory(backupDir: File) {
        try {
            val prefsDir = File(context.applicationInfo.dataDir, "shared_prefs")
            if (!prefsDir.exists()) {
                Logger.w("shared_prefs 不存在，跳过配置备份")
                return
            }

            // 保留 data_dir/shared_prefs 结构，兼容旧备份的恢复路径
            val targetPrefsDir = File(backupDir, "data_dir/shared_prefs")
            prefsDir.copyRecursively(targetPrefsDir, overwrite = true)
            Logger.d("配置备份完成: ${targetPrefsDir.absolutePath}")
        } catch (e: Exception) {
            Logger.w("配置备份失败: ${e.message}")
        }
    }

    /**
     * 恢复用户配置（shared_prefs）
     *
     * 只覆盖 shared_prefs，不再清空并重建整个 dataDir——那样会删除 App 当前的运行时目录，
     * 既危险又无必要。旧备份的 data_dir 里也含 shared_prefs，同样能正确恢复。
     */
    private fun restoreDataDirectory(backupDir: File) {
        try {
            val snapshotPrefsDir = File(backupDir, "data_dir/shared_prefs")
            if (!snapshotPrefsDir.exists()) {
                Logger.w("备份中没有 shared_prefs，尝试兼容恢复旧版配置文件")
                restoreLegacyPreferences(backupDir)
                return
            }

            val targetPrefsDir = File(context.applicationInfo.dataDir, "shared_prefs")
            targetPrefsDir.mkdirs()
            snapshotPrefsDir.copyRecursively(targetPrefsDir, overwrite = true)
            Logger.d("配置恢复完成")
        } catch (e: Exception) {
            Logger.w("配置恢复失败: ${e.message}")
        }
    }

    /**
     * 兼容旧备份：仅恢复 settings.xml
     */
    private fun restoreLegacyPreferences(backupDir: File) {
        try {
            val backupPrefsFile = File(backupDir, "settings.xml")

            if (backupPrefsFile.exists()) {
                val prefsDir = File(context.applicationInfo.dataDir, "shared_prefs")
                if (!prefsDir.exists()) {
                    prefsDir.mkdirs()
                }

                val settingsFile = File(prefsDir, "settings.xml")
                backupPrefsFile.copyTo(settingsFile, overwrite = true)

                // 清理备份文件
                backupPrefsFile.delete()

                Logger.d("配置文件恢复完成")
            } else {
                Logger.w("备份中无配置文件，跳过恢复")
            }
        } catch (e: Exception) {
            Logger.w("配置文件恢复失败: ${e.message}")
        }
    }
}
