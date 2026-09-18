package com.lizongying.mytv

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request as HttpReq
import okio.Buffer
import java.io.File
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 线路探活 / 测速。
 *
 * 探测分**两级**（对应 `源可用性与质量专题.md` 的 L3 与 L4）：
 * - L3 可达：连接成功、HTTP 响应成功；
 * - L4 可播：HLS 线路继续取 playlist，并验证「首个分片能下载」。
 *
 * 只有验证到分片（[LEVEL_SEGMENT]）的线路才会排在所有线路最前面。
 *
 * 为什么必须做第二级：早期只判 HTTP 200，而 200 只说明「列表能下载」，
 * 不代表「能播」——错误页、空列表、分片 403 都可能返回 200/302；
 * 反过来，跨洲线路要经 1~4 次 302 跳转，超时定太紧又会把好线路误判成失败。
 *
 * 探测范围由调用方决定——只探"当前分组"，用户切到新分组时按需补探，
 * 避免每次启动对全表上千条线路发请求。未探测过的线路不参与排序，也不会被误标"不可用"。
 */
object ChannelProbe {
    private const val TAG = "ChannelProbe"
    private const val FILE_NAME = "probe.json"
    private const val UA = "Mozilla/5.0 (Linux; Android) my-tv"

    /** 探测专用客户端：单例复用连接池与 TLS 会话（每轮新建会把握手成果全部丢掉） */
    private val probeClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            // 跨洲线路实测 connect 可达 1.8s，且要经 1~4 次 302 跳转：
            // 超时定太紧会把好线路误判为"探测失败"，这里给足余量
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    /** 落盘专用单线程：串行化写 probe.json，并发写同一文件会交错损坏 */
    private val persistExecutor = Executors.newSingleThreadExecutor()

    /**
     * 单次最多探测线路数。
     * 两级探测每条线路要发 1~3 个请求，上限用于兜底，防止超大分组把请求打爆。
     */
    private const val MAX_TARGETS = 200

    /** 并发数：两级探测请求数变多，适度提高以维持整体时长 */
    private const val CONCURRENCY = 10

    /** 整轮总超时：覆盖「最多 200 条 × 平均 2 个请求」的量 */
    private const val OVERALL_TIMEOUT_SEC = 60L

    /** 同一线路在此期间内不重复探测：浏览分组时来回切不会反复发请求 */
    private const val REPROBE_INTERVAL_MS = 10 * 60_000L

    /**
     * 响应体最大读取量。
     * 超过这个体积不可能是播放列表，同时也避免把大文件（点播 mp4/flv）整个读进内存。
     */
    private const val PLAYLIST_MAX_BYTES = 128 * 1024L

    /** master playlist 里的 `RESOLUTION=1920x1080` */
    private val RESOLUTION_REGEX = Regex("RESOLUTION=(\\d+)[xX](\\d+)")

    /** master playlist 里的 `BANDWIDTH=8000000` */
    private val BANDWIDTH_REGEX = Regex("BANDWIDTH=(\\d+)")

    // ---------------- 排序权重（越小越优先） ----------------

    /** 未探测过：取中间值，既不优先也不淘汰 */
    private const val UNKNOWN = 100_000

    /** 仅验证到"可达"（非 HLS 直连流，或分片验证不可用）：排在已完整验证的线路之后 */
    private const val REACHABLE_ONLY = 500_000

    /** 探测失败：排最后 */
    private const val FAILED = 1_000_000

    /** 端到端耗时上限，防止极端值跨越权重区间 */
    private const val MAX_LATENCY = 400_000

    // ---------------- 探测深度 ----------------

    /** 连接或响应失败 */
    private const val LEVEL_FAIL = 0

    /** 可达，但未验证到分片（非 HLS 流，或分片探测不可用） */
    private const val LEVEL_REACHABLE = 1

    /** playlist 有效且首个分片可取：可信度最高 */
    private const val LEVEL_SEGMENT = 2

