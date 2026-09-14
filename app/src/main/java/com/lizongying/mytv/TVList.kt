package com.lizongying.mytv

import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.lizongying.mytv.models.ProgramType
import okhttp3.OkHttpClient
import okhttp3.Request as HttpReq
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 频道数据源加载器。
 *
 * 支持两种订阅格式：
 * 1. M3U/M3U8（#EXTINF，tvg-logo 与 group-title 属性可选）
 * 2. TXT（`分组名,#genre#` 声明分组，随后每行 `频道名,播放地址`）
 *
 * 订阅地址支持配置多个（用逗号/分号/竖线分隔），同名频道自动合并为多线路。
 * 未配置远程源或全部加载失败时，使用内置兜底频道。
 */
object TVList {
    private const val TAG = "TVList"

    /** 单个订阅源的整体超时（含镜像重试），避免慢源拖垮启动 */
    private const val SOURCE_DEADLINE_MS = 12_000L

    /**
     * 单次下载的总时长上限。
     *
     * `readTimeout` 只能约束"两次数据之间的间隔"：像 suxuang 这种"连得上但极慢"的源
     * 不会触发它（实测 25s 才拿到 97KB），结果每次冷启动都被拖到整体截止时间。这里硬性截断。
     */
    private const val SOURCE_CALL_TIMEOUT_MS = 8_000L

    /** 裸 IP 判定（用于线路的网段打散） */
    private val IPV4_REGEX = Regex("\\d{1,3}(\\.\\d{1,3}){3}")

    /** 订阅源并发拉取上限 */
    private const val MAX_PARALLEL_SOURCES = 4

    /** "测试源"整体截止时间（含镜像重试） */
    private const val TEST_DEADLINE_MS = 30_000L

    /** 各订阅源自带的 EPG 地址（x-tvg-url），按加载顺序 */
    var epgUrls: List<String> = emptyList()
        private set

    /**
     * 最近一次远程加载是否**所有订阅源都成功**。
     * 部分源失败时拿到的是残缺列表，调用方据此判断要不要用它覆盖已有的完整列表。
     */
    @Volatile
    var lastLoadComplete: Boolean = true
        private set

    /** 内置兜底频道（fengshows 源，实测可用） */
    fun builtin(): Map<String, List<TV>> = linkedMapOf(
        "港澳台" to listOf(
            fTV(
                "凤凰卫视资讯台",
                "7c96b084-60e1-40a9-89c5-682b994fb680",
                "http://c1.fengshows-cdn.com/a/2021_22/79dcc3a9da358a3.png"
            ),
            fTV(
                "凤凰卫视中文台",
                "f7f48462-9b13-485b-8101-7b54716411ec",
                "http://c1.fengshows-cdn.com/a/2021_22/ede3d9e09be28e5.png"
            ),
            fTV(
                "凤凰卫视香港台",
                "15e02d92-1698-416c-af2f-3e9a872b4d78",
                "http://c1.fengshows-cdn.com/a/2021_23/325d941090bee17.png"
            ),
        ),
    )

    private fun fTV(title: String, pid: String, logo: String) = TV(
        0,
        title,
        "",
        listOf(),
        "港澳台",
        logo,
        pid,
        "",
        ProgramType.F,
        false,
        mustToken = false
    )

    /** 加载频道列表：远程 → 本地缓存 → 内置源，逐级回退 */
    fun load(): Map<String, List<TV>> {
        val urls = sources()
        if (urls.isEmpty()) {
            Log.i(TAG, "no remote source, use builtin")
            return builtinGroups()
        }
        return loadRemote(urls) ?: loadCached() ?: builtinGroups()
    }

    /** 仅尝试远程拉取（供缓存优先渲染后的后台刷新使用）；未配置或全部失败返回 null */
    fun loadRemote(): Map<String, List<TV>>? {
        val urls = sources()
        if (urls.isEmpty()) return null
        return loadRemote(urls)
    }

    /** 仅读本地缓存（含归一化）；无缓存返回 null */
    fun loadCached(): Map<String, List<TV>>? {
        val cached = ChannelCache.load() ?: return null
        Log.i(TAG, "use cached channels: ${cached.size} groups")
        // 缓存也要重新归一化：离线时收藏/编号变更同样生效
        finalize(cached)
        return cached
    }

    /**
     * 默认订阅源：用户未显式配置时使用。
     * 均为实测可直连、台数较多的公开聚合源（用逗号分隔也可混用）。
     */
    private val DEFAULT_SOURCES = listOf(
        // 自动更新、中文台覆盖最全（实测 1619 条，其中 CCTV/卫视 1478 条）
        "https://raw.githubusercontent.com/Guovin/iptv-api/gd/output/result.m3u",
        "https://raw.githubusercontent.com/BigBigGrandG/IPTV-URL/release/Gather.m3u",
        "https://raw.githubusercontent.com/suxuang/myIPTV/refs/heads/main/ipv4.m3u",
        "https://live.zbds.top/tv/iptv4.m3u",
        "https://iptv-org.github.io/iptv/countries/cn.m3u",
    )

    /** 默认订阅源列表（供设置页展示/一键恢复） */
    fun defaultSources(): List<String> = DEFAULT_SOURCES

