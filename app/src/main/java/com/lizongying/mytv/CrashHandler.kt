package com.lizongying.mytv

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File

/**
 * 崩溃日志本地化：写入 filesDir/crash/，只保留最近 5 份，随日志打印设备信息。
 * 不涉任何上报（无外部依赖）。
 */
class CrashHandler(private val context: Context) : Thread.UncaughtExceptionHandler {

    private val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

    override fun uncaughtException(t: Thread, e: Throwable) {
        try {
            val dir = File(context.filesDir, "crash").apply { mkdirs() }
            val file = File(dir, "crash-${System.currentTimeMillis()}.log")
            file.writeText(
                buildString {
                    append("time=").append(Utils.getDateFormat("yyyy-MM-dd HH:mm:ss")).append('\n')
                    append("thread=").append(t.name).append('\n')
                    append("device=").append(Build.MANUFACTURER).append('/')
                        .append(Build.MODEL).append(" sdk=").append(Build.VERSION.SDK_INT).append('\n')
                    append(Log.getStackTraceString(e))
                }
            )
            // 只保留最近 5 份
            dir.listFiles()?.sortedByDescending { it.name }?.drop(5)?.forEach { it.delete() }
            Log.e(TAG, "crash saved: ${file.name}")
        } catch (_: Exception) {
        }
        defaultHandler?.uncaughtException(t, e)
    }

    companion object {
        private const val TAG = "CrashHandler"
    }
}