    private val mainHandler = Handler(Looper.getMainLooper())

    data class Score(
        val ok: Boolean,
        /** 端到端耗时：从发起请求到「拿到分片响应」，不是首包延迟 */
        val latencyMs: Long,
        val at: Long,
        /** 探测深度（[LEVEL_FAIL] / [LEVEL_REACHABLE] / [LEVEL_SEGMENT]） */
        val level: Int = LEVEL_FAIL,
        /**
         * 302 之后的真实来源 host。
         *
         * 入口地址常常只是调度器：实测一个频道的 8 条线路来自 8 个域名，
         * 重定向后却汇聚到同一批服务器。只有它才能识别出这种情况。
         * 用可空类型——Gson 反序列化旧记录时该字段为 null。
         */
        val finalHost: String? = null,
        /** master playlist 实测到的视频高度（0 = 未取到；单码率源拿不到 RESOLUTION 属正常） */
        val height: Int = 0,
        /** master playlist 实测到的码率 bps（0 = 未取到） */
        val bandwidth: Int = 0,
    )

    @Volatile
    private var scores: Map<String, Score> = emptyMap()

    /**
     * 是否存在"探测可达"的线路。
     * [isChannelDown] 会在列表逐行绑定时被调用，早期每次都遍历整张结果表；
     * 这里改为结果更新时算一次，判定退化成 O(频道线路数)。
     */
    @Volatile
    private var anyOk = false

    private var loaded = false

    /** 统一更新结果与派生标记，避免两处不同步 */
    private fun updateScores(newScores: Map<String, Score>) {
        scores = newScores
        anyOk = newScores.values.any { it.ok }
    }

    /** 该线路是否属于"本机没有 IPv6 出口"的情况（此时不探、不判、不记） */
    private fun noIpv6For(url: String): Boolean = Utils.isIpv6Url(url) && !Utils.hasIpv6

    /** 清空探测结果与落盘文件（供"重新检测线路"使用） */
    fun reset() {
        updateScores(emptyMap())
        runCatching {
            MyApplication.instance?.let { File(it.filesDir, FILE_NAME).delete() }
        }
        Log.i(TAG, "probe results cleared")
    }

    /**
     * 正在探测中的地址。
     * 缓存渲染与后台刷新几乎同时触发探测时，靠它避免同一批线路被重复请求。
     */
    private val inFlight: MutableSet<String> =
        Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    /** 是否已有探测在跑：约束请求速率，避免快速切分组时同时发起多轮 */
    @Volatile
    private var running = false

    /**
     * 探测进行中时收到的新请求：**累积**而不是覆盖。
     * 覆盖式会在快速切分组时把中间的请求丢掉，那些分组就一直是"未探测"。
     */
    private val pendingChannels: MutableList<TV> =
        Collections.synchronizedList(mutableListOf())

    @Volatile
    private var pendingOnComplete: (() -> Unit)? = null

    /**
     * 排序权重（越小越优先）。
     * 已完整验证（到分片）的线路按端到端耗时升序排在最前；仅可达的次之；未探测再次；失败最后。
     */
    fun sortKey(url: String): Int {
        val s = ensureLoaded()[url] ?: return UNKNOWN
        if (!s.ok) return FAILED
        val latency = s.latencyMs.coerceIn(0L, MAX_LATENCY.toLong()).toInt()
        return if (s.level >= LEVEL_SEGMENT) latency else REACHABLE_ONLY + latency
    }

    /**
     * 线路探测状态文案（供线路列表展示）。
     * 必须区分"没探过"、"探了不通"和"验证到分片"——
     * 早期三者都返回 null 或同一个值，线路列表里会满屏"未探测"，看不出哪些线路是真的连不上。
     */
    fun label(url: String): String {
        val s = ensureLoaded()[url] ?: return "未探测"
        if (!s.ok) return "探测失败"
        // ">Nms" 表示只验证到可达、未验证到分片（耗时只反映 playlist 那一段）
        return if (s.level >= LEVEL_SEGMENT) "${s.latencyMs}ms" else ">${s.latencyMs}ms"
    }

