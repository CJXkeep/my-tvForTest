package com.lizongying.mytv

import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * 线路预热：在用户真正切过去**之前**，把这条线路最贵的准备工作做掉——
 * DNS 解析、TCP/TLS 握手、302 跳转链（实测跨洲线路要经 1~4 跳）。
 *
 * ## 为什么能共享连接
 * 这里用的数据源与播放器是**同一个类**（`DefaultHttpDataSource`，底层 `HttpURLConnection`）。
 * 它的连接池是进程级的 keep-alive 缓存：预热请求读完正文并关闭后，连接会留在池中，
 * 播放器随后的起播请求命中同一 host 时直接复用，省掉握手。
 *
 * 这也是**没有**引入 `media3-datasource-okhttp` 的原因：它会把这个项目的 OkHttp 从 3.x 顶到 4.x，
 * 波及全项目十几处 `code()` / `body()` / `host()` 调用与老设备 TLS 兼容代码，
 * 代价远高于"省几百毫秒握手"的收益。
 *
 * ## 只预热"很可能下一个要看"的频道
 * 全表预热等于把源站请求量放大数十倍。调用方只传相邻台，且本类有冷却与去重保护。
 *
 * 预热只读几 KB 播放列表，不下载分片、不碰解码器，因此不会影响正在播放的频道。
 */
@OptIn(UnstableApi::class)
object StreamPreheat {
    private const val TAG = "StreamPreheat"
    private const val UA = "Mozilla/5.0 (Linux; Android) my-tv"

    /** 单轮最多预热几条：并发太多会与正在播放的流抢带宽 */
    private const val MAX_TARGETS = 4

    /** 并发度：握手是 IO 等待，2 路足够，再多意义有限 */
    private const val CONCURRENCY = 2

    /** 与播放器保持一致，避免预热走通的路径和起播走的不是同一条 */
    private const val CONNECT_TIMEOUT_MS = 6000
    private const val READ_TIMEOUT_MS = 8000

    /**
     * 读取上限。
     * 播放列表只有几 KB，读到远超这个体积说明不是列表（点播 mp4 等），直接放弃。
     */
    private const val MAX_PREHEAT_BYTES = 64 * 1024L

    /** 同一地址的预热冷却：连接池保活期内重复预热是白做 */
    private const val COOLDOWN_MS = 60_000L

    private val executor = Executors.newFixedThreadPool(CONCURRENCY)

    /** 正在预热中的地址，避免同一地址被反复提交 */
    private val inFlight: MutableSet<String> =
        Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    /** 上次预热时间：用于冷却判断 */
    private val warmedAt = ConcurrentHashMap<String, Long>()

    /**
     * 预热一批地址（内部走后台线程，调用方可直接在主线程调用）。
     * 自动去重、限流，并跳过刚预热过的地址。
     */
    fun warm(urls: List<String>) {
        if (urls.isEmpty()) return
        val now = System.currentTimeMillis()
        val targets = urls.asSequence()
            .filter { it.isNotBlank() }
            .distinct()
            .filter { url -> now - (warmedAt[url] ?: 0L) > COOLDOWN_MS }
            .filter { url -> inFlight.add(url) }
            .take(MAX_TARGETS)
            .toList()
        if (targets.isEmpty()) return
        Log.i(TAG, "preheat ${targets.size} line(s)")
        for (url in targets) {
            try {
                executor.execute {
                    try {
                        val start = System.currentTimeMillis()
                        warmOne(url)
                        // 记录耗时：预热失败会被下面的 catch 记成 failed，
                        // 两者都没有就意味着预热压根没被调用（先查这个再查实现）
                        Log.i(TAG, "preheated in ${System.currentTimeMillis() - start}ms: $url")
                    } catch (e: Exception) {
                        Log.d(TAG, "preheat failed: $url (${e.message})")
                    } finally {
                        warmedAt[url] = System.currentTimeMillis()
                        inFlight.remove(url)
                    }
                }
            } catch (e: Exception) {
                // 线程池已关闭等情况：不能把异常抛回调用方（可能在主线程）
                inFlight.remove(url)
                Log.d(TAG, "preheat submit failed: $url (${e.message})")
            }
        }
    }

    /**
     * 预热单条地址。
     *
     * 必须**把正文读完**再关闭：读满之前关闭会让 `HttpURLConnection` 丢弃这条连接，
     * 那样播放器就没得复用——等于白预热。体积超过上限时放弃
     * （说明不是播放列表，硬读完只是浪费带宽）。
     */
    private fun warmOne(url: String) {
        val source = DefaultHttpDataSource.Factory()
            .setUserAgent(UA)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(CONNECT_TIMEOUT_MS)
            .setReadTimeoutMs(READ_TIMEOUT_MS)
            .createDataSource()
        try {
            source.open(DataSpec(Uri.parse(url)))
            val buffer = ByteArray(8 * 1024)
            var total = 0L
            while (true) {
                val n = source.read(buffer, 0, buffer.size)
                if (n < 0) break
                total += n
                if (total > MAX_PREHEAT_BYTES) {
                    Log.d(TAG, "preheat aborted (over limit): $url")
                    break
                }
            }
        } finally {
            runCatching { source.close() }
        }
    }
}
