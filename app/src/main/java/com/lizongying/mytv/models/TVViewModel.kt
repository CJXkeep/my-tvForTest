package com.lizongying.mytv.models

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.lizongying.mytv.TV
import com.lizongying.mytv.TVList
import com.lizongying.mytv.api.FEPG
import java.text.SimpleDateFormat
import java.util.TimeZone

class TVViewModel(private var tv: TV) : ViewModel() {

    private var rowPosition: Int = 0
    private var itemPosition: Int = 0

    var tokenFHRetryTimes = 0
    var tokenFHRetryMaxTimes = 8

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
        val current = _videoUrl.value
        tv.videoUrl = if (!current.isNullOrEmpty() && current.last().contains("cctv.cn")) {
            // cctv.cn 地址是"换"而不是"追加"：它通常是同一路的临时凭证
            tv.videoUrl.dropLast(1) + listOf(url)
        } else {
            tv.videoUrl + listOf(url)
        }
        _videoUrl.value = tv.videoUrl
        _videoIndex.value = tv.videoUrl.lastIndex
    }

    fun firstSource() {
        if (!_videoUrl.value.isNullOrEmpty()) {
            setVideoIndex(0)
            allReady()
        } else {
            Log.e(TAG, "no source available: ${tv.title}")
            // 早期这里只打日志：没有线路 → 不触发播放 → 界面永远停在上一帧，用户没有任何反馈
            setErrInfo("${tv.title} 没有可用线路")
        }
    }

    /** 线路轮换尝试次数（重置时机：播放成功 / 切台） */
    var attemptCount = 0
        private set

    /**
     * 自动轮换的线路范围：默认只在前 [AUTO_LINE_LIMIT] 条（排序后的最优线）内轮换，
     * 避免在一堆低质线路上反复折腾；若用户手动选到更靠后的线路，则放开到全部线路。
     */
    private fun autoLimit(): Int {
        val size = _videoUrl.value?.size ?: 0
        if (size <= AUTO_LINE_LIMIT) return size
        return if ((videoIndex.value ?: 0) >= AUTO_LINE_LIMIT) size else AUTO_LINE_LIMIT
    }

    /** 自动轮换可用的线路数（供界面提示使用） */
    fun autoLineCount(): Int = autoLimit()

    /** 是否已达轮换上限（自动范围内轮换两轮仍失败则放弃，避免死循环） */
    fun isAttemptExhausted(): Boolean {
        val limit = autoLimit()
        return limit > 0 && attemptCount >= limit * 2
    }

    fun resetAttempts() {
        attemptCount = 0
    }

    /** 轮转到下一条线路（循环），用于播放出错时自动换源 */
    fun nextSource() {
        val size = _videoUrl.value?.size ?: return
        if (size > 1) {
            attemptCount++
            val limit = autoLimit().coerceAtLeast(1)
            val next = ((videoIndex.value ?: 0) + 1) % limit
            setVideoIndex(next)
            resetSourceTypes()
            allReady()
        }
    }

    /** 源类型轮换也计入尝试次数 */
    fun countAttempt() {
        attemptCount++
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

    /** 当前线路地址；无可用线路时返回空串（调用方必须判空，避免直接 NPE 崩溃） */
    fun getVideoUrlCurrent(): String {
        val list = _videoUrl.value
        if (list.isNullOrEmpty()) return ""
        val index = (_videoIndex.value ?: 0).coerceIn(0, list.lastIndex)
        return list[index]
    }

    /** 是否存在可播放线路 */
    fun hasSource(): Boolean = !_videoUrl.value.isNullOrEmpty()

    /**
     * 运行时重排线路（探活完成 / 播放成功后调用），让"实测优选"不再等到下次启动。
     *
     * 两条约束：
     * - **不裁剪**（`trim = false`）：运行时裁剪可能把正在播放的那条裁到保留位之外；
     * - **索引跟着走**：重排后把 [videoIndex] 重新定位到同一个地址，
     *   这样"列表顺序变了，但播放器持有的 URL 没变"，播放不会被打断。
     */
    fun resortLines() {
        val current = _videoUrl.value ?: return
        if (current.size <= 1) return
        val playing = getVideoUrlCurrent()
        TVList.sortLines(tv, trim = false)
        val reordered = tv.videoUrl
        if (reordered == current) return
        _videoUrl.value = reordered
        val idx = reordered.indexOf(playing)
        _videoIndex.value = if (idx >= 0) idx else 0
        Log.i(TAG, "resort lines: ${tv.title} -> ${reordered.size} lines, now playing #${idx + 1}")
    }

    companion object {
        /**
         * 自动轮换只用排序后最优的前 N 条线路（"优中选优"），
         * 其余线路仍保留在列表里，用户可在线路列表中手动切换。
         */
        const val AUTO_LINE_LIMIT = 2

        private const val TAG = "TVViewModel"
    }
}