package com.lizongying.mytv

import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

/**
 * 线路健康记忆：把"实际播放的结果"记下来并落盘。
 *
 * 与 [ChannelProbe] 的分工：
 * - ChannelProbe 看"通不通、能不能播"（网络层 + 分片验证）；
 * - LineHealth 看"实际播起来成不成功"（真实播放的成功/失败累计）。
 *
 * 判定规则：
 * - 失败 [FAIL_LIMIT] 次即视为坏线，排序时靠后、频道可标记"暂不可用"；
 *   超过 [COOLDOWN_MS] 自动恢复尝试，避免源恢复后一直被埋没。
 * - **同时累计成功次数**，供排序参考"这条线历史上靠不靠谱"：
 *   早期只记失败，导致"从没播过"和"播一次成一次"的线路无法区分。
 */
object LineHealth {
    private const val TAG = "LineHealth"
    private const val FILE_NAME = "line_health.json"

    /** 被放弃多少次后判定为坏线 */
    private const val FAIL_LIMIT = 2

    /**
     * 冷却时间：到期后重新给机会（源可能已恢复）。
     * 取 2 小时——公共源经常是"几十分钟后又好了"，6 小时会把恢复的线路埋没太久。
     */
    private const val COOLDOWN_MS = 2 * 3600_000L

    /**
     * 一条线路的健康记录。
     * [count] 沿用旧字段名（原为失败次数），保证升级后旧记录里的失败计数仍能读出。
     */
    data class Health(
        /** 失败次数 */
        val count: Int = 0,
        /** 累计播放成功次数 */
        val success: Int = 0,
        /** 最近一次更新时间（失败或成功都会刷新） */
        val at: Long = 0,
    )

    @Volatile
    private var health: Map<String, Health> = emptyMap()

    private var loaded = false

    /** 落盘单线程串行执行：并发写同一文件会交错损坏 */
    private val persistExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()

    /** 是否已判定为坏线（冷却期内才算） */
    fun isBad(url: String): Boolean {
        val h = ensureLoaded()[url] ?: return false
        if (System.currentTimeMillis() - h.at > COOLDOWN_MS) return false
        return h.count >= FAIL_LIMIT
    }

    /** 该频道的线路是否已全部被判为坏线 */
    fun isChannelDown(urls: List<String>): Boolean {
        return urls.isNotEmpty() && urls.all { isBad(it) }
    }

    /**
     * 历史成功率（0~100）；没有记录时返回 -1（未知）。
     *
     * 排序时"未知"必须作中性处理，不能当成 0——
     * 那会把所有从没播过的新线路冤枉地排到最后，而它们往往才是好线路。
     */
    fun successRate(url: String): Int {
        val h = ensureLoaded()[url] ?: return -1
        val total = h.count + h.success
        return if (total == 0) -1 else h.success * 100 / total
    }

    /** 放弃某条线路时记录一次失败 */
    fun recordFail(url: String) {
        if (url.isBlank()) return
        // 本机没有 IPv6 出口时，IPv6 线路失败是设备能力问题，不能算在这条线路上
        if (Utils.isIpv6Url(url) && !Utils.hasIpv6) return
        val now = System.currentTimeMillis()
        val map = HashMap(ensureLoaded())
        val prev = map[url]
        val count = if (prev != null && now - prev.at <= COOLDOWN_MS) prev.count + 1 else 1
        map[url] = Health(count = count, success = prev?.success ?: 0, at = now)
        health = map
        persistAsync()
        Log.i(TAG, "record fail: count=$count $url")
    }

    /** 播放成功：清空失败计数并累计成功次数 */
    fun recordSuccess(url: String) {
        if (url.isBlank()) return
        val map = HashMap(ensureLoaded())
        val success = (map[url]?.success ?: 0) + 1
        map[url] = Health(count = 0, success = success, at = System.currentTimeMillis())
        health = map
        persistAsync()
        Log.i(TAG, "recovered: success=$success $url")
    }

    /** 清空记录与落盘文件（供"重新检测线路"使用） */
    fun reset() {
        health = emptyMap()
        runCatching {
            MyApplication.instance?.let { File(it.filesDir, FILE_NAME).delete() }
        }
        Log.i(TAG, "line health cleared")
    }

    /** 预热：在 IO 线程把记录读进内存，避免首次列表绑定在主线程做磁盘 IO */
    fun warmUp() {
        ensureLoaded()
    }

    @Synchronized
    private fun ensureLoaded(): Map<String, Health> {
        if (loaded) return health
        loaded = true
        val ctx = MyApplication.instance ?: return health
        try {
            val f = File(ctx.filesDir, FILE_NAME)
            if (f.exists()) {
                val type = object : TypeToken<Map<String, Health>>() {}.type
                val restored = Gson().fromJson<Map<String, Health>>(f.readText(), type)
                    ?: emptyMap()
                // 丢弃早已过期的记录（换源后旧地址会永久残留），避免文件无限增长
                val cutoff = System.currentTimeMillis() - 7L * 24 * 3600_000
                health = restored.filterValues { it.at > cutoff }
            }
        } catch (e: Exception) {
            Log.e(TAG, "load line health failed", e)
        }
        return health
    }

    /** 落盘放到后台线程，避免在播放错误回调（主线程）里读写文件 */
    private fun persistAsync() {
        val ctx = MyApplication.instance ?: return
        val snapshot = health
        persistExecutor.execute {
            try {
                File(ctx.filesDir, FILE_NAME).writeText(Gson().toJson(snapshot))
            } catch (e: Exception) {
                Log.e(TAG, "save line health failed", e)
            }
        }
    }
}
