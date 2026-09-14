package com.lizongying.mytv

import android.content.res.Resources
import android.util.TypedValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object Utils {
    /** 本地时钟与标准时间的差值；后台线程写入、UI 线程读取，需要可见性保证 */
    @Volatile
    private var between: Long = 0

    /**
     * 本机是否有 IPv6 出口。
     *
     * 判断依据是"存在非链路本地的 IPv6 地址"——只有 fe80:: 的链路本地地址不代表能出公网，
     * 而很多设备（模拟器、部分盒子）确实只有 IPv4 出口。
     *
     * 意义：没有 IPv6 出口时，所有 IPv6 线路必然连不上，
     * 这是**设备能力**问题，不能据此判定"线路坏了"（见 ChannelProbe / LineHealth 的相应过滤）。
     */
    val hasIpv6: Boolean by lazy {
        runCatching {
            java.net.NetworkInterface.getNetworkInterfaces().asSequence()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.asSequence() }
                .any {
                    it is java.net.Inet6Address && !it.isLoopbackAddress &&
                            !it.isLinkLocalAddress && !it.isSiteLocalAddress
                }
        }.getOrDefault(false)
    }

    /** 是否是 IPv6 地址的 URL */
    fun isIpv6Url(url: String): Boolean =
        url.startsWith("http://[", true) || url.startsWith("https://[", true)

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

    /**
     * 网络时间校准客户端。
     * 复用连接池：早期在循环里对每个时间源各新建一个 client（要试 3 个源，就建 3 次）。
     */
    private val timeClient: okhttp3.OkHttpClient by lazy {
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(1, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(2, java.util.concurrent.TimeUnit.SECONDS)
            .build()
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
                    val request = okhttp3.Request.Builder().url(url).build()
                    timeClient.newCall(request).execute().use { response ->
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
}