    /**
     * 探活得到的真实来源 host（302 之后）。
     * 未探过、或本来就无重定向时返回 null，调用方应回退到入口 host。
     */
    fun finalHostOf(url: String): String? =
        ensureLoaded()[url]?.finalHost?.takeIf { it.isNotBlank() }

    /** 探活实测到的视频高度；0 表示未知（单码率源拿不到 RESOLUTION 属正常） */
    fun heightOf(url: String): Int = ensureLoaded()[url]?.height ?: 0

    /** 该线路是否已经探测过（供后台补探挑选目标） */
    fun isProbed(url: String): Boolean = ensureLoaded().containsKey(url)

    /**
     * 记录**播放实测**到的分辨率（来自播放器 `onVideoSizeChanged`）。
     *
     * 探活只能从 master playlist 取 `RESOLUTION`，而多数源是单码率列表、拿不到；
     * 播放器给出的是真实分辨率，覆盖所有能播的线路——**画质维度最可靠的来源**。
     *
     * 「播出来了」也是比探活更强的可用性证据，因此顺带把 `ok` 置为 true、
     * 档位提到 [LEVEL_SEGMENT]（播放成功意味着分片一定取得到）。
     */
    @Synchronized
    fun recordPlaybackSize(url: String, width: Int, height: Int) {
        if (url.isBlank() || height <= 0) return
        val existing = ensureLoaded()[url]
        // 已经完全一致时不写盘（该回调可能被多次触发）
        if (existing != null && existing.ok && existing.height == height &&
            existing.level >= LEVEL_SEGMENT
        ) {
            return
        }
        val map = HashMap(ensureLoaded())
        map[url] = if (existing == null) {
            Score(
                ok = true,
                latencyMs = 0,
                at = System.currentTimeMillis(),
                level = LEVEL_SEGMENT,
                height = height,
            )
        } else {
            existing.copy(
                ok = true,
                level = maxOf(existing.level, LEVEL_SEGMENT),
                height = height,
                at = System.currentTimeMillis(),
            )
        }
        updateScores(map)
        persistAsync()
        Log.i(TAG, "playback size recorded: ${width}x$height $url")
    }

    /** 探测是否判定"可用"。注意 false 也可能是"未探测"，需用 [isProbed] 区分 */
    fun isOk(url: String): Boolean = ensureLoaded()[url]?.ok == true

    /** 是否已验证到分片（可信度最高的一档） */
    fun isVerified(url: String): Boolean =
        ensureLoaded()[url]?.let { it.ok && it.level >= LEVEL_SEGMENT } == true

    /**
     * 频道是否"整组线路都不可用"。
     * 未探测过、或本轮探测整体不可达（通常是本机网络问题）时返回 false，避免误标。
     */
    fun isChannelDown(urls: List<String>): Boolean {
        if (urls.isEmpty()) return false
        val map = ensureLoaded()
        // 一条都不通 → 判定为本机网络异常，不做标记
        if (!anyOk) return false
        if (urls.any { map[it]?.ok == true }) return false
        // 必须每条线路都探测过且都是失败，才认定该频道不可用
        return urls.all { map.containsKey(it) }
    }

    /** 预热：在 IO 线程把结果读进内存，避免首次列表绑定在主线程做磁盘 IO */
    fun warmUp() {
        ensureLoaded()
    }

    @Synchronized
    private fun ensureLoaded(): Map<String, Score> {
        if (loaded) return scores
        loaded = true
        val ctx = MyApplication.instance ?: return scores
        try {
            val f = File(ctx.filesDir, FILE_NAME)
            if (f.exists()) {
                val type = object : TypeToken<Map<String, Score>>() {}.type
                val restored = Gson().fromJson<Map<String, Score>>(f.readText(), type) ?: emptyMap()
                // 丢弃过期记录：换源或被屏蔽的地址会永久残留，避免文件无限增长
                val cutoff = System.currentTimeMillis() - 7L * 24 * 3600_000
                updateScores(restored.filterValues { it.at > cutoff })
            }
        } catch (e: Exception) {
            Log.e(TAG, "load probe result failed", e)
        }
        return scores
    }

