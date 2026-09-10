package com.lizongying.mytv

import android.app.Application
import android.content.Context
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager

class MyApplication : Application() {
    private lateinit var displayMetrics: DisplayMetrics

    val configServer = ConfigServer(this)

    companion object {
        var instance: MyApplication? = null
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        displayMetrics = DisplayMetrics()
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
//        windowManager.defaultDisplay.getRealMetrics(displayMetrics)
        windowManager.defaultDisplay.getMetrics(displayMetrics)

        // 局域网远程配置服务（手机/电脑浏览器访问 http://<设备IP>:34567）
        try {
            configServer.start()
            Log.i("MyApplication", "config server started on :${ConfigServer.PORT}")
        } catch (e: Exception) {
            Log.e("MyApplication", "config server start failed: ${e.message}")
        }

        // 崩溃日志本地化
        Thread.setDefaultUncaughtExceptionHandler(CrashHandler(this))
    }

    override fun onTerminate() {
        configServer.stop()
        super.onTerminate()
    }

    fun getDisplayMetrics(): DisplayMetrics {
        return displayMetrics
    }
}
