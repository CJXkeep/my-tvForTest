package com.lizongying.mytv

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import java.io.File

/**
 * 频道列表本地缓存：远程订阅拉取成功后落盘，断网或源站故障时直接复用，
 * 避免"必须联网且源可用"才能看电视。换源时需调用 [clear] 防止展示旧源数据。
 */
object ChannelCache {
    private const val TAG = "ChannelCache"
    private const val FILE_NAME = "channels.json"

    private data class Snapshot(
        val savedAt: Long = 0,
        val groups: Map<String, List<TV>> = emptyMap(),
    )

    private fun context(): Context? = MyApplication.instance

    fun save(groups: Map<String, List<TV>>) {
        val ctx = context() ?: return
        try {
            val snapshot = Snapshot(System.currentTimeMillis(), groups)
            File(ctx.filesDir, FILE_NAME).writeText(Gson().toJson(snapshot))
            Log.i(TAG, "cache saved: ${groups.size} groups")
        } catch (e: Exception) {
            Log.e(TAG, "save cache failed", e)
        }
    }

    fun load(): LinkedHashMap<String, MutableList<TV>>? {
        val ctx = context() ?: return null
        return try {
            val f = File(ctx.filesDir, FILE_NAME)
            if (!f.exists()) return null
            val snapshot = Gson().fromJson(f.readText(), Snapshot::class.java) ?: return null
            if (snapshot.groups.isEmpty()) return null
            // Gson 反序列化的集合可能不可变，转成可变以便后续归一化与排序
            val groups = LinkedHashMap<String, MutableList<TV>>()
            snapshot.groups.forEach { (k, v) -> groups[k] = v.toMutableList() }
            Log.i(TAG, "cache loaded: ${groups.size} groups, savedAt=${snapshot.savedAt}")
            groups
        } catch (e: Exception) {
            Log.e(TAG, "load cache failed", e)
            null
        }
    }

    fun clear() {
        val ctx = context() ?: return
        try {
            File(ctx.filesDir, FILE_NAME).delete()
        } catch (_: Exception) {
        }
    }
}
