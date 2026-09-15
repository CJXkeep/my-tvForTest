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

    /**
     * 单轮最多预热几条：并发太多会与正在播放的流抢带宽。
     * 取 5 是为了容得下"最近邻居的两条候选线路"（4 个邻居 + 1 条额外候选），
     * 否则多出来的那条会挤掉最远的邻居，覆盖反而变小。
     */
    private const val MAX_TARGETS = 5

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

    /**
     * 在途任务上限。
     *
     * 去重与冷却只挡得住"同一地址"，挡不住"地址各不相同"的洪流：连按换台时调用方
     * 每次提交的都是新地址（相邻台在移动），实测连按 20 次会积压 20+ 个任务、
     * 持续近 9 秒才消化完，而用户早已停手——这些握手正好与他要看的台抢带宽。
     * 超限时直接放弃本轮：这些地址本就是"已经滚过去的台"。
     */
    private const val MAX_IN_FLIGHT = 8

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
        // 先挡洪流再入队：inFlight 只增不减（本轮全部完成才释放），
        // 在途已满时提交只会把"没用的握手"排在"有用的握手"前面
        if (inFlight.size >= MAX_IN_FLIGHT) {
            Log.i(TAG, "preheat skipped: ${inFlight.size} in flight")
            return
        }
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
        // 已知落点时直接请求它：跳转本身也是一轮往返，能省则省
        val target = ResolvedUrl.find(url) ?: url
        try {
            source.open(DataSpec(Uri.parse(target)))
            // open() 之后 uri 已经是跟随跳转之后真正的地址，顺手记下来给播放器直接用
            ResolvedUrl.remember(url, source.uri.toString())
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
        } catch (e: Exception) {
            // 走落点失败多半是它带了时效参数已过期：丢掉，下次回到原始地址重新解析
            if (target != url) ResolvedUrl.forget(url)
            throw e
        } finally {
            runCatching { source.close() }
        }
    }
}
