package com.lizongying.mytv

import android.util.Log
import com.google.gson.Gson
import com.lizongying.mytv.models.ProgramType
import okhttp3.OkHttpClient
import okhttp3.Request as HttpReq
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

    /** 各订阅源自带的 EPG 地址（x-tvg-url），按加载顺序 */
    var epgUrls: List<String> = emptyList()
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

    /** 加载频道列表：多远程源合并，全部失败或未配置时回退内置源 */
    fun load(): Map<String, List<TV>> {
        val urls = SP.iptvSourceUrl
            .split(',', ';', '|', '\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        if (urls.isEmpty()) {
            Log.i(TAG, "no remote source, use builtin")
            return builtin()
        }

        val merged = linkedMapOf<String, MutableList<TV>>()
        var okCount = 0
        for (url in urls) {
            var loaded = false
            for (candidate in expandMirrors(url)) {
                try {
                    parseInto(fetch(candidate), merged)
                    loaded = true
                    Log.i(TAG, "source loaded: $candidate")
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "load source failed: $candidate", e)
                }
            }
            if (loaded) okCount++
        }

        if (merged.isEmpty()) {
            Log.e(TAG, "all remote sources failed, fallback to builtin")
            return builtin()
        }

        // 远程源在前，内置凤凰台放在最后（观看需求较低）
        val combined = LinkedHashMap<String, MutableList<TV>>()
        (merged.asSequence() + builtin().asSequence()).forEach { (k, v) ->
            combined.getOrPut(k) { mutableListOf() }.addAll(v)
        }
        finalize(combined)
        Log.i(TAG, "remote sources merged: $okCount/${urls.size}, ${combined.size} groups")
        return combined
    }

    /** GitHub 链接自动展开镜像候选（借鉴 my-tv-0 Utils.getUrls） */
    private fun expandMirrors(url: String): List<String> {
        if (!url.startsWith("https://raw.githubusercontent.com") &&
            !url.startsWith("https://github.com") &&
            !url.startsWith("http://raw.githubusercontent.com")
        ) {
            return listOf(url)
        }
        val mirrors = listOf(
            "https://gh.llkk.cc/",
            "https://github.moeyy.xyz/",
            "https://mirror.ghproxy.com/",
            "https://ghproxy.cn/",
            "https://ghproxy.net/",
            "https://ghproxy.click/",
            "https://ghproxy.com/",
            "https://github.moeyy.cn/",
            "https://gh-proxy.com/",
            "https://www.ghproxy.cc/",
            "https://cf.ghproxy.cc/",
            "https://ghp.ci/",
        )
        return listOf(url) + mirrors.map { it + url }
    }

    private fun fetch(url: String): String {
        val client = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
        val request = HttpReq.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android) my-tv")
            .build()
        client.newCall(request).execute().use { response ->
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
            val key = canonicalName(title)
            val existing = folded[key]
            if (existing != null) {
                val list = result[existing.first] ?: return
                val vm = list.find { it.title == existing.second }
                if (vm != null) {
                    if (!vm.videoUrl.contains(trimmed)) {
                        vm.videoUrl = vm.videoUrl + trimmed
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
                        tvgId = pendingTvgId
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

    /** 编号、跨源跨分组折叠与线路排序（需在所有源合并完成后调用） */
    private fun finalize(result: LinkedHashMap<String, MutableList<TV>>) {
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
                }
            }
        }
        result.clear()
        result.putAll(grouped)
        cleanEmptyGroups(result)

        // 收藏置顶：收藏的频道移入列表最前的"★收藏"分组，编号从 1 开始
        applyFavorites(result)

        // 全局连续编号：数字选台与播放位置记忆依赖此编号
        var id = 0
        result.forEach { (_, v) -> v.forEach { it.id = id++ } }

        // 线路排序：真直播优先，录像轮播（VOD 转播，画质差且可能夹带推广内容）排后
        result.forEach { (_, v) ->
            v.forEach { tv ->
                if (tv.videoUrl.size > 1) {
                    tv.videoUrl = tv.videoUrl.sortedBy { url -> if (isLoopSuspect(url)) 1 else 0 }
                }
            }
        }
    }

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
        for ((idx, it) in items.withIndex()) {
            val title = it.title ?: it.name ?: continue
            val group = it.group?.takeIf { g -> g.isNotBlank() } ?: "其他"
            val urls = it.uris.filter { u -> u.contains("://") }.distinct()
            if (urls.isEmpty()) continue
            val logo = it.logo?.toString() ?: ""
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
                    headers = it.headers
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

    /** 收藏置顶：把收藏的频道移到最前的"★收藏"分组（收藏顺序即编号顺序） */
    private fun applyFavorites(result: LinkedHashMap<String, MutableList<TV>>) {
        val favKeys = SP.favorites.split(',', ';', '|', '\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (favKeys.isEmpty()) return

        val favList = mutableListOf<TV>()
        for (key in favKeys) {
            val entry = result.entries.find { e ->
                e.value.any { canonicalName(it.title) == key }
            } ?: continue
            val tv = entry.value.first { canonicalName(it.title) == key }
            if (favList.none { canonicalName(it.title) == key }) {
                entry.value.remove(tv)
                favList.add(tv)
            }
        }
        if (favList.isNotEmpty()) {
            cleanEmptyGroups(result)
            val newResult = linkedMapOf<String, MutableList<TV>>()
            newResult["★收藏"] = favList
            newResult.putAll(result)
            result.clear()
            result.putAll(newResult)
        }
    }

    /** 收藏/取消收藏当前频道，返回操作后的收藏名列表 */
    fun toggleFavorite(title: String): Pair<Boolean, String> {
        val key = canonicalName(title)
        val current = SP.favorites.split(',', ';', '|', '\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toMutableList()
        return if (current.contains(key)) {
            current.remove(key)
            SP.favorites = current.joinToString(",")
            Pair(false, current.joinToString(","))
        } else {
            current.add(key)
            SP.favorites = current.joinToString(",")
            Pair(true, current.joinToString(","))
        }
    }

    /** 疑似录像轮播线路特征 */
    private fun isLoopSuspect(url: String): Boolean {
        return url.contains("kwimgs")            // 快手 CDN 轮播
                || url.contains("/video-hls/")   // 点播转直播常见路径
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
