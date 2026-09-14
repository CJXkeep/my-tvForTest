package com.lizongying.mytv

import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

/**
 * 数据源健康统计。
 *
 * 每次加载订阅源时记录一次结果（成功/失败、实际耗时、解析出的频道数、命中的镜像地址），
 * 按源聚合后落盘，并可在远程配置页查看。
 *
 * 目的：把「这几个源该留哪几个」从感觉变成数据——
 * 成功率、平均耗时、最近一次状态都是直接可读的，源开始退化时也能看出来。
 */
object SourceHealth {
    private const val TAG = "SourceHealth"
    private const val FILE_NAME = "source_health.json"

    /** 最多统计多少个源（配置变化时不至于无限增长，超出则丢弃最久未更新的） */
    private const val MAX_ENTRIES = 20

    data class Agg(
        val attempts: Int = 0,
        val okCount: Int = 0,
        val totalMs: Long = 0,
        val lastOk: Boolean = false,
        val lastMs: Long = 0,
        val lastChannels: Int = 0,
        /** 最近一次实际命中的地址（可能经过镜像），用于判断原始地址是否被墙 */
        val lastVia: String = "",
        val updatedAt: Long = 0,
    ) {
        /** 成功率（0~100） */
        val successRate: Int get() = if (attempts == 0) 0 else okCount * 100 / attempts

        /** 平均耗时（含失败的那些，反映"这个源的平均等待成本"） */
        val avgMs: Long get() = if (attempts == 0) 0L else totalMs / attempts
    }

    @Volatile
    private var stats: Map<String, Agg> = emptyMap()

    private var loaded = false

    /** 记录一次源加载结果：成功与失败都要记（失败才能看出源在退化） */
    @Synchronized
    fun record(source: String, ok: Boolean, ms: Long, channels: Int = 0, via: String = "") {
        if (source.isBlank()) return
        val map = HashMap(ensureLoaded())
        val prev = map[source] ?: Agg()
        map[source] = prev.copy(
            attempts = prev.attempts + 1,
            okCount = prev.okCount + if (ok) 1 else 0,
            totalMs = prev.totalMs + ms.coerceAtLeast(0L),
            lastOk = ok,
            lastMs = ms.coerceAtLeast(0L),
            lastChannels = if (ok) channels else prev.lastChannels,
            lastVia = if (ok && via.isNotEmpty()) via else prev.lastVia,
            updatedAt = System.currentTimeMillis(),
        )
        stats = if (map.size > MAX_ENTRIES) {
            map.entries
                .sortedByDescending { it.value.updatedAt }
                .take(MAX_ENTRIES)
                .associate { it.key to it.value }
        } else {
            map
        }
        persistAsync()
    }

    /** 当前统计快照（供远程配置页展示） */
    fun snapshot(): Map<String, Agg> = ensureLoaded()

    /** 源的可读标识（域名） */
    fun label(url: String): String =
        runCatching { java.net.URI(url).host }.getOrNull()?.removePrefix("www.") ?: url

    /** 清空统计与落盘文件 */
    fun reset() {
        stats = emptyMap()
        runCatching {
            MyApplication.instance?.let { File(it.filesDir, FILE_NAME).delete() }
        }
    }

    @Synchronized
    private fun ensureLoaded(): Map<String, Agg> {
        if (loaded) return stats
        loaded = true
        val ctx = MyApplication.instance ?: return stats
        try {
            val f = File(ctx.filesDir, FILE_NAME)
            if (f.exists()) {
                val type = object : TypeToken<Map<String, Agg>>() {}.type
                stats = Gson().fromJson<Map<String, Agg>>(f.readText(), type) ?: emptyMap()
            }
        } catch (e: Exception) {
            Log.e(TAG, "load source health failed", e)
        }
        return stats
    }

    /** 落盘放后台线程：记录发生在加载路径上，不能让它做文件 IO */
    private fun persistAsync() {
        val ctx = MyApplication.instance ?: return
        val snapshot = stats
        Thread {
            try {
                File(ctx.filesDir, FILE_NAME).writeText(Gson().toJson(snapshot))
            } catch (e: Exception) {
                Log.e(TAG, "save source health failed", e)
            }
        }.start()
    }
}