    /**
     * 后台探测指定频道的线路（范围由调用方决定，不再全表扫描）。
     * 单线路频道也探——否则无法判断"整组线路都不可用"。
     * [onComplete] 在主线程回调，用于探测完成后刷新对应分组的标记。
     */
    fun probeAsync(channels: List<TV>, onComplete: (() -> Unit)? = null) {
        if (running) {
            // 已有探测在跑：先累积，等本轮结束后补跑，避免请求堆叠
            pendingChannels.addAll(channels)
            pendingOnComplete = onComplete ?: pendingOnComplete
            Log.i(TAG, "probe queued: ${channels.size} channels (pending=${pendingChannels.size})")
            return
        }
        val known = ensureLoaded()
        val now = System.currentTimeMillis()
        val targets = channels.asSequence()
            .flatMap { it.videoUrl.asSequence() }
            .distinct()
            // 本机没有 IPv6 出口时跳过 IPv6 线路：
            // 它们必然探测失败，但失败原因是设备能力而不是线路本身，
            // 记进去会让整批 IPv6 频道被误标"暂不可用"、并被永久降权。
            .filter { url -> !noIpv6For(url) }
            // 刚探过、或正在探测中的不再重复发起
            .filter { url -> known[url]?.let { now - it.at > REPROBE_INTERVAL_MS } ?: true }
            .filter { url -> !inFlight.contains(url) }
            .take(MAX_TARGETS)
            .toList()
        if (targets.isEmpty()) {
            Log.i(TAG, "probe skipped: ${channels.size} channels probed recently")
            // 即使"无需探测"也要回调：调用方可能正等它刷新界面
            //（例如线路列表打开后想显示最新延迟，否则会一直停在"未探测"）
            if (onComplete != null) mainHandler.post(onComplete)
            return
        }
        inFlight.addAll(targets)
        running = true

        Thread {
            try {
                val client = probeClient
                val pool = Executors.newFixedThreadPool(CONCURRENCY)
                // 只装本轮结果：丢弃判定必须基于"本轮"，
                // 否则历史里已有的 ok 会掩盖"这一轮其实全失败"，断网时反而把整表标死
                val probed = ConcurrentHashMap<String, Score>()
                val latch = CountDownLatch(targets.size)
                for (url in targets) {
                    pool.execute {
                        try {
                            probed[url] = probe(client, url)
                        } catch (e: Exception) {
                            probed[url] = Score(false, 0, System.currentTimeMillis())
                            Log.e(TAG, "probe error: $url", e)
                        } finally {
                            latch.countDown()
                        }
                    }
                }
                try {
                    latch.await(OVERALL_TIMEOUT_SEC, TimeUnit.SECONDS)
                } catch (_: InterruptedException) {
                }
                pool.shutdownNow()
                val okCount = probed.values.count { it.ok }
                val verifiedCount = probed.values.count { it.level >= LEVEL_SEGMENT }
                // 本轮几乎全不可达 → 更像本机网络异常而不是"这批线路全死了"，
                // 丢弃本轮结果，免得一次断网就把整表标成"暂不可用"。
                //
                // 阈值取 10%：公共源本身经常就只有一两成线路可用（已用 PC 端复测确认），
                // 门槛定高了反而会把正常结果也丢掉，排序功能就形同失效。
                if (okCount == 0 || okCount * 10 < probed.size) {
                    Log.w(
                        TAG,
                        "probe round looks like a local network problem " +
                                "(ok=$okCount/${probed.size}), discard"
                    )
                } else {
                    val merged = HashMap(ensureLoaded())
                    merged.putAll(probed)
                    updateScores(merged)
                    persist()
                    Log.i(
                        TAG,
                        "probed ${probed.size}/${targets.size} lines, " +
                                "ok=$okCount, verified=$verifiedCount"
                    )
                }
                if (onComplete != null) mainHandler.post(onComplete)
            } finally {
                inFlight.removeAll(targets)
                running = false
                // 探测期间攒下的请求补跑一轮
                val queued = synchronized(pendingChannels) {
                    val copy = pendingChannels.toList()
                    pendingChannels.clear()
                    copy
                }
                if (queued.isNotEmpty()) {
                    val callback = pendingOnComplete
                    pendingOnComplete = null
                    probeAsync(queued, callback)
                }
            }
        }.start()
    }

