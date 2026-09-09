package com.lizongying.mytv

import android.content.res.Resources
import android.os.Build
import android.util.TypedValue
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class TimeResponse(val data: TimeData) {
    data class TimeData(val t: String)
}

object Utils {
    private var between: Long = 0

    fun getDateFormat(format: String): String {
        val sdf = SimpleDateFormat(format, Locale.CHINA)
        // 时间显示强制使用北京时间，不受设备时区影响
        sdf.timeZone = TimeZone.getTimeZone("Asia/Shanghai")
        return sdf.format(Date(System.currentTimeMillis() - between))
    }

    fun getDateTimestamp(): Long {
        return (System.currentTimeMillis() - between) / 1000
    }

    fun setBetween(currentTimeMillis: Long) {
        between = System.currentTimeMillis() - currentTimeMillis
    }

    suspend fun init() {
        // 多时间源逐试，全部失败时保持设备本地时钟（between = 0）
        var currentTimeMillis = -1L
        withContext(Dispatchers.IO) {
            for (url in listOf(
                "https://api.m.taobao.com/rest/api3.do?api=mtop.common.getTimestamp",
                "https://f.m.suning.com/api/ct.do",
                "https://ip.ddnspod.com/timestamp",
            )) {
                try {
                    val client = okhttp3.OkHttpClient.Builder()
                        .connectTimeout(1, java.util.concurrent.TimeUnit.SECONDS)
                        .readTimeout(2, java.util.concurrent.TimeUnit.SECONDS).build()
                    val request = okhttp3.Request.Builder().url(url).build()
                    client.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            // 各时间源返回格式不同，统一提取响应中的 13 位毫秒时间戳
                            val body = response.body()?.string()
                            val m = Regex("\\d{13}").find(body ?: "")
                            if (m != null) {
                                currentTimeMillis = m.value.toLong()
                                return@withContext
                            }
                        }
                    }
                } catch (e: Exception) {
                    println("timestamp source failed: $url")
                }
            }
        }
        between = if (currentTimeMillis > 0) {
            System.currentTimeMillis() - currentTimeMillis
        } else {
            0
        }
    }

    fun dpToPx(dp: Float): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, dp, Resources.getSystem().displayMetrics
        ).toInt()
    }

    fun dpToPx(dp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, dp.toFloat(), Resources.getSystem().displayMetrics
        ).toInt()
    }

    fun pxToDp(px: Float): Int {
        val scale = Resources.getSystem().displayMetrics.density
        return (px / scale).toInt()
    }

    fun pxToDp(px: Int): Int {
        val scale = Resources.getSystem().displayMetrics.density
        return (px / scale).toInt()
    }

    fun isTmallDevice() = Build.MANUFACTURER.equals("Tmall", ignoreCase = true)
}