    /** 一键换源的候选：用户已配置的源 + 内置默认源 */
    private fun sourceCandidates(): List<String> {
        val configured = SP.iptvSourceUrl
            .split(',', ';', '|', '\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        return (configured + DEFAULT_SOURCES).distinct()
    }

    /** 候选源总数（用于判断是否已轮完，避免无意义地反复切换） */
    fun sourceCandidateCount(): Int = sourceCandidates().size

    /**
     * 切到下一个候选数据源并落盘。
     *
     * 用于"源疑似失效"时的一键换源：家里老人不可能去改订阅地址，
     * 所以把"换源"做成一个按键就能完成——逐个轮换候选源，直到找到能播的。
     */
    fun rotateSource(): String {
        val all = sourceCandidates()
        val current = sources().firstOrNull().orEmpty()
        val idx = all.indexOfFirst { it.equals(current, ignoreCase = true) }
        // 优先选**历史成功率最高**的候选，而不是盲目轮换下一个——
        // 公共源里"下一个"经常还是坏的，白白浪费一次换源机会。
        // 只有确实成功过的源才参与竞争；全都没成功过时回退到顺序轮换。
        val candidates = all.filter { !it.equals(current, ignoreCase = true) }
        val best = candidates.maxByOrNull { sourceScore(it) }
        val next = if (best != null && sourceScore(best) > 0) {
            best
        } else {
            all[(idx + 1).mod(all.size)]
        }
        SP.iptvSourceUrl = next
        Log.i(TAG, "rotate source ${idx + 1}/${all.size} -> $next (score=${sourceScore(next)})")
        return sourceLabel(next)
    }

    /** 源的历史健康分：成功率 0~100；无统计返回 -1（不参与"最优"竞争） */
    private fun sourceScore(url: String): Int =
        SourceHealth.snapshot()[url]?.successRate ?: -1

    /** 源的可读标识（域名），用于"已切换到 xxx"这类提示 */
    private fun sourceLabel(url: String): String =
        runCatching { java.net.URI(url).host }.getOrNull()?.removePrefix("www.") ?: url

    /** 生效的订阅源：优先用户配置，留空则用默认源 */
    private fun sources(): List<String> {
        val configured = SP.iptvSourceUrl
            .split(',', ';', '|', '\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        return configured.ifEmpty { DEFAULT_SOURCES }
    }

    /** 内置源（含归一化） */
    private fun builtinGroups(): Map<String, List<TV>> {
        val groups = mutableGroups(builtin())
        finalize(groups)
        return groups
    }

    private fun loadRemote(urls: List<String>): Map<String, List<TV>>? {
        // 每次拉取重置 EPG 源地址，避免多次刷新后累积
        epgUrls = emptyList()

        // 并发拉取各源文本（保持配置顺序），再串行解析，保证分组顺序稳定
        val outcomes = fetchAll(urls)

        val merged = linkedMapOf<String, MutableList<TV>>()
        var okCount = 0
        outcomes.forEachIndexed { index, outcome ->
            val source = urls[index]
            val text = outcome.text
            if (text == null) {
                Log.e(TAG, "source unavailable: $source (${outcome.ms}ms)")
                SourceHealth.record(source, ok = false, ms = outcome.ms)
                return@forEachIndexed
            }
            try {
                // 先解析到独立容器，才能统计"这个源贡献了多少台"
                val single = linkedMapOf<String, MutableList<TV>>()
                parseInto(text, single)
                val channels = single.values.sumOf { it.size }
                single.forEach { (group, list) ->
                    merged.getOrPut(group) { mutableListOf() }.addAll(list)
                }
                okCount++
                SourceHealth.record(
                    source,
                    ok = true,
                    ms = outcome.ms,
                    channels = channels,
                    via = outcome.via,
                )
                Log.i(TAG, "source loaded: $source ($channels channels, ${outcome.ms}ms)")
            } catch (e: Exception) {
                Log.e(TAG, "parse failed: $source", e)
                SourceHealth.record(source, ok = false, ms = outcome.ms)
            }
        }

        lastLoadComplete = okCount == urls.size

        if (merged.isEmpty()) {
            Log.e(TAG, "all remote sources failed")
            return null
        }

        // 远程源在前，内置凤凰台放在最后（观看需求较低）
        val combined = LinkedHashMap<String, MutableList<TV>>()
        (merged.asSequence() + builtin().asSequence()).forEach { (k, v) ->
            combined.getOrPut(k) { mutableListOf() }.addAll(v)
        }
        finalize(combined)
        // 拉取成功后落盘，供断网/源站故障时复用。
        // 但部分源失败时可能只拿到很少的频道，此时不能覆盖掉完整缓存，
        // 否则下次启动只剩这几个源，体验反而变差。
        val newCount = combined.values.sumOf { it.size }
        // 与旧缓存比较时同样只算白名单内的频道：否则开了过滤后新列表必然"小得多"，
        // 会被下面的保护逻辑挡住，缓存永远更新不了
        val oldCount = ChannelCache.load()?.values?.sumOf { v -> v.count { isWanted(it.title) } } ?: 0
        // 判定口径与界面侧保持一致：加载完整就写入；不完整时只有不比旧缓存少才写，
        // 否则一次部分失败的加载会把缓存"越刷越少"（实测从 67 台退化到 42 台）
        if (oldCount == 0 || lastLoadComplete || newCount >= oldCount) {
            ChannelCache.save(combined)
        } else {
            Log.w(TAG, "skip cache save: incomplete and new=$newCount < cached=$oldCount")
        }
        Log.i(
            TAG,
            "remote sources merged: $okCount/${urls.size}, ${combined.size} groups, $newCount channels"
        )
        return combined
    }

    /** 数据源可用性测试（供远程配置页"测试源"使用） */
    fun testSource(url: String): SourceTestResult {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return SourceTestResult(false, 0, 0, "地址为空")
        // 整体截止时间：订阅源较多且多数被墙时，避免"测试源"长时间无响应
        val deadline = System.currentTimeMillis() + TEST_DEADLINE_MS
        for (candidate in expandMirrors(trimmed)) {
            if (System.currentTimeMillis() > deadline) break
            try {
                val tmp = LinkedHashMap<String, MutableList<TV>>()
                parseInto(fetch(candidate), tmp)
                val channels = tmp.values.sumOf { it.size }
                if (channels > 0) {
                    return SourceTestResult(true, tmp.size, channels, "可用", candidate)
                }
            } catch (e: Exception) {
                Log.e(TAG, "test source failed: $candidate", e)
            }
        }
        return SourceTestResult(false, 0, 0, "未解析到频道或无法访问")
    }

    data class SourceTestResult(
        val ok: Boolean,
        val groups: Int,
        val channels: Int,
        val message: String,
        /** 实际成功的地址（可能经过镜像），便于判断原始地址是否被墙 */
        val via: String = "",
    )

    /**
     * GitHub 加速镜像前缀（借鉴 my-tv-0 Utils.getUrls）。
     * 顺序即尝试优先级；实测失效/超时的镜像放到最后，避免每次都先等它超时。
     */
    private val MIRRORS = listOf(
        "https://github.moeyy.xyz/",
        "https://mirror.ghproxy.com/",
        "https://ghproxy.net/",
        "https://gh-proxy.com/",
        "https://ghproxy.cn/",
        "https://ghproxy.click/",
        "https://ghproxy.com/",
        "https://github.moeyy.cn/",
        "https://www.ghproxy.cc/",
        "https://cf.ghproxy.cc/",
        "https://ghp.ci/",
        // 实测连通性差，放最后
        "https://gh.llkk.cc/",
    )

    /**
     * GitHub 链接自动展开镜像候选。
     * 若用户粘贴的地址本身已带镜像前缀（如 `https://ghproxy.net/https://raw.githubusercontent.com/...`），
     * 会先还原成原始 GitHub 地址再统一展开，避免被绑死在单一镜像上。
     */
    private fun expandMirrors(url: String): List<String> {
        var origin = url.trim()
        for (prefix in MIRRORS) {
            if (origin.startsWith(prefix, ignoreCase = true)) {
                origin = origin.removePrefix(prefix).trim()
                break
            }
        }
        if (!origin.startsWith("https://raw.githubusercontent.com") &&
            !origin.startsWith("https://github.com") &&
            !origin.startsWith("http://raw.githubusercontent.com")
        ) {
            return listOf(origin)
        }
        val candidates = mutableListOf<String>()
        // jsdelivr 排第一：实测国内可达性明显好于 raw.githubusercontent.com
        //（raw 已经开始出现整站拉不动的情况，而同一份内容走 jsdelivr 正常）
        jsdelivrUrl(origin)?.let { candidates.add(it) }
        candidates.add(origin)
        candidates.addAll(MIRRORS.map { it + origin })
        return candidates
    }

    /**
     * 把 raw.githubusercontent.com 地址转换为等价的 jsdelivr CDN 地址。
     *
     * 例：`raw.githubusercontent.com/o/r/refs/heads/main/a.m3u` → `cdn.jsdelivr.net/gh/o/r@main/a.m3u`
     *     `raw.githubusercontent.com/o/r/release/x.m3u`      → `cdn.jsdelivr.net/gh/o/r@release/x.m3u`
     *
     * 做不到转换（非 GitHub、路径不合规）时返回 null，由调用方回退到原始地址。
     */
    private fun jsdelivrUrl(origin: String): String? {
        val m = Regex("^https?://raw\\.githubusercontent\\.com/([^/]+)/([^/]+)/(.+)$")
            .find(origin) ?: return null
        val (owner, repo, rest) = m.destructured
        val path = rest.replace(Regex("^refs/(heads|tags)/"), "")
        val slash = path.indexOf('/')
        if (slash <= 0 || slash == path.lastIndex) return null
        val ref = path.substring(0, slash)
        val file = path.substring(slash + 1)
        return "https://cdn.jsdelivr.net/gh/$owner/$repo@$ref/$file"
    }

    /**
     * 并发拉取各订阅源文本：整体耗时约等于"最慢的单个源"而不是所有源之和。
     * 每个源内部仍按顺序尝试镜像，但受 [SOURCE_DEADLINE_MS] 整体截止时间约束。
     */
    /** 单个源的拉取结果：文本 + 实际耗时 + 命中的镜像地址 */
    private class FetchOutcome(val text: String?, val ms: Long, val via: String)

    private fun fetchAll(urls: List<String>): List<FetchOutcome> {
        val results = arrayOfNulls<FetchOutcome>(urls.size)
        val pool = Executors.newFixedThreadPool(urls.size.coerceAtMost(MAX_PARALLEL_SOURCES))
        val latch = CountDownLatch(urls.size)
        val deadlineAt = System.currentTimeMillis() + SOURCE_DEADLINE_MS

        urls.forEachIndexed { index, url ->
            pool.execute {
                val start = System.currentTimeMillis()
                var via = ""
                try {
                    for (candidate in expandMirrors(url)) {
                        if (System.currentTimeMillis() > deadlineAt) {
                            Log.e(TAG, "source deadline exceeded: $url")
                            break
                        }
                        val t0 = System.currentTimeMillis()
                        try {
                            val text = fetch(candidate)
                            via = candidate
                            Log.i(TAG, "source ok in ${System.currentTimeMillis() - t0}ms: $candidate")
                            results[index] =
                                FetchOutcome(text, System.currentTimeMillis() - start, candidate)
                            break
                        } catch (e: Exception) {
                            Log.e(
                                TAG,
                                "load source failed in ${System.currentTimeMillis() - t0}ms: $candidate",
                                e
                            )
                        }
                    }
                    // 所有候选都失败/超时也要落一条记录，否则"这个源在退化"永远看不出来
                    if (results[index] == null) {
                        results[index] = FetchOutcome(null, System.currentTimeMillis() - start, via)
                    }
                } finally {
                    latch.countDown()
                }
            }
        }

        try {
            latch.await(SOURCE_DEADLINE_MS + 3_000L, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
        }
        pool.shutdownNow()
        return results.map { it ?: FetchOutcome(null, 0L, "") }
    }

    /**
     * 订阅源下载客户端（复用连接池 + HTTP 缓存）。
     *
     * 复用：早期每个源、每个镜像候选都新建一个 client，TLS 握手与连接池全部浪费；
     * 缓存：GitHub raw / jsdelivr 都支持 ETag，配合 `noCache()`（每次校验）
     * 可在内容未变时拿到 304，省掉整份列表（约 300~400KB）的下载。
     */
    private val httpClient: OkHttpClient by lazy {
        val builder = OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            // 整个调用（含镜像重定向）的总上限，专门对付"连得上但极慢"的源
            .callTimeout(SOURCE_CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        val cacheDir = MyApplication.instance?.cacheDir
        if (cacheDir != null) {
            runCatching {
                // 8MB 足够放下几份列表的 ETag 副本
                builder.cache(okhttp3.Cache(java.io.File(cacheDir, "http_source"), 8L * 1024 * 1024))
            }.onFailure { Log.w(TAG, "http cache init failed: ${it.message}") }
        }
        builder.build()
    }

    private fun fetch(url: String): String {
        val request = HttpReq.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android) my-tv")
            // 每次都向源站校验：内容未变走 304，变了才真正下载
            .cacheControl(okhttp3.CacheControl.Builder().noCache().build())
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw RuntimeException("HTTP ${response.code()}")
            }
            return response.body()?.string() ?: throw RuntimeException("empty body")
        }
    }

    /** 解析订阅内容并合并进 result（归一化后同名的频道自动折叠为多线路） */
    private fun parseInto(text: String, result: LinkedHashMap<String, MutableList<TV>>) {
        if (text.trimStart().startsWith("[")) {
            parseJson(text, result)
            return
        }
        var group = "其他"
        var pendingTitle: String? = null
        var pendingLogo = ""
        var pendingChno = 0
        var pendingTvgId = ""
        var pendingHeaders: MutableMap<String, String> = mutableMapOf()

        // 频道折叠索引：归一化名 -> (分组, 展示名, VM 所在列表)
        val folded = linkedMapOf<String, Pair<String, String>>()

        fun addChannel(url: String) {
            val title = pendingTitle?.takeIf { it.isNotBlank() } ?: return
            val trimmed = url.trim()
            if (!trimmed.contains("://")) return
            // 过滤非频道条目（如 Guovin 结果中的更新时间戳）
            if (title.matches(Regex("\\d{4}-\\d{2}-\\d{2}.*"))) return
            // 过滤占位/垃圾条目
            if (isJunkTitle(title)) return
            // 过滤点播文件：这类不是直播，混在直播列表里只会拉低整体质量
            if (isVodUrl(trimmed)) return
            // 过滤黑名单域名：第三方聚合/代理地址，不播放也不探活
            if (isBlockedUrl(trimmed)) return
            val key = canonicalName(title)
            val existing = folded[key]
            if (existing != null) {
                val list = result[existing.first] ?: return
                val vm = list.find { it.title == existing.second }
                if (vm != null) {
                    if (!vm.videoUrl.contains(trimmed)) {
                        vm.videoUrl = vm.videoUrl + trimmed
                    }
                    // 记录该线路的画质分：同名多渠道折叠时，用各线路自己的命名/地址判断清晰度
                    val q = lineQuality(title, trimmed)
                    if (q > (vm.urlQuality[trimmed] ?: 0)) {
                        vm.urlQuality = vm.urlQuality + (trimmed to q)
                    }
                    if (vm.chno == 0 && pendingChno > 0) {
                        vm.chno = pendingChno
                    }
                    if (vm.headers.isEmpty() && pendingHeaders.isNotEmpty()) {
                        vm.headers = pendingHeaders.toMap()
                    }
                    if (vm.tvgId.isEmpty() && pendingTvgId.isNotEmpty()) {
                        vm.tvgId = pendingTvgId
                    }
                }
            } else {
                folded[key] = group to title
                val list = result.getOrPut(group) { mutableListOf() }
                list.add(
                    TV(
                        0,
                        title,
                        "",
                        listOf(trimmed),
                        group,
                        pendingLogo,
                        "",
                        "",
                        ProgramType.DIRECT,
                        false,
                        chno = pendingChno,
                        headers = pendingHeaders.toMap(),
                        tvgId = pendingTvgId,
                        urlQuality = mapOf(trimmed to lineQuality(title, trimmed))
                    )
                )
            }
            pendingTitle = null
            pendingLogo = ""
            pendingChno = 0
            pendingTvgId = ""
            pendingHeaders = mutableMapOf()
        }

        text.lines().forEach { raw ->
            val line = raw.trim()
            when {
                line.isEmpty() || line == "#EXTM3U" || line.startsWith("#EXTM3U ") -> {
                    Regex("x-tvg-url=\"([^\"]*)\"").find(line)?.groupValues?.get(1)
                        ?.takeIf { it.isNotBlank() }?.let {
                            epgUrls = (epgUrls + it).distinct()
                        }
                }

                line.startsWith("#PLAYLIST") || line.startsWith("#AUTHOR") ||
                        line.startsWith("#DATE") -> {}

                line.startsWith("#EXTINF") -> {
                    pendingTitle = line.substringAfterLast(',').trim()
                    pendingLogo = Regex("tvg-logo=\"([^\"]*)\"").find(line)?.groupValues?.get(1) ?: ""
                    // 台标地址同样屏蔽，避免加载图片时连到风险域名
                    if (pendingLogo.isNotEmpty() && isBlockedUrl(pendingLogo)) pendingLogo = ""
                    pendingChno = Regex("tvg-chno=\"(\\d+)\"").find(line)?.groupValues?.get(1)?.toInt() ?: 0
                    pendingTvgId = Regex("tvg-id=\"([^\"]*)\"").find(line)?.groupValues?.get(1) ?: ""
                    group = Regex("group-title=\"([^\"]*)\"").find(line)?.groupValues?.get(1)
                        ?.takeIf { it.isNotBlank() } ?: group
                }

                line.startsWith("#EXTVLCOPT") -> {
                    // #EXTVLCOPT:http-user-agent=xxx / http-referrer=xxx（借鉴 my-tv-0）
                    val m = Regex("#EXTVLCOPT:http-([A-Za-z-]+)=(.*)").find(line)
                    if (m != null) {
                        val key = when (m.groupValues[1].lowercase()) {
                            "user-agent" -> "User-Agent"
                            "referrer", "referer" -> "Referer"
                            else -> m.groupValues[1]
                        }
                        pendingHeaders[key] = m.groupValues[2].trim()
                    }
                }

                line.startsWith("#") -> {}

                line.contains("#genre#") -> {
                    group = line.substringBefore(',').trim().ifEmpty { "其他" }
                }

                line.contains(',') && line.contains("://") -> {
                    // TXT 格式：频道名,地址
                    pendingTitle = line.substringBefore(',').trim()
                    pendingLogo = ""
                    addChannel(line.substringAfter(','))
                }

                else -> addChannel(line)
            }
        }
    }

    /** 把只读分组转成可变分组，便于归一化处理 */
    private fun mutableGroups(src: Map<String, List<TV>>): LinkedHashMap<String, MutableList<TV>> {
        val out = LinkedHashMap<String, MutableList<TV>>()
        src.forEach { (k, v) -> out[k] = v.toMutableList() }
        return out
    }

    /** 编号、跨源跨分组折叠与线路排序（需在所有源合并完成后调用） */
    private fun finalize(result: LinkedHashMap<String, MutableList<TV>>) {
        // 先清理黑名单地址（含历史缓存里的残留）：清空线路的频道一并丢弃
        result.forEach { (_, v) ->
            v.removeAll { tv ->
                if (tv.videoUrl.any { isBlockedUrl(it) }) {
                    tv.videoUrl = tv.videoUrl.filterNot { isBlockedUrl(it) }
                }
                val logo = tv.logo as? String
                if (!logo.isNullOrEmpty() && isBlockedUrl(logo)) tv.logo = ""
                tv.videoUrl.isEmpty()
            }
        }
        cleanEmptyGroups(result)

        // 只保留央视与主流地方卫视（详见 isWanted），先过滤再折叠，避免白做工
        applyChannelFilter(result)

        // 全局折叠：不同订阅源/分组中的同一频道，合并为一条多线路频道（保留首次出现的分组与命名）
        val folded = linkedMapOf<String, Pair<String, TV>>()
        val grouped = LinkedHashMap<String, MutableList<TV>>()
        result.forEach { (g, v) ->
            v.forEach { tv ->
                val key = canonicalName(tv.title)
                val existing = folded[key]
                if (existing == null) {
                    folded[key] = g to tv
                    grouped.getOrPut(g) { mutableListOf() }.add(tv)
                } else {
                    val etv = existing.second
                    for (url in tv.videoUrl) {
                        if (!etv.videoUrl.contains(url)) {
                            etv.videoUrl = etv.videoUrl + url
                        }
                    }
                    // 合并画质信息（已有值优先）
                    if (tv.urlQuality.isNotEmpty()) {
                        etv.urlQuality = tv.urlQuality + etv.urlQuality
                    }
                }
            }
        }
        result.clear()
        result.putAll(grouped)
        cleanEmptyGroups(result)

        // 全局连续编号：列表显示、数字选台与播放位置记忆都依赖此编号。
        // 编号即列表顺序（不再掺入源里的 tvg-chno），做到"所见即所得"。
        var id = 0
        result.forEach { (_, v) -> v.forEach { it.id = id++ } }

        // 线路排序：录像轮播/已知坏线最次 → 画质高优先 → 探活延迟低优先
        // 注意：只排序不删除。全部线路都保留，用户随时可以在线路列表里手动切换；
        // 自动轮换的范围限制由 TVViewModel.AUTO_LINE_LIMIT 控制。
        result.forEach { (_, v) ->
            v.forEach { tv -> sortLines(tv, trim = true) }
        }
    }

    /**
     * 对单个频道的线路重排：排序 → 网段打散 →（可选）裁剪。
     *
     * 加载时用 `trim = true`；**运行时重排必须传 `trim = false`**——
     * 否则可能把正在播放的那条线路裁到保留位之外，导致下次切换找不到它。
     */
    fun sortLines(tv: TV, trim: Boolean) {
        if (tv.videoUrl.size <= 1) return
        val preferred = preferenceMap()[canonicalName(tv.title)]

        var comparator: Comparator<String> = compareBy<String> {
            (if (isLoopSuspect(it)) 1 else 0) +
                    (if (LineHealth.isBad(it)) 1 else 0)
        }
            // 用户手动选过的那条优先（坏线已被上一级排除，不会把坏线抬上来）
            .thenBy { if (it == preferred) 0 else 1 }

        comparator = if (SP.qualityFirst) {
            // 画质优先：先看清晰度，再看能不能播
            comparator
                .thenByDescending { measuredOrGuessedQuality(tv, it) }
                .thenBy { probeLevelRank(it) }
                .thenByDescending { successRank(it) }
        } else {
            // 默认：可用性优先——能播 > 清晰
            comparator
                .thenByDescending { successRank(it) }
                .thenBy { probeLevelRank(it) }
                .thenByDescending { measuredOrGuessedQuality(tv, it) }
        }

        tv.videoUrl = tv.videoUrl.sortedWith(comparator.thenBy { ChannelProbe.sortKey(it) })
        // 网段打散：实测一个频道的多条线路经常汇聚到同一台服务器，
        // 若前两条（自动轮换范围）同源，单点故障会一次打掉整批
        diversifyLines(tv)
        // 裁剪：只排序不删除会让探活覆盖不全，排在后面的线路依据是"未探测"这个中性值，等于盲排
        if (trim) trimLines(tv)
    }

    // ---------------- 线路偏好（用户手动选过的线路） ----------------

    /** 偏好上限，避免长期使用后无限增长 */
    private const val MAX_LINE_PREFS = 100

    @Volatile
    private var prefCacheJson: String = ""
    @Volatile
    private var prefCache: Map<String, String> = emptyMap()

    /** 归一化频道名 → 用户手动选过的线路 URL（带简单缓存，避免每次排序都解析 JSON） */
    private fun preferenceMap(): Map<String, String> {
        val json = SP.linePreference
        if (json == prefCacheJson) return prefCache
        val parsed = runCatching {
            val type = object : TypeToken<Map<String, String>>() {}.type
            Gson().fromJson<Map<String, String>>(json, type)
        }.getOrNull() ?: emptyMap()
        prefCacheJson = json
        prefCache = parsed
        return parsed
    }

    /**
     * 记住用户为某频道手动选择的线路。
     * 这是比"自动轮换播成功过"更强的偏好信号——用户明确选过它。
     */
    fun rememberPreference(tv: TV, url: String) {
        if (url.isBlank()) return
        val key = canonicalName(tv.title)
        val map = LinkedHashMap(preferenceMap())
        map.remove(key)          // 重新插入以移到末尾（超出上限时淘汰最旧的）
        map[key] = url
        val limited = if (map.size > MAX_LINE_PREFS) {
            LinkedHashMap(map.entries.drop(map.size - MAX_LINE_PREFS).associate { it.key to it.value })
        } else {
            map
        }
        SP.linePreference = Gson().toJson(limited)
        prefCacheJson = ""       // 让下次读取重新解析
        Log.i(TAG, "remember preference: ${tv.title} -> $url")
    }

    /**
     * 每个频道保留的线路数上限。
     *
     * 保留更多的唯一价值是"手动还能切到更靠后的线路"，但代价很实在：
     * 实测一个频道常有 10 条以上线路（其中不少是同源冗余——8 条入口实际只对应 4 台服务器），
     * 而探活每轮上限 200 条、后台补探 3 轮共 600 条，覆盖不到全部，
     * 排在后面的线路就只能按"未探测"的中性值盲排。
     *
     * 6 条 = 自动轮换 2 条 + 手动切换 4 条，已足够；
     * 且排序已保证"坏线/录像 → 成功率 → 画质 → 端到端耗时"，被裁掉的本就是最次的。
     */
    private const val MAX_LINES_PER_CHANNEL = 6

    /** 裁掉排序 + 打散之后多余的线路（保留前 [MAX_LINES_PER_CHANNEL] 条） */
    private fun trimLines(tv: TV) {
        if (tv.videoUrl.size <= MAX_LINES_PER_CHANNEL) return
        tv.videoUrl = tv.videoUrl.take(MAX_LINES_PER_CHANNEL)
    }

    /**
     * 网段打散：让靠前的线路尽量来自不同网络，同时**不丢弃任何线路**（只把同网段的排到后面）。
     *
     * 贪心遍历排序结果：每个网络的第一条线保持原位，同网络的其余线路延后；
     * 已知坏线 / 疑似录像不参与提前，始终留在最后，避免"打散"把它们反向抬到前面。
     */
    private fun diversifyLines(tv: TV) {
        val urls = tv.videoUrl
        if (urls.size <= 1) return
        val (suspect, preferred) = urls.partition {
            isLoopSuspect(it) || LineHealth.isBad(it)
        }
        if (preferred.size <= 1) {
            tv.videoUrl = preferred + suspect
            return
        }
        val seen = HashSet<String>(preferred.size)
        val firstOfNetwork = ArrayList<String>(preferred.size)
        val duplicated = ArrayList<String>(preferred.size)
        for (u in preferred) {
            if (seen.add(networkKey(u))) firstOfNetwork.add(u) else duplicated.add(u)
        }
        tv.videoUrl = firstOfNetwork + duplicated + suspect
    }

    /**
     * 线路的网络归属（判断两条线路是否很可能在同一台机器上）。
     *
     * 优先用探活得到的**真实来源**：入口域名常常只是调度器，
     * 实测"8 个不同入口、重定向后只有 4 台服务器"这种情况，只有它能识别。
     * 未探过的线路回退到入口 host。
     */
    private fun networkKey(url: String): String {
        ChannelProbe.finalHostOf(url)?.let { return normalizeHost(it.lowercase()) }
        val host = runCatching { android.net.Uri.parse(url).host?.lowercase() }.getOrNull()
            ?: return url
        return normalizeHost(host)
    }

    /** 裸 IP → 取 /16 段（实测同段聚集明显，可近似视为同机房）；域名 → 取完整 host */
    private fun normalizeHost(host: String): String =
        if (IPV4_REGEX.matches(host)) host.split('.').take(2).joinToString(".") else host

    /** json 源格式（借鉴 my-tv-0）：[{"name":...,"uris":[...],"group":...,"number":...,"headers":{...}}] */
    private data class JsonTV(
        val name: String? = null,
        val title: String? = null,
        val group: String? = null,
        val logo: Any? = null,
        val uris: List<String> = emptyList(),
        val headers: Map<String, String> = emptyMap(),
        val number: Int = 0,
    )

    private fun parseJson(text: String, result: LinkedHashMap<String, MutableList<TV>>) {
        val items = Gson().fromJson(text, Array<JsonTV>::class.java) ?: return
        for (it in items) {
            val title = it.title ?: it.name ?: continue
            val group = it.group?.takeIf { g -> g.isNotBlank() } ?: "其他"
            val urls = it.uris.filter { u -> u.contains("://") && !isBlockedUrl(u) }.distinct()
            if (urls.isEmpty()) continue
            var logo = it.logo?.toString() ?: ""
            if (logo.isNotEmpty() && isBlockedUrl(logo)) logo = ""
            result.getOrPut(group) { mutableListOf() }.add(
                TV(
                    0,
                    title,
                    "",
                    urls,
                    group,
                    logo,
                    "",
                    "",
                    ProgramType.DIRECT,
                    false,
                    chno = if (it.number > 0) it.number else 0,
                    headers = it.headers,
                    urlQuality = urls.associateWith { u -> qualityScore("$title $u") }
                )
            )
        }
        var id = 0
        result.forEach { (_, v) -> v.forEach { it.id = id++ } }
    }

    /** 清理折叠后没有频道的空分组 */
    private fun cleanEmptyGroups(result: LinkedHashMap<String, MutableList<TV>>) {
        result.entries.removeAll { it.value.isEmpty() }
    }

    /**
     * 屏蔽的域名（含其所有子域）。
     * 这些是第三方直播聚合/代理地址，本身不是电视直播源，且常被杀毒软件标记为风险站点。
     * 命中后：解析阶段直接丢弃，既不播放也不探活（连 tvg-logo 图片地址也一并屏蔽）。
     */
    private val BLOCKED_HOSTS = listOf(
        "iill.top",
    )

    /** 地址是否属于被屏蔽域名（host 解析失败时退化为字符串匹配，避免漏网） */
    private fun isBlockedUrl(url: String): Boolean {
        if (url.isBlank()) return false
        val host = runCatching { android.net.Uri.parse(url).host?.lowercase() }.getOrNull()
        if (host != null) {
            return BLOCKED_HOSTS.any { host == it || host.endsWith(".$it") }
        }
        val lower = url.lowercase()
        return BLOCKED_HOSTS.any {
            lower.contains("//$it") || lower.contains(".$it/") || lower.contains(".$it:")
        }
    }

    /** 明显不是频道的占位/垃圾条目前缀 */
    private val JUNK_TITLE_REGEX = Regex(
        "^(更新时间|更新日期|更新说明|公告|广告|加群|未命名|敬请期待|测试|test|福利|暂无|无效).*",
        RegexOption.IGNORE_CASE
    )

    /** 占位/垃圾频道名（订阅列表里常见的噪音行） */
    private fun isJunkTitle(title: String): Boolean {
        val t = title.trim()
        if (t.length <= 1) return true
        return JUNK_TITLE_REGEX.containsMatchIn(t)
    }

    /**
     * 保留的央视台号（[canonicalName] 归一化后的形态）。
     * 央视全套：CCTV-1~17 与 CCTV-5+。
     */
    private val CCTV_KEEP = setOf(
        // 归一化后失去台号的（如 CCTV-4K，"4K" 被当画质词去掉）
        "CCTV",
        "CCTV1", "CCTV2", "CCTV3", "CCTV4", "CCTV5", "CCTV5+",
        "CCTV6", "CCTV7", "CCTV8", "CCTV9", "CCTV10", "CCTV11",
        "CCTV12", "CCTV13", "CCTV14", "CCTV15", "CCTV16", "CCTV17",
    )

    /**
     * 只保留「央视 + 主流地方卫视」。
     *
     * 直播聚合源里混着大量游戏直播（斗鱼/虎牙）、购物、地方城市台，甚至节目名条目，
     * 对看电视没用还淹没列表，这里统一丢弃：
     * - 央视：白名单台号
     * - CGTN、凤凰卫视：明确保留
     * - 地方卫视：归一化名以"卫视"结尾（湖南卫视、东方卫视、海峡卫视…）
     *   用 endsWith 而非 contains，避免"北京卫视养生堂"这类节目名条目混进来
     *
     * 想调整保留范围改这里即可（例如加上"翡翠台"）。
     */
    private fun isWanted(title: String): Boolean {
        val c = canonicalName(title)
        if (c.startsWith("CCTV")) return CCTV_KEEP.contains(c)
        if (c.startsWith("CGTN")) return true
        if (c.contains("凤凰卫视")) return true
        return c.endsWith("卫视")
    }

    /** 过滤后统一归入的分组名 */
    private const val GROUP_CCTV = "央视频道"
    private const val GROUP_SAT = "卫视频道"

    /**
     * 应用频道白名单过滤，并把保留下来的频道归入「央视频道 / 卫视频道」两个分组。
     *
     * 之所以重新分组：直播源自己的分组名往往名不副实（实测出现过
     * 「咪咕央视」里装着海峡卫视、「备用频道」「港澳代理」这类分组）。
     *
     * 过滤后一个不剩时（例如用户自己配的是纯地方台源）保留原样，
     * 否则用户会看到一个空列表而不知道发生了什么。
     */
    private fun applyChannelFilter(result: LinkedHashMap<String, MutableList<TV>>) {
        val cctv = mutableListOf<TV>()
        val sat = mutableListOf<TV>()
        result.values.forEach { list ->
            // 显式 label：外层也是 forEach，不加会与 `return@forEach` 产生同名歧义
            list.forEach inner@{ tv ->
                if (!isWanted(tv.title)) return@inner
                if (isCctv(tv.title)) cctv.add(tv) else sat.add(tv)
            }
        }
        if (cctv.isEmpty() && sat.isEmpty()) {
            Log.w(TAG, "channel filter matched nothing, keep original list")
            cleanEmptyGroups(result)
            return
        }

        // 央视按台号排序，编号顺序符合直觉：CCTV1、CCTV2 … CCTV5、CCTV5+ …
        cctv.sortBy { cctvOrder(it.title) }

        val kept = LinkedHashMap<String, MutableList<TV>>()
        if (cctv.isNotEmpty()) kept[GROUP_CCTV] = cctv
        if (sat.isNotEmpty()) kept[GROUP_SAT] = sat
        // 同步频道自身的分组字段，供"当前频道属于哪个分组"的判断使用
        kept.forEach { (group, list) -> list.forEach { it.channel = group } }

        Log.i(
            TAG,
            "channel filter: keep ${cctv.size + sat.size} (cctv=${cctv.size}, sat=${sat.size}), " +
                    "drop ${result.values.sumOf { it.size } - cctv.size - sat.size}"
        )
        result.clear()
        result.putAll(kept)
        cleanEmptyGroups(result)
    }

    /** 央视/CGTN 归为「央视频道」 */
    private fun isCctv(title: String): Boolean {
        val c = canonicalName(title)
        return c.startsWith("CCTV") || c.startsWith("CGTN")
    }

    /** 央视排序键：CCTV5 → 50、CCTV5+ → 51、CCTV12 → 120；无台号的（如 CCTV-4K）排最后 */
    private fun cctvOrder(title: String): Int {
        val m = Regex("(\\d{1,2})(\\+)?").find(canonicalName(title)) ?: return Int.MAX_VALUE
        val n = m.groupValues[1].toIntOrNull() ?: return Int.MAX_VALUE
        return n * 10 + if (m.groupValues[2].isNotEmpty()) 1 else 0
    }

    /** 点播文件地址（.mp4/.mkv/.avi）：直播列表里出现这些基本是混进来的录制内容 */
    private fun isVodUrl(url: String): Boolean {
        val path = url.substringBefore('?').lowercase()
        return path.endsWith(".mp4") || path.endsWith(".mkv") || path.endsWith(".avi")
    }

    /** 疑似录像轮播线路特征 */
    private fun isLoopSuspect(url: String): Boolean {
        return url.contains("kwimgs")            // 快手 CDN 轮播
                || url.contains("/video-hls/")   // 点播转直播常见路径
    }

    /** 是否疑似录像轮播线路（线路列表展示用） */
    fun isLoopSuspectLine(url: String): Boolean = isLoopSuspect(url)

    /**
     * 线路画质标签（线路列表展示用）。
     * 优先用探活**实测**到的分辨率；拿不到（单码率源没有 RESOLUTION）再回退到"名字/地址推断"。
     */
    fun qualityLabel(tv: TV, url: String): String {
        return when (measuredOrGuessedQuality(tv, url)) {
            in 4000..Int.MAX_VALUE -> "4K"
            in 3000 until 4000 -> "2K"
            in 2000 until 3000 -> "1080P"
            in 1000 until 2000 -> "720P"
            in 500 until 1000 -> "480P"
            else -> "未知画质"
        }
    }

    /** 画质分：优先探活实测分辨率，回退到名字/地址推断 */
    private fun measuredOrGuessedQuality(tv: TV, url: String): Int {
        val measured = ChannelProbe.heightOf(url)
        return if (measured > 0) heightScore(measured) else (tv.urlQuality[url] ?: qualityScore(url))
    }

    /** 实测分辨率高度 → 与 [qualityScore] 同一量纲的分数 */
    private fun heightScore(height: Int): Int = when {
        height >= 2160 -> 4000
        height >= 1440 -> 3000
        height >= 1080 -> 2000
        height >= 720 -> 1000
        height >= 480 -> 500
        else -> 0
    }

    /** 排序用成功率：无记录按 50 中性处理，避免把所有没播过的线路冤枉地排到最后 */
    private fun successRank(url: String): Int {
        val rate = LineHealth.successRate(url)
        return if (rate < 0) 50 else rate
    }

    /**
     * 探活档位（越小越优先）。
     *
     * 实测暴露过一个问题：标称 `1080P` 但**探测失败**的线路，会因画质分高于
     * "可达但未知画质"的线路而排到前面，占掉自动轮换的名额（前 2 条），白白试一次。
     * 所以这里把"能不能播"提到"清不清晰"之前。
     */
    private fun probeLevelRank(url: String): Int = when {
        !ChannelProbe.isProbed(url) -> 2   // 未探测：居中，不优先也不淘汰
        !ChannelProbe.isOk(url) -> 3       // 探测失败：沉底
        ChannelProbe.isVerified(url) -> 0  // 已验证到分片：最优先
        else -> 1                          // 仅可达
    }

    /** 线路画质分：优先看命名里的清晰度标签，没有再看地址 */
    private fun lineQuality(title: String, url: String): Int {
        val fromTitle = qualityScore(title)
        return if (fromTitle > 0) fromTitle else qualityScore(url)
    }

    /**
     * 从文字（频道名或地址）中提取画质分，越大越清晰。
     * 4K/2160 > 1440/2K > 1080/FHD > 720 > 576/480，50/60FPS 与 HEVC 额外加分。
     */
    private fun qualityScore(text: String): Int {
        val t = text.uppercase()
        var score = when {
            t.contains("4K") || t.contains("2160") || t.contains("UHD") -> 4000
            t.contains("1440") || t.contains("2K") -> 3000
            t.contains("1080") || t.contains("FHD") -> 2000
            t.contains("720") -> 1000
            t.contains("576") || t.contains("480") -> 500
            else -> 0
        }
        if (t.contains("50FPS") || t.contains("60FPS")) score += 100
        if (t.contains("HEVC") || t.contains("H265") || t.contains("H.265")) score += 50
        return score
    }

    /**
     * 频道名归一化：用于折叠同一频道的不同命名。
     * 如 CCTV-1 / CCTV1 / CCTV-1综合 / CCTV-1(720p) 均折叠为同一频道。
     */
    fun canonicalName(raw: String): String {
        var n = raw.trim().uppercase()
            .replace("（", "(")
            .replace("）", ")")
        // 去掉括号内容与方括号内容：(720p)、[HD] 等
        n = n.replace(Regex("\\([^)]*\\)"), "")
        n = n.replace(Regex("\\[[^\\]]*\\]"), "")
        // 去掉画质/帧率等修饰词
        n = n.replace(Regex("(超高清|高清|标清|蓝光|超清|FHD|HD|SD|4K|8K|50FPS|60FPS)"), "")
        n = n.replace(" ", "").replace("－", "-").replace("–", "-").replace("—", "-")
        // CCTV 系列取频道号主干：CCTV-1综合 -> CCTV1，CCTV5+体育赛事 -> CCTV5+
        val m = Regex("^(CCTV)-?([0-9]{1,2}\\+?)([KQ]?)").find(n)
        if (m != null) {
            return "CCTV" + m.groupValues[2] + m.groupValues[3]
        }
        return n.replace("-", "")
    }
}
