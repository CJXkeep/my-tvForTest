package com.lizongying.mytv

import android.content.Context
import android.content.SharedPreferences

object SP {
    // Name of the sp file TODO Should use a meaningful name and do migrations
    private const val SP_FILE_NAME = "MainActivity"

    // If Change channel with up and down in reversed order or not
    private const val KEY_CHANNEL_REVERSAL = "channel_reversal"

    // If use channel num to select channel or not
    private const val KEY_CHANNEL_NUM = "channel_num"

    private const val KEY_TIME = "time"

    // If start app on device boot or not
    private const val KEY_BOOT_STARTUP = "boot_startup"

    private const val KEY_GRID = "grid"

    // Position in list of the selected channel item
    private const val KEY_POSITION = "position"

    // guid
    private const val KEY_GUID = "guid"

    // IPTV 数据源订阅地址
    private const val KEY_IPTV_SOURCE_URL = "iptv_source_url"

    // 收藏频道（归一化名，逗号分隔，靠前的排更前）
    private const val KEY_FAVORITES = "favorites"

    // EPG 节目单地址（XMLTV，留空则用订阅源自带的 x-tvg-url）
    private const val KEY_EPG_URL = "epg_url"

    private lateinit var sp: SharedPreferences

    /**
     * The method must be invoked as early as possible(At least before using the keys)
     */
    fun init(context: Context) {
        sp = context.getSharedPreferences(SP_FILE_NAME, Context.MODE_PRIVATE)
    }

    var channelReversal: Boolean
        get() = sp.getBoolean(KEY_CHANNEL_REVERSAL, false)
        set(value) = sp.edit().putBoolean(KEY_CHANNEL_REVERSAL, value).apply()

    var channelNum: Boolean
        get() = sp.getBoolean(KEY_CHANNEL_NUM, true)
        set(value) = sp.edit().putBoolean(KEY_CHANNEL_NUM, value).apply()

    var time: Boolean
        get() = sp.getBoolean(KEY_TIME, true)
        set(value) = sp.edit().putBoolean(KEY_TIME, value).apply()

    var bootStartup: Boolean
        get() = sp.getBoolean(KEY_BOOT_STARTUP, false)
        set(value) = sp.edit().putBoolean(KEY_BOOT_STARTUP, value).apply()

    var grid: Boolean
        get() = sp.getBoolean(KEY_GRID, false)
        set(value) = sp.edit().putBoolean(KEY_GRID, value).apply()

    var itemPosition: Int
        get() = sp.getInt(KEY_POSITION, 0)
        set(value) = sp.edit().putInt(KEY_POSITION, value).apply()

    var guid: String
        get() = sp.getString(KEY_GUID, "") ?: ""
        set(value) = sp.edit().putString(KEY_GUID, value).apply()

    var iptvSourceUrl: String
        get() = sp.getString(KEY_IPTV_SOURCE_URL, "") ?: ""
        set(value) = sp.edit().putString(KEY_IPTV_SOURCE_URL, value).apply()

    var favorites: String
        get() = sp.getString(KEY_FAVORITES, "") ?: ""
        set(value) = sp.edit().putString(KEY_FAVORITES, value).apply()

    var epgUrl: String
        get() = sp.getString(KEY_EPG_URL, "") ?: ""
        set(value) = sp.edit().putString(KEY_EPG_URL, value).apply()
}