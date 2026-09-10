package com.lizongying.mytv

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.lizongying.mytv.models.EPG
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.InputStream
import java.util.zip.GZIPInputStream
import java.net.URL
import java.text.SimpleDateFormat
import java.util.TimeZone

/**
 * XMLTV 格式节目单存储（借鉴 my-tv-0）：
 * - 支持多地址（逗号分隔）逐试、.gz 自动解压
 * - 归一化频道名匹配
 * - 解析结果 JSON 缓存到 filesDir，启动先读缓存
 */
object EpgStore {
    private const val TAG = "EpgStore"
    private var epgMap: Map<String, List<EPG>> = emptyMap()
    private var loaded = false

    fun initCache(context: Context) {
        try {
            val f = File(context.filesDir, "epg_cache.json")
            if (f.exists()) {
                val type = object : TypeToken<Map<String, List<EPG>>>() {}.type
                epgMap = Gson().fromJson(f.readText(), type)
                loaded = epgMap.isNotEmpty()
                Log.i(TAG, "epg cache loaded: ${epgMap.size} channels")
            }
        } catch (e: Exception) {
            Log.e(TAG, "load epg cache failed", e)
        }
    }

    /** 后台拉取并解析节目单：多地址逐试，直到成功（okhttp 自动跟随跨协议重定向） */
    fun updateAsync(urls: List<String>) {
        val candidates = urls.map { it.trim() }.filter { it.isNotBlank() }
        if (candidates.isEmpty()) return
        Thread {
            for (url in candidates) {
                try {
                    val request = okhttp3.Request.Builder()
                        .url(url)
                        .header("User-Agent", "Mozilla/5.0 (Linux; Android) my-tv")
                        .header("Referer", "https://www.yangshipin.cn/")
                        .build()
                    trustAllClient().newCall(request).execute().use { resp ->
                        if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code()}")
                        val bytes = resp.body()?.bytes() ?: throw RuntimeException("empty body")
                        val gz = resp.request().url().toString().endsWith(".gz")
                        val input: InputStream =
                            if (gz) GZIPInputStream(bytes.inputStream()) else bytes.inputStream()
                        val map = parseXmltv(input)
                        input.close()
                        if (map.isNotEmpty()) {
                            epgMap = map
                            loaded = true
                            Log.i(TAG, "epg loaded: ${map.size} channels from $url, keys=${map.keys.take(5)}")
                            File(context().filesDir, "epg_cache.json")
                                .writeText(Gson().toJson(map))
                            return@use
                        } else {
                            Log.i(TAG, "epg parse EMPTY from $url (bytes=${bytes.size})")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "epg update failed: $url", e)
                }
            }
        }.start()
    }

    /** 查找节目单：先按 tvg-id 匹配，再按频道名归一化匹配 */
    fun find(title: String, tvgId: String = ""): List<EPG> {
        if (!loaded) return emptyList()
        if (tvgId.isNotBlank()) {
            epgMap[TVList.canonicalName(tvgId)]?.let { if (it.isNotEmpty()) return it }
        }
        return epgMap[TVList.canonicalName(title)] ?: emptyList()
    }

    /** 信任所有证书的客户端（EPG 站点证书混乱，与 ApiClient 同策略） */
    private fun trustAllClient(): okhttp3.OkHttpClient {
        val trustAll = arrayOf<javax.net.ssl.TrustManager>(
            object : javax.net.ssl.X509TrustManager {
                override fun checkClientTrusted(
                    chain: Array<out java.security.cert.X509Certificate>?,
                    authType: String?
                ) {
                }

                override fun checkServerTrusted(
                    chain: Array<out java.security.cert.X509Certificate>?,
                    authType: String?
                ) {
                }

                override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> =
                    emptyArray()
            }
        )
        val sslContext = javax.net.ssl.SSLContext.getInstance("SSL")
        sslContext.init(null, trustAll, java.security.SecureRandom())
        return okhttp3.OkHttpClient.Builder()
            .sslSocketFactory(
                sslContext.socketFactory,
                trustAll[0] as javax.net.ssl.X509TrustManager
            )
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    private fun context(): Context {
        return MyApplication.instance!!
    }

    private fun parseXmltv(input: InputStream): Map<String, List<EPG>> {
        val now = System.currentTimeMillis() / 1000
        val map = linkedMapOf<String, MutableList<EPG>>()
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(input, null)
        var event = parser.eventType
        var curChannelId = ""
        var curDisplayName = ""
        var curStart = 0L
        var curStop = 0L
        var curTitle = ""
        var curProgrammeChannel = ""
        var inProgramme = false

        while (event != XmlPullParser.END_DOCUMENT) {
            try {
                when (event) {
                    XmlPullParser.START_TAG -> when (parser.name) {
                        "channel" -> {
                            curChannelId = parser.getAttributeValue(null, "id") ?: ""
                            curDisplayName = ""
                        }
                        "display-name" -> if (curChannelId.isNotEmpty() && curDisplayName.isEmpty()) {
                            curDisplayName = parser.nextText().trim()
                        }
                        "programme" -> {
                            inProgramme = true
                            curStart = parseTime(parser.getAttributeValue(null, "start"))
                            curStop = parseTime(parser.getAttributeValue(null, "stop"))
                            curProgrammeChannel = parser.getAttributeValue(null, "channel") ?: ""
                            curTitle = ""
                        }
                        "title" -> if (inProgramme && curTitle.isEmpty()) {
                            curTitle = parser.nextText().trim()
                        }
                    }
                    XmlPullParser.END_TAG -> when (parser.name) {
                        "channel" -> curChannelId = ""
                        "programme" -> {
                            // 过滤已结束的历史节目
                            if (inProgramme && curStop > now && curTitle.isNotEmpty()) {
                                val keys = linkedSetOf(
                                    TVList.canonicalName(curDisplayName),
                                    TVList.canonicalName(curChannelId),
                                    TVList.canonicalName(curProgrammeChannel),
                                )
                                for (key in keys) {
                                    if (key.isNotBlank()) {
                                        map.getOrPut(key) { mutableListOf() }
                                            .add(EPG(curTitle, curStart.toInt()))
                                    }
                                }
                            }
                            inProgramme = false
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "xml parse line issue: ${e.message}")
            }
            event = parser.next()
        }
        map.forEach { (_, v) -> v.sortBy { it.beginTime } }
        return map
    }

    /** 解析 XMLTV 时间：yyyyMMddHHmmss + 可选时区后缀 */
    private fun parseTime(s: String?): Long {
        if (s.isNullOrBlank()) return 0
        val clean = s.replace(" ", "")
        val digits = clean.takeWhile { it.isDigit() }
        val tz = clean.drop(digits.length).trim()
        return try {
            val fmt = SimpleDateFormat("yyyyMMddHHmmss".take(digits.length.coerceAtMost(14)))
            fmt.timeZone = TimeZone.getTimeZone(if (tz.isNotEmpty()) tz else "UTC")
            fmt.parse(digits.padEnd(14, '0').take(14))?.time?.div(1000) ?: 0
        } catch (e: Exception) {
            0
        }
    }
}