    // ---------------- 两级探测 ----------------

    /**
     * 探测一条线路：
     * 1. 请求 URL（自动跟随 302 链）；
     * 2. 响应成功且是 HLS playlist → 取第一个分片，验证分片可取（[LEVEL_SEGMENT]）；
     * 3. 响应成功但不是 HLS（flv/ts 直连等）→ 视为可达（[LEVEL_REACHABLE]）；
     * 4. 其余情况视为失败。
     *
     * 注意"误杀"比"漏判"更伤：分片验证失败时降级为可达而不是失败——
     * 因为分片取不到也可能是探测方式的问题（服务器不支持 Range），不能据此判死。
     */
    private fun probe(client: OkHttpClient, url: String): Score {
        val start = System.currentTimeMillis()
        fun elapsed() = System.currentTimeMillis() - start

        val playlist = openPlaylist(client, url)
            ?: return Score(false, elapsed(), System.currentTimeMillis(), LEVEL_FAIL)

        // 302 之后的真实来源：入口域名往往只是调度器，真正承载流的服务器只有它能反映
        var realHost = playlist.finalUrl?.host()

        val body = playlist.text.trimStart()
        val looksHls = playlist.contentType.contains("mpegurl", true) ||
                url.substringBefore('?').lowercase().endsWith(".m3u8")

        if (!body.startsWith("#EXTM3U")) {
            // 不是播放列表：HLS 期望落空 → 失败；非 HLS 直连流 → 可达
            return if (looksHls) {
                Score(false, elapsed(), System.currentTimeMillis(), LEVEL_FAIL, realHost)
            } else {
                Score(true, elapsed(), System.currentTimeMillis(), LEVEL_REACHABLE, realHost)
            }
        }

        // master playlist → 下钻到具体码率列表，顺带拿到实测画质
        var mediaText = playlist.text
        var base: HttpUrl? = playlist.finalUrl
        var height = 0
        var bandwidth = 0
        if (mediaText.contains("#EXT-X-STREAM-INF", ignoreCase = true)) {
            val info = parseStreamInfo(mediaText)
            height = info.first
            bandwidth = info.second
            val variant = firstUri(base, mediaText)?.toString()
            val variantPlaylist = variant?.let { openPlaylist(client, it) }
            if (variantPlaylist == null) {
                return Score(
                    true, elapsed(), System.currentTimeMillis(), LEVEL_REACHABLE,
                    realHost, height, bandwidth
                )
            }
            mediaText = variantPlaylist.text
            base = variantPlaylist.finalUrl
            realHost = variantPlaylist.finalUrl?.host() ?: realHost
        }

        // media playlist → 第一个分片
        val segment = firstUri(base, mediaText)
            ?: return Score(
                true, elapsed(), System.currentTimeMillis(), LEVEL_REACHABLE,
                realHost, height, bandwidth
            )

        val segmentOk = probeSegment(client, segment.toString())
        return if (segmentOk) {
            Score(
                true, elapsed(), System.currentTimeMillis(), LEVEL_SEGMENT,
                realHost, height, bandwidth
            )
        } else {
            // 分片取不到：可能是不支持 Range，也可能是流真的坏了。保守记为"可达"，
            // 排序上仍会略逊于已验证到分片的线路，但不至于被判"不可用"。
            Score(
                true, elapsed(), System.currentTimeMillis(), LEVEL_REACHABLE,
                realHost, height, bandwidth
            )
        }
    }

