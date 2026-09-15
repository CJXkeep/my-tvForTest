package com.lizongying.mytv

import java.util.concurrent.ConcurrentHashMap

/**
 * 线路地址的 302 落点缓存：**原始地址 → 最终地址**。
 *
 * 实测跨洲线路的 m3u8 要经一次跳转（`…221.218:8181/3m1080p/cctv2.m3u8` →
 * `…241.18:82/live/cctv2md.m3u8`），而一次跳转就是一轮完整的往返：
 * 请求 → 302 响应 → 新 host 的连接。起播时间线里 m3u8 阶段占了首帧的一半
 * （3.4s 里的 1.76s），跳转是其中实打实的一轮。
 *
 * 落点不额外发请求就能拿到——[StreamPreheat] 预热时本来就把跳转链走完了，
 * 直接从数据源读回落到哪即可。播放时直接请求落点，就省掉这一轮。
 *
 * **落点可能带时效参数**（实测见过 `?RTS=<时间戳>` 这类）。所以这份缓存是"尽力而为"的：
 * 用落点起播失败时，`PlayerFragment` 会退回原始地址重试一次并调用 [forget]，
 * 不会因为一个过期地址把整条线路判死。
 */
object ResolvedUrl {
    /** 条目上限：地址总数有限，超了直接清空，不做 LRU */
    private const val MAX_ENTRIES = 128

    private val map = ConcurrentHashMap<String, String>()

    /** 记下某条地址的落点；与原始地址相同就不必记 */
    fun remember(from: String, to: String) {
        if (from.isBlank() || to.isBlank() || from == to) return
        if (map.size >= MAX_ENTRIES) map.clear()
        map[from] = to
    }

    /** 取落点；没有记录则返回 null（调用方退回原始地址） */
    fun find(url: String): String? = map[url]

    /** 丢弃某条地址的落点（落点失效时调用） */
    fun forget(url: String) {
        map.remove(url)
    }
}
