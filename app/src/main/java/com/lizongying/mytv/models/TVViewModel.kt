package com.lizongying.mytv.models

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.lizongying.mytv.TV
import com.lizongying.mytv.api.FEPG
import java.text.SimpleDateFormat
import java.util.TimeZone

class TVViewModel(private var tv: TV) : ViewModel() {

    private var rowPosition: Int = 0
    private var itemPosition: Int = 0

    var retryTimes = 0
    var retryMaxTimes = 8
    var tokenYSPRetryTimes = 0
    var tokenYSPRetryMaxTimes = 0
    var tokenFHRetryTimes = 0
    var tokenFHRetryMaxTimes = 8

    var needGetToken = false

    private val _errInfo = MutableLiveData<String>()
    val errInfo: LiveData<String>
        get() = _errInfo

    private var _epg = MutableLiveData<MutableList<EPG>>()
    val epg: LiveData<MutableList<EPG>>
        get() = _epg

    private val _videoUrl = MutableLiveData<List<String>>()
    val videoUrl: LiveData<List<String>>
        get() = _videoUrl

    private val _videoIndex = MutableLiveData<Int>()
    val videoIndex: LiveData<Int>
        get() = _videoIndex

    private val _change = MutableLiveData<Boolean>()
    val change: LiveData<Boolean>
        get() = _change

    private val _ready = MutableLiveData<Boolean>()
    val ready: LiveData<Boolean>
        get() = _ready

    var seq = 0

    // ---- 源类型轮换（借鉴 my-tv-0）：HLS / PROGRESSIVE 两级 ----
    object SourceTypes {
        const val TYPE_HLS = 0
        const val TYPE_PROGRESSIVE = 1
    }

    private var sourceTypes: List<Int> = listOf(SourceTypes.TYPE_HLS, SourceTypes.TYPE_PROGRESSIVE)
    var sourceTypeIndex = 0
    private val confirmedTypes = mutableMapOf<String, Int>()

    /** 切换线路/首次播放时重置候选类型；已验证成功的类型优先 */
    fun resetSourceTypes() {
        val url = getVideoUrlCurrent()
        sourceTypes = if (url.substringBefore('?').substringAfterLast('/').contains(".m3u8")) {
            listOf(SourceTypes.TYPE_HLS, SourceTypes.TYPE_PROGRESSIVE)
        } else {
            listOf(SourceTypes.TYPE_PROGRESSIVE, SourceTypes.TYPE_HLS)
        }
        sourceTypeIndex = (confirmedTypes[url] ?: 0).coerceIn(0, sourceTypes.lastIndex)
    }

    /** 还有下一个候选类型返回 true 并推进 */
    fun nextSourceType(): Boolean {
        return if (sourceTypeIndex < sourceTypes.lastIndex) {
            sourceTypeIndex++
            true
        } else {
            false
        }
    }

    /** 播放成功后记忆该地址可用的源类型 */
    fun confirmSourceType() {
        confirmedTypes[getVideoUrlCurrent()] = sourceTypeIndex
    }

    val currentSourceType: Int
        get() = sourceTypes[sourceTypeIndex]

    fun addVideoUrl(url: String) {
        if (_videoUrl.value?.isNotEmpty() == true) {
            if (_videoUrl.value!!.last().contains("cctv.cn")) {
                tv.videoUrl = tv.videoUrl.subList(0, tv.videoUrl.lastIndex) + listOf(url)
            } else {
                tv.videoUrl = tv.videoUrl + listOf(url)
            }
        } else {
            tv.videoUrl = tv.videoUrl + listOf(url)
        }
        _videoUrl.value = tv.videoUrl
        _videoIndex.value = tv.videoUrl.lastIndex
    }

    fun firstSource() {
        if (_videoUrl.value!!.isNotEmpty()) {
            setVideoIndex(0)
            allReady()
        } else {
            Log.e(TAG, "no first")
        }
    }

    /** 轮转到下一条线路（循环），用于播放出错时自动换源 */
    fun nextSource() {
        val size = _videoUrl.value?.size ?: return
        if (size > 1) {
            val next = ((videoIndex.value ?: 0) + 1) % size
            setVideoIndex(next)
            resetSourceTypes()
            allReady()
        }
    }

    fun changed() {
        _change.value = true
    }

    fun allReady() {
        _ready.value = true
    }

    fun setVideoIndex(videoIndex: Int) {
        _videoIndex.value = videoIndex
    }

    init {
        _videoUrl.value = tv.videoUrl
        // 多线路频道（折叠后）从第一条（优选）线路开始播放
        _videoIndex.value = if (tv.videoUrl.isNotEmpty()) 0 else -1
    }

    fun getRowPosition(): Int {
        return rowPosition
    }

    fun getItemPosition(): Int {
        return itemPosition
    }

    fun setRowPosition(position: Int) {
        rowPosition = position
    }

    fun setItemPosition(position: Int) {
        itemPosition = position
    }

    fun setErrInfo(info: String) {
        _errInfo.value = info
    }

    fun getTV(): TV {
        return tv
    }

    private fun formatFTime(s: String): Int {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss")
        dateFormat.timeZone = TimeZone.getTimeZone("UTC")
        val date = dateFormat.parse(s.substring(0, 19))
        if (date != null) {
            return (date.time / 1000).toInt()
        }
        return 0
    }

    fun addFEPG(p: List<FEPG>) {
        _epg.value = p.map { EPG(it.title, formatFTime(it.event_time)) }.toMutableList()
    }

    /** 直连频道的 XMLTV 节目单 */
    fun addDirectEPG(p: List<EPG>) {
        if (p.isNotEmpty()) {
            _epg.value = p.toMutableList()
        }
    }

    fun getVideoUrlCurrent(): String {
        return _videoUrl.value!![_videoIndex.value!!]
    }

    companion object {
        private const val TAG = "TVViewModel"
    }
}