package com.lizongying.mytv

import android.content.Context
import android.os.Handler
import android.os.Looper
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
import java.util.concurrent.Executors

/**
 * XMLTV 格式节目单存储（借鉴 my-tv-0）：
 * - 支持多地址（逗号分隔）逐试、.gz 自动解压
 * - 归一化频道名匹配
 * - 解析结果 JSON 缓存到 filesDir，启动先读缓存
 */
object EpgStore {
    private const val TAG = "EpgStore"

    /** 抓取/落盘专用单线程：同一时间只跑一个抓取任务，且复用线程（每次裸起 Thread 会并发堆积） */
    private val fetchExecutor = Executors.newSingleThreadExecutor()

    // 后台线程写入、UI 线程读取，需保证可见性
    @Volatile
    private var epgMap: Map<String, List<EPG>> = emptyMap()

    @Volatile
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

    /** 后台拉取并解析节目单：多地址全部尝试并合并，覆盖更全（okhttp 自动跟随跨协议重定向） */
    fun updateAsync(urls: List<String>, onDone: (() -> Unit)? = null) {
        val candidates = urls.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        if (candidates.isEmpty()) {
            if (onDone != null) Handler(Looper.getMainLooper()).post(onDone)
            return
        }
        fetchExecutor.execute {
            val merged = linkedMapOf<String, MutableList<EPG>>()
            var okCount = 0
            for (url in candidates) {
                try {
                    val request = okhttp3.Request.Builder()
                        .url(url)
                        .header("User-Agent", "Mozilla/5.0 (Linux; Android) my-tv")
                        .header("Referer", "https://www.yangshipin.cn/")
                        .build()
                    httpClient.newCall(request).execute().use { resp ->
                        if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code()}")
                        val bytes = resp.body()?.bytes() ?: throw RuntimeException("empty body")
                        val gz = resp.request().url().toString().endsWith(".gz")
                        val input: InputStream =
                            if (gz) GZIPInputStream(bytes.inputStream()) else bytes.inputStream()
                        val map = parseXmltv(input)
                        input.close()
                        if (map.isNotEmpty()) {
                            okCount++
                            mergeInto(merged, map)
                            Log.i(TAG, "epg merged: ${map.size} channels from $url")
                        } else {
                            Log.i(TAG, "epg parse EMPTY from $url (bytes=${bytes.size})")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "epg update failed: $url", e)
                }
            }
            if (okCount > 0) {
                merged.forEach { (_, v) -> v.sortBy { it.beginTime } }
                val result: Map<String, List<EPG>> = merged
                epgMap = result
                loaded = true
                Log.i(TAG, "epg loaded: ${result.size} channels from $okCount/${candidates.size} sources")
                try {
                    File(context().filesDir, "epg_cache.json").writeText(Gson().toJson(result))
                } catch (e: Exception) {
                    Log.e(TAG, "save epg cache failed", e)
                }
            }
            // 无论成功失败都回调：失败时无需刷新，成功时让列表副标题显示节目名
            if (onDone != null) Handler(Looper.getMainLooper()).post(onDone)
        }
    }

    /** 合并多源节目单：同频道按「标题 + 开始时间」去重 */
    private fun mergeInto(
        target: LinkedHashMap<String, MutableList<EPG>>,
        source: Map<String, List<EPG>>,
    ) {
        source.forEach { (key, list) ->
            val dest = target.getOrPut(key) { mutableListOf() }
            for (e in list) {
                if (dest.none { it.title == e.title && it.beginTime == e.beginTime }) {
                    dest.add(e)
                }
            }
        }
    }

    /** 查找节目单：先按 tvg-id 匹配，再按频道名归一化匹配 */
    fun find(title: String, tvgId: String = ""): List<EPG> {
        if (!loaded) return emptyList()
        if (tvgId.isNotBlank()) {
            epgMap[TVList.canonicalName(tvgId)]?.let { if (it.isNotEmpty()) return it }
        }
        return epgMap[TVList.canonicalName(title)] ?: emptyList()
    }

    /**
     * EPG 抓取客户端：使用**系统信任链**（不再信任所有证书），并复用单例。
     *
     * 早期是全局 trust-all（注释为"EPG 站点证书混乱"），代价是 HTTPS 形同虚设、可被中间人替换内容；
     * 且每次抓取都新建一个 client（连接池无法复用）。目标站点均为正规 HTTPS，默认校验即可。
     */
    private val httpClient: okhttp3.OkHttpClient by lazy {
        okhttp3.OkHttpClient.Builder()
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
