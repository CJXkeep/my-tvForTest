package com.lizongying.mytv

import android.content.Context
import android.content.SharedPreferences

object SP {
    // Name of the sp file TODO Should use a meaningful name and do migrations
    private const val SP_FILE_NAME = "MainActivity"

    private const val KEY_TIME = "time"

    // If start app on device boot or not
    private const val KEY_BOOT_STARTUP = "boot_startup"

    // Position in list of the selected channel item
    private const val KEY_POSITION = "position"

    // IPTV 数据源订阅地址
    private const val KEY_IPTV_SOURCE_URL = "iptv_source_url"

    // EPG 节目单地址（XMLTV，留空则用订阅源自带的 x-tvg-url）
    private const val KEY_EPG_URL = "epg_url"

    private lateinit var sp: SharedPreferences

    /**
     * The method must be invoked as early as possible(At least before using the keys)
     */
    fun init(context: Context) {
        sp = context.getSharedPreferences(SP_FILE_NAME, Context.MODE_PRIVATE)
    }

    var time: Boolean
        get() = sp.getBoolean(KEY_TIME, false)
        set(value) = sp.edit().putBoolean(KEY_TIME, value).apply()

    var bootStartup: Boolean
        get() = sp.getBoolean(KEY_BOOT_STARTUP, false)
        set(value) = sp.edit().putBoolean(KEY_BOOT_STARTUP, value).apply()

    var itemPosition: Int
        get() = sp.getInt(KEY_POSITION, 0)
        set(value) = sp.edit().putInt(KEY_POSITION, value).apply()

    var iptvSourceUrl: String
        get() = sp.getString(KEY_IPTV_SOURCE_URL, "") ?: ""
        set(value) = sp.edit().putString(KEY_IPTV_SOURCE_URL, value).apply()

    var epgUrl: String
        get() = sp.getString(KEY_EPG_URL, "") ?: ""
        set(value) = sp.edit().putString(KEY_EPG_URL, value).apply()

    /**
     * 双播放器预加载：为相邻频道提前准备一个待命播放器，换台时直接接管渲染。
     * 实测换台首帧从 1.7~4.0s 降到 30ms 上下。
     *
     * **默认开启**。代价是需要同时存在两个解码器实例，个别电视盒子可能因此卡顿、
     * 甚至第二个实例分配不到硬件解码器——设置里可以关掉。
     */
    private const val KEY_PRELOAD_NEXT = "preload_next"

    var preloadNext: Boolean
        get() = sp.getBoolean(KEY_PRELOAD_NEXT, true)
        set(value) = sp.edit().putBoolean(KEY_PRELOAD_NEXT, value).apply()

    // 排序偏好：false = 可用性优先（默认），true = 画质优先
    private const val KEY_QUALITY_FIRST = "quality_first"

    var qualityFirst: Boolean
        get() = sp.getBoolean(KEY_QUALITY_FIRST, false)
        set(value) = sp.edit().putBoolean(KEY_QUALITY_FIRST, value).apply()

    // 用户手动选过的线路偏好：JSON {"归一化频道名":"线路URL"}，该频道优先用它
    private const val KEY_LINE_PREF = "line_preference"

    var linePreference: String
        get() = sp.getString(KEY_LINE_PREF, "") ?: ""
        set(value) = sp.edit().putString(KEY_LINE_PREF, value).apply()

    // 远程配置访问令牌（首次读取时生成），用于保护局域网配置接口
    private const val KEY_CONFIG_TOKEN = "config_token"

    var configToken: String
        get() {
            val existing = sp.getString(KEY_CONFIG_TOKEN, "") ?: ""
            if (existing.isNotEmpty()) return existing
            val chars = "abcdefghijkmnpqrstuvwxyz23456789"
            val generated = (1..6)
                .map { chars[kotlin.random.Random.nextInt(chars.length)] }
                .joinToString("")
            sp.edit().putString(KEY_CONFIG_TOKEN, generated).apply()
            return generated
        }
        set(value) = sp.edit().putString(KEY_CONFIG_TOKEN, value).apply()

    /** 确保访问令牌已生成（启动时调用，避免首次请求时才生成导致地址不一致） */
    fun ensureConfigToken(): String = configToken

    // 是否已展示过首次启动的远程配置引导
    private const val KEY_GUIDE_SHOWN = "guide_shown"

    var guideShown: Boolean
        get() = sp.getBoolean(KEY_GUIDE_SHOWN, false)
        set(value) = sp.edit().putBoolean(KEY_GUIDE_SHOWN, value).apply()

    /** 恢复默认：重置各项设置开关与缓存，保留订阅源与收藏（用户配置数据） */
    fun reset() {
        sp.edit()
            .remove(KEY_TIME)
            .remove(KEY_BOOT_STARTUP)
            .remove(KEY_POSITION)
            .remove(KEY_EPG_URL)
            .remove(KEY_PRELOAD_NEXT)
            .remove(KEY_GUIDE_SHOWN)
            .apply()
    }
}