    /**
     * 从 master playlist 提取画质：取最大的 RESOLUTION 高度与 BANDWIDTH。
     * 取最大而不是第一条——播放器默认拉最高码率，排序也应反映用户实际会看到的画质。
     */
    private fun parseStreamInfo(text: String): Pair<Int, Int> {
        var height = 0
        var bandwidth = 0
        for (line in text.lineSequence()) {
            if (!line.startsWith("#EXT-X-STREAM-INF", ignoreCase = true)) continue
            RESOLUTION_REGEX.find(line)?.groupValues?.getOrNull(2)?.toIntOrNull()?.let {
                if (it > height) height = it
            }
            BANDWIDTH_REGEX.find(line)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let {
                if (it > bandwidth) bandwidth = it
            }
        }
        return height to bandwidth
    }

    private class Playlist(
        val text: String,
        val finalUrl: HttpUrl?,
        val contentType: String,
    )

    /** 取回播放列表文本；连接失败、HTTP 错误、体积过大（非列表）时返回 null */
    private fun openPlaylist(client: OkHttpClient, url: String): Playlist? {
        return try {
            val request = HttpReq.Builder()
                .url(url)
                .header("User-Agent", UA)
                .build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful && resp.code() != 206) return null
                val body = resp.body() ?: return null
                val length = body.contentLength()
                if (length > PLAYLIST_MAX_BYTES) {
                    // 体积远超播放列表：只证明"可达"，文本部分留空由调用方判定
                    return Playlist("", resp.request().url(), body.contentType()?.toString() ?: "")
                }
                val buffer = Buffer()
                val source = body.source()
                var read = 0L
                while (read < PLAYLIST_MAX_BYTES) {
                    val n = source.read(buffer, PLAYLIST_MAX_BYTES - read)
                    if (n == -1L) break
                    read += n
                }
                Playlist(
                    buffer.readUtf8(),
                    resp.request().url(),
                    body.contentType()?.toString() ?: ""
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "probe open failed: $url (${e.message})")
            null
        }
    }

    /**
     * 取 playlist 里第一个媒体地址：
     * - master（`#EXT-X-STREAM-INF` 后跟 variant 地址）→ 返回 variant；
     * - media（第一个非注释行就是分片）→ 返回分片。
     */
    private fun firstUri(base: HttpUrl?, text: String): HttpUrl? {
        if (base == null) return null
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            return base.resolve(line)
        }
        return null
    }

    /**
     * 验证分片可取：只取前 1KB（Range），避免为探测拉整段视频。
     * 少数服务器忽略 Range 直接返回 200，同样视为可取。
     */
    private fun probeSegment(client: OkHttpClient, url: String): Boolean {
        return try {
            val request = HttpReq.Builder()
                .url(url)
                .header("User-Agent", UA)
                .header("Range", "bytes=0-1023")
                .build()
            client.newCall(request).execute().use { resp ->
                resp.isSuccessful || resp.code() == 206
            }
        } catch (e: Exception) {
            Log.w(TAG, "segment probe failed: $url (${e.message})")
            false
        }
    }

    /** 落盘统一提交到单线程 executor：探测线程与主线程都调它，串行执行才不会写坏文件 */
    private fun persist() {
        val ctx = MyApplication.instance ?: return
        val snapshot = scores
        persistExecutor.execute {
            try {
                File(ctx.filesDir, FILE_NAME).writeText(Gson().toJson(snapshot))
            } catch (e: Exception) {
                Log.e(TAG, "save probe result failed", e)
            }
        }
    }

    /** 异步落盘：供主线程调用（如播放回调记录分辨率），避免在主线程做文件写入 */
    private fun persistAsync() = persist()
}
