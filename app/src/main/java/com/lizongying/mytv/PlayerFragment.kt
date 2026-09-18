package com.lizongying.mytv

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.fragment.app.Fragment
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.ui.PlayerView
import com.lizongying.mytv.databinding.PlayerBinding
import com.lizongying.mytv.models.ProgramType
import com.lizongying.mytv.models.TVViewModel
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.UnknownHostException


/**
 * 播放页：统一使用 Media3 ExoPlayer 单引擎（不再区分天猫专用旧引擎）。
 *
 * 失败重试顺序：源类型轮换 → 线路轮换 → 自动降级软解重试一次 → 明确报错。
 * 软解成功后本会话内后续频道直接用软解，替代原先让用户手动开的"软解开关"。
 */
@OptIn(UnstableApi::class)
class PlayerFragment : Fragment() {

    private var _binding: PlayerBinding? = null
    private var playerView: PlayerView? = null
    private var tvViewModel: TVViewModel? = null
    private val aspectRatio = 16f / 9f

    /** 当前播放器是否以软解优先构建 */
    private var softDecode = false

    /** 本会话是否已尝试过软解降级，防止无限重建 */
    private var softDecodeTried = false

    /** 轨道选择器：用于"画质优先"与卡顿后自动放宽 */
    private var trackSelector: DefaultTrackSelector? = null

    /** 是否已因卡顿放宽过画质（本会话只降一次，避免来回抖动） */
    private var qualityRelaxed = false

    /** 当前频道是否已成功播放过：用于区分首帧缓冲与播放中的卡顿 */
    private var hasPlayed = false

    private val rebufferTimes = mutableListOf<Long>()

    /**
     * 缓冲看门狗。
     * 只在"加载中"期间运行：无论有没有错误回调，首帧迟迟不来就主动判失败——
     * 连通了却不发数据的"假活"线路、直播流中途彻底卡死，都不会产生 error 回调，
     * 早期版本这种情况下转圈图标会一直留在屏幕上。
     */
    private val handler = Handler(Looper.getMainLooper())
    private val watchdog = Runnable { onBufferTimeout() }

    /** 是否已进入终态（所有线路均不可用）：避免重复提示与重复自动跳过 */
    private var terminal = false

    /** 首帧超时次数：同一频道内累加，换频道时清零 */
    private var timeoutCount = 0

    /** 上一次起播的频道 id：用于区分"换频道"与"同频道自动换线" */
    private var lastChannelId = -1

    /**
     * 本次起播的起点时刻（[SystemClock.elapsedRealtime]）。
     * 用于测量"真正开始加载 → 画面渲染出来"的耗时——
     * 没有这个数字，"换台快没快"就永远只能靠感觉，也无法判断瓶颈在网络还是解码。
     */
    private var playStartAt = 0L

    /**
     * 起播时间线的计时基准（见 [trace]）。
     * 与 [playStartAt] 分开：后者在首帧出来后被清零，而时间线要覆盖到首帧**之后**——
     * 接管之后是否立刻回补分片，正是"继承到的缓冲够不够"的直接证据。
     */
    private var traceBaseAt = 0L

    /**
     * 待命播放器（"预加载下一频道"开启时才有）：提前把下一个频道的流缓冲好，
     * 换台时直接接管渲染，省掉整段加载过程。
     *
     * 它**不渲染、不播放**，只负责缓冲；因此绝不能挂 [playerListener]——
     * 那个监听器会显示加载遮罩、写播放统计、触发自动跳过，都是"当前频道"才该做的事。
     */
    private var standbyPlayer: ExoPlayer? = null

    /** 待命播放器正在预备的频道 id（-1 表示没有） */
    private var standbyChannelId = -1

    /** 待命开始 prepare 的时刻：用于量化"多久才能接管"（见 [standbyListener]） */
    private var standbyStartAt = 0L

    /**
     * 待命加载失败的频道：id → 失败时刻（[SystemClock.elapsedRealtime]）。
     *
     * 起播初期 [onIsPlayingChanged] 会触发多次（每次卡顿恢复都算一次），每次都重排 [preloadRunnable]；
     * 而待命失败会把 [standbyPlayer] 置空，于是下一次排程就**原样重建**同一个台——
     * 实测出现"failed 之后 124ms 又 prepare 同一个连不上的台"，白等一个连接超时。
     */
    private val standbyFailed = HashMap<Int, Long>()

    /**
     * 本次换台的方向：+1 向下、-1 向上。
     * 只朝一个方向预加载——单台待命播放器无法同时预备上下两个频道，
     * 而用户换台通常有方向性（一直按频道+，或一直按频道-）。
     *
     * 例外见 [flipFlop]：上下来回翻台时方向没有意义。
     */
    private var preloadOffset = 1

    /** 上一次换台前所在的频道 id（-1 表示还没有）。用于判定"来回翻台"，见 [flipFlop] */
    private var previousChannelId = -1

    /**
     * 本次是否属于"上下来回翻台"：按到了上一次换台前所在的那个台（A→B→A）。
     *
     * 这种按法的下一个目标**不是**列表里的相邻台，而是刚离开的那个台——
     * 按方向预加载会把待命播放器备成一个用户根本不会去的台：
     * 每翻一次就丢掉一个解码器实例+一段缓冲，再为错误的方向重建，
     * 结果整个来回过程里只有第一次能吃到接管，之后每次都是整段重载。
     */
    private var flipFlop = false

    /**
     * 本次起播是否由"待命接管"完成。
     * 接管意味着画面来自已经缓冲充足的流，可以更快开始准备再下一个
     * （普通起播时当前流还在补缓冲，拉下一路会互相拖慢）。
     */
    private var promotedStart = false

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = PlayerBinding.inflate(inflater, container, false)
        playerView = _binding!!.playerView
        // 换台时保留上一帧画面，而不是立刻黑屏：这是"切换即有台"的观感基础。
        // 没有它，setMediaSource() 会让 PlayerView 清空画面，用户先看到一段黑屏再等新台出画。
        playerView!!.setKeepContentOnPlayerReset(true)
        playerView!!.player = buildPlayer(requireContext(), softDecode)
        playerView!!.player?.playWhenReady = true
        playerView!!.player?.addListener(playerListener)
        (playerView!!.player as? ExoPlayer)?.addAnalyticsListener(analyticsListener)

        (activity as? MainActivity)?.fragmentReady("PlayerFragment")
        return _binding!!.root
    }

    /** 构建播放器（可按需启用软解优先 / 待命用的低缓冲水位） */
    private fun buildPlayer(context: Context, soft: Boolean, standby: Boolean = false): ExoPlayer {
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(UA)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(8000)
            .setReadTimeoutMs(8000)
        val dataSourceFactory = DefaultDataSource.Factory(context, httpDataSourceFactory)
        val renderersFactory = DefaultRenderersFactory(context).apply {
            if (soft) {
                // 软解码优先：优先选择软件编解码器，兼容部分设备硬解花屏/无声
                setMediaCodecSelector(softwareFirstSelector)
            }
        }
        // 画质优先：默认强制最高码率；检测到持续卡顿后放宽
        val selector = DefaultTrackSelector(context).apply {
            setParameters(qualityParams(context, forceHighest = !qualityRelaxed))
        }
        trackSelector = selector
        softDecode = soft
        // 起播阈值见 [BUFFER_FOR_PLAYBACK_MS]：默认 2500ms 意味着切台时要白等这么久。
        // 待命播放器另用一套更低的缓冲水位——它只需要"够接管"（见 [STANDBY_MIN_BUFFER_MS]）。
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                if (standby) STANDBY_MIN_BUFFER_MS else MIN_BUFFER_MS,
                if (standby) STANDBY_MAX_BUFFER_MS else MAX_BUFFER_MS,
                BUFFER_FOR_PLAYBACK_MS,
                BUFFER_AFTER_REBUFFER_MS,
            )
            .build()

        return ExoPlayer.Builder(context, renderersFactory)
            .setTrackSelector(selector)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(dataSourceFactory).setLoadErrorHandlingPolicy(loadErrorPolicy)
            )
            .setLoadControl(loadControl)
            .build()
    }

    /**
     * 画质策略：
     * - 优先：强制选择最高码率轨道，先把清晰度拉满；
     * - 放宽：解除强制并限制码率上限，保证弱网下不卡。
     */
    private fun qualityParams(context: Context, forceHighest: Boolean): TrackSelectionParameters {
        val builder = TrackSelectionParameters.Builder(context)
            .setForceHighestSupportedBitrate(forceHighest)
        if (!forceHighest) {
            builder.setMaxVideoBitrate(RELAXED_MAX_BITRATE)
        }
        return builder.build()
    }

    /** 播放中反复缓冲：判定网络吃不消，自动放宽画质并提示 */
    private fun onRebuffer() {
        if (!hasPlayed || qualityRelaxed) return
        val now = SystemClock.elapsedRealtime()
        rebufferTimes.add(now)
        rebufferTimes.removeAll { now - it > REBUFFER_WINDOW_MS }
        if (rebufferTimes.size >= REBUFFER_LIMIT) {
            qualityRelaxed = true
            Log.i(TAG, "network unstable, relax video quality")
            val context = context ?: return
            trackSelector?.setParameters(qualityParams(context, forceHighest = false))
            (activity as? MainActivity)?.showInfoMessage("网络不稳定，已自动降低画质")
        }
    }

    /** 重建播放器（硬解 ↔ 软解切换），保持监听与播放状态 */
    private fun rebuildPlayer(soft: Boolean) {
        val context = activity ?: return
        // 软解重建时先释放待命播放器：它可能是硬解实例，白占着解码器
        releaseStandby()
        val old = playerView?.player
        playerView?.player = null
        old?.removeListener(playerListener)
        (old as? ExoPlayer)?.removeAnalyticsListener(analyticsListener)
        old?.release()

        val player = buildPlayer(context, soft)
        player.playWhenReady = true
        player.addListener(playerListener)
        player.addAnalyticsListener(analyticsListener)
        playerView?.player = player
    }

    /**
     * 起播分段追踪。
     *
     * 只用 [Player.Listener] 看不清时间花在哪：m3u8 请求、首个分片、解码器初始化
     * 都发生在 ExoPlayer 内部，最终只体现为一个"first frame in 3xxx ms"。
     * 要判断启动首帧（实测 2.5~3.5s）该往哪儿优化，必须先有这条时间线。
     */
    private val analyticsListener = object : AnalyticsListener {
        override fun onLoadStarted(
            eventTime: AnalyticsListener.EventTime,
            loadEventInfo: LoadEventInfo,
            mediaLoadData: MediaLoadData,
        ) {
            trace(eventTime.realtimeMs, "load → ${shortUri(loadEventInfo.uri.toString())}")
        }

        override fun onLoadCompleted(
            eventTime: AnalyticsListener.EventTime,
            loadEventInfo: LoadEventInfo,
            mediaLoadData: MediaLoadData,
        ) {
            trace(
                eventTime.realtimeMs,
                "done ← ${shortUri(loadEventInfo.uri.toString())} ${loadEventInfo.bytesLoaded}B",
            )
            // 本次起播的第一个响应（播放列表）回来了：uri 就是跳转落点，记下来
            if (pendingResolveUrl.isNotBlank()) {
                ResolvedUrl.remember(pendingResolveUrl, loadEventInfo.uri.toString())
                pendingResolveUrl = ""
            }
        }

        /**
         * 请求失败。主动取消（切台 / 看门狗判死后的换线路）不算失败，但**也要留一行**：
         * 否则那条被看门狗晾了 15 秒的线路会在日志里留下一段完全空白，看不出发生过什么。
         * [Player.Listener] 只给一个笼统的错误码，失败原因只有这里看得到。
         */
        override fun onLoadError(
            eventTime: AnalyticsListener.EventTime,
            loadEventInfo: LoadEventInfo,
            mediaLoadData: MediaLoadData,
            error: IOException,
            wasCanceled: Boolean,
        ) {
            trace(
                eventTime.realtimeMs,
                "${if (wasCanceled) "cancel" else "fail"} ← " +
                        "${shortUri(loadEventInfo.uri.toString())} ${error.message}",
                keepAfterWindow = true,
            )
        }

        /** 拿到视频流格式：说明封装已解析，接下来才是解码器初始化与首帧渲染 */
        override fun onVideoInputFormatChanged(
            eventTime: AnalyticsListener.EventTime,
            format: Format,
            decoderReuseEvaluation: DecoderReuseEvaluation?,
        ) {
            trace(
                eventTime.realtimeMs,
                "format ${format.sampleMimeType} ${format.width}x${format.height}",
            )
        }
    }

    /**
     * 打印一条起播时间线。只在起播窗口内输出——直播分片会持续加载，全量打印会刷屏。
     *
     * @param keepAfterWindow 失败原因要越过窗口保留：慢失败（例如 15 秒才超时的那条线路）
     *   正好落在窗口之外，而那恰恰是最需要知道原因的时候。失败频率远低于分片加载，不会刷屏。
     */
    private fun trace(realtimeMs: Long, message: String, keepAfterWindow: Boolean = false) {
        if (traceBaseAt <= 0L) return
        val offset = realtimeMs - traceBaseAt
        if (offset < 0) return
        if (offset > TRACE_WINDOW_MS && !keepAfterWindow) return
        Log.i(TAG, "[+${offset}ms] $message")
    }

    /** 本次起播用的是否是 [ResolvedUrl] 记下的 302 落点（失败时要退回原始地址） */
    private var resolvedUrlInUse = false

    /**
     * 本次起播线路的**原始**地址，用于在第一个响应回来后记录 302 落点。
     *
     * 只靠预热记录落点是不够的：[MainFragment.preheatAround] 预热的是相邻台、**不含当前台**，
     * 所以"启动后看的第一个台"和"刚切过去的台"都只能走原始地址（多一次跳转）。
     * 而播放本身已经把跳转走完了，顺手记下来，下次（尤其是来回翻台切回来时）就能直接命中落点。
     */
    private var pendingResolveUrl = ""

    /** 地址摘要：完整 URL 常带 token 与长路径，直接打会淹没日志 */
    private fun shortUri(url: String): String {
        val path = url.substringAfter("://", url).substringBefore('?')
        return if (path.length <= 48) path else "…" + path.takeLast(48)
    }

    private val playerListener = object : Player.Listener {
        override fun onVideoSizeChanged(videoSize: VideoSize) {
            // 把真实分辨率回写到线路档案：画质排序/展示优先用实测值。
            // 探活多数源拿不到 RESOLUTION（单码率列表），播放器的是覆盖最广的实测来源。
            if (videoSize.height > 0) {
                tvViewModel?.let { vm ->
                    ChannelProbe.recordPlaybackSize(
                        vm.getVideoUrlCurrent(), videoSize.width, videoSize.height
                    )
                    vm.resortLines()
                }
            }
            val view = playerView ?: return
            val width = view.measuredWidth
            val height = view.measuredHeight
            // 该回调可能早于布局完成：此时宽高为 0，直接相除会抛 ArithmeticException
            if (width <= 0 || height <= 0) return
            val ratio = width.toFloat() / height
            val layoutParams = view.layoutParams
            if (ratio < aspectRatio) {
                layoutParams.height = (width / aspectRatio).toInt()
                view.layoutParams = layoutParams
            } else if (ratio > aspectRatio) {
                layoutParams.width = (height * aspectRatio).toInt()
                view.layoutParams = layoutParams
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            super.onPlaybackStateChanged(playbackState)
            when (playbackState) {
                Player.STATE_BUFFERING -> {
                    showLoading()
                    onRebuffer()
                }
                Player.STATE_READY, Player.STATE_ENDED -> hideLoading()
            }
        }

        /**
         * 首帧真正渲染出来的时刻——这才是用户眼中的"有台了"。
         * 不用 STATE_READY：那只代表"可以播了"，画面还可能没上屏。
         */
        override fun onRenderedFirstFrame() {
            super.onRenderedFirstFrame()
            // 画面已出来：放行此前一直让路给起播的后台探活
            (activity as? MainActivity)?.onFirstFrameRendered()
            val vm = tvViewModel ?: return
            if (playStartAt <= 0L) return
            Log.i(
                TAG,
                "first frame in ${SystemClock.elapsedRealtime() - playStartAt}ms: " +
                        "${vm.getTV().title} [${vm.getVideoUrlCurrent()}]"
            )
            playStartAt = 0L
        }

        override fun onPlayerError(error: PlaybackException) {
            super.onPlayerError(error)
            Log.e(TAG, "PlaybackException $error")
            hideLoading()
            retryOnError(error)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            super.onIsPlayingChanged(isPlaying)
            if (isPlaying) {
                hideLoading()
                hasPlayed = true
                // 软解播放成功：本会话后续频道直接用软解
                if (softDecode) softDecodeTried = true
                // 该线路确实能播：累计成功次数，并按最新数据重排（这条线路会升到前面）。
                // 重排只改顺序、不裁剪，索引会跟着当前地址走，所以不会打断播放。
                // 注：播放失败时**不**重排——那会改变"下一条线路"的语义，干扰自动轮换。
                tvViewModel?.let {
                    LineHealth.recordSuccess(it.getVideoUrlCurrent())
                    it.resortLines()
                }
                tvViewModel?.confirmSourceType()
                tvViewModel?.resetAttempts()
                (activity as? MainActivity)?.isPlaying()
                // 准备下一个频道。接管后可以更快开始（画面来自已缓冲充足的流）；
                // 普通起播则要等一等，否则会和还在补缓冲的当前流抢带宽。
                handler.removeCallbacks(preloadRunnable)
                val delay = when {
                    // 接管的画面来自已经缓冲充足的流，再拉一路几乎不影响它
                    promotedStart -> PRELOAD_DELAY_AFTER_PROMOTE_MS
                    // 折回且没接管：当前台是整段重载起来的，但它同时也是"刚播过"的台，
                    // 流在 CDN 侧是热的，所以可以比冷启动的相邻台更早动手
                    flipFlop -> PRELOAD_DELAY_FLIP_FLOP_MS
                    else -> PRELOAD_DELAY_MS
                }
                // 记下实际使用的档位：只有这个数字，"等多久才轮到预加载"才说得清
                // （first frame 到 prepare 的间隔还会叠加 isPlaying 的触发时机，不能反推）
                Log.i(TAG, "standby: schedule in ${delay}ms (took over=$promotedStart, flip=$flipFlop)")
                handler.postDelayed(preloadRunnable, delay)
            }
        }
    }

    fun play(tvViewModel: TVViewModel) {
        this.tvViewModel = tvViewModel
        val newId = tvViewModel.getTV().id
        // 只有"换频道"才清零重试计数。
        // 自动换线同样会走这里，早期无条件 resetAttempts() 会把上限反复清零，
        // 结果就是死台永远重试下去（日志里每 3 秒一个 Source error，停不下来）。
        if (lastChannelId != newId) {
            // 记下换台方向：预加载只朝这个方向准备，避免预备了一个用户根本不会去的台
            preloadOffset = if (lastChannelId < 0 || newId > lastChannelId) 1 else -1
            // 换回上一次换台前所在的那个台 = 用户在上下反复翻台（A→B→A）。
            // 只认"一步折回"，一直朝同一方向连按不会被误判成来回翻。
            flipFlop = newId == previousChannelId
            previousChannelId = lastChannelId
            lastChannelId = newId
            timeoutCount = 0
            tvViewModel.resetAttempts()
            // 用户真的切到了这个台：清掉它的待命失败记录，下次轮到它当"下一个"时可以重新尝试
            standbyFailed.remove(newId)
        }
        // 新频道重新开始卡顿统计（首帧缓冲不计入）
        hasPlayed = false
        terminal = false
        rebufferTimes.clear()
        playStartAt = SystemClock.elapsedRealtime()
        traceBaseAt = playStartAt
        showLoading()
        // 线路变了要重新判断封装类型（同一个 URL 的类型记忆仍会命中）
        tvViewModel.resetSourceTypes()

        // 预加载命中：目标频道已经在待命播放器里缓冲好，直接接管渲染。
        // 这是唯一能真正做到"切换就有台"的路径——它省掉的不是握手，而是整段加载。
        //
        // 清排程必须在接管**之前**：接管会同步触发 onIsPlayingChanged，
        // 那里会为"新的下一个频道"重新排程；顺序反了就会把刚排上的任务又取消掉，
        // 表现是"只有第一次快，之后再也等不到待命播放器"。
        //
        // 同理，promotedStart 也必须在接管之前置位——它会被那次同步回调读到。
        promotedStart = true
        handler.removeCallbacks(preloadRunnable)
        if (promoteStandby(newId)) {
            return
        }
        promotedStart = false
        applyCurrentSource()
    }

    /** 把当前线路与源类型应用到播放器 */
    private fun applyCurrentSource() {
        val vm = tvViewModel ?: return
        if (vm.getVideoUrlCurrent().isBlank()) {
            // 线路被全部过滤/异常时不带空地址去 prepare，否则会抛异常崩溃
            Log.e(TAG, "no playable source: ${vm.getTV().title}")
            enterTerminal("${vm.getTV().title} 暂无可播放线路")
            return
        }
        // 记录本次是否走了落点：失败时据此决定要不要退回原始地址（见 retryOnError）
        resolvedUrlInUse = ResolvedUrl.find(vm.getVideoUrlCurrent()) != null
        pendingResolveUrl = vm.getVideoUrlCurrent()
        val context = context ?: return
        (playerView?.player as? ExoPlayer)?.apply {
            setMediaSource(buildMediaSource(context, vm))
            prepare()
        }
    }

    // ---------------- 预加载下一频道（D 方案，由设置开关控制） ----------------

    private val preloadRunnable = Runnable { prepareStandby() }

    /**
     * 为下一个频道准备待命播放器。
     * 只在起播成功后再延迟 [PRELOAD_DELAY_MS] 调用——刚起播时那条流还在缓冲，立刻再拉一路会互相拖慢。
     */
    private fun prepareStandby() {
        if (!SP.preloadNext) return
        val ctx = activity ?: return
        val main = ctx as? MainActivity ?: return
        // 来回翻台时备"刚离开的台"（用户大概率按回去），其余情况才按方向备相邻台。
        // 列表只有一个频道时 [neighborTVViewModel] 返回 null，这里一并不做预加载。
        val next = if (flipFlop) {
            main.tvViewModelById(previousChannelId)
        } else {
            main.neighborTVViewModel(preloadOffset)
        } ?: return
        val nextId = next.getTV().id
        if (nextId == lastChannelId) return                              // 只有这一个频道
        if (standbyPlayer != null && standbyChannelId == nextId) return   // 已经备好
        if (isStandbyFailed(nextId)) {
            Log.i(TAG, "standby: skip #$nextId (failed recently)")
            return
        }

        releaseStandby()
        val player = buildPlayer(ctx, softDecode, standby = true)
        player.playWhenReady = false                                     // 只缓冲：不出声、不渲染
        player.addListener(standbyListener)
        player.setMediaSource(buildMediaSource(ctx, next))
        standbyStartAt = SystemClock.elapsedRealtime()
        player.prepare()
        standbyPlayer = player
        standbyChannelId = nextId
        Log.i(
            TAG,
            "standby: prepare ${next.getTV().title} [${next.getVideoUrlCurrent()}]" +
                    if (flipFlop) " (flip-flop)" else ""
        )
    }

    /** 释放待命播放器（关闭开关、软解重建、退出时都要调用） */
    private fun releaseStandby() {
        standbyPlayer?.removeListener(standbyListener)
        standbyPlayer?.release()
        standbyPlayer = null
        standbyChannelId = -1
    }

    /**
     * 该频道是否还在待命失败的静默期内。
     * 过期即自动放行——源站可能已经恢复，频道线路也可能被探活重排过了。
     */
    private fun isStandbyFailed(id: Int): Boolean {
        val at = standbyFailed[id] ?: return false
        if (SystemClock.elapsedRealtime() - at > STANDBY_FAILED_TTL_MS) {
            standbyFailed.remove(id)
            return false
        }
        return true
    }

    /**
     * 让待命播放器接管渲染；返回 true 表示已接管，调用方不要再走普通加载路径。
     * 没缓冲好就不接管——否则只是换了个播放器重新加载，白折腾。
     */
    private fun promoteStandby(channelId: Int): Boolean {
        if (!SP.preloadNext) return false
        val stand = standbyPlayer ?: return false
        if (standbyChannelId != channelId) return false
        if (stand.playbackState != Player.STATE_READY) return false
        val old = playerView?.player as? ExoPlayer
        if (old === stand) return false

        Log.i(
            TAG,
            "standby: promote #$channelId (elapsed ${SystemClock.elapsedRealtime() - playStartAt}ms)"
        )
        old?.removeListener(playerListener)
        old?.removeAnalyticsListener(analyticsListener)
        stand.removeListener(standbyListener)
        playerView?.player = stand
        stand.addListener(playerListener)
        stand.addAnalyticsListener(analyticsListener)
        stand.playWhenReady = true

        standbyPlayer = null
        standbyChannelId = -1
        // 旧播放器立刻释放：保证任何时刻最多两个解码器实例，不会出现新旧待命三份并存
        old?.release()
        return true
    }

    /** 设置里的"预加载下一频道"被改动时调用 */
    fun onPreloadSettingChanged() {
        handler.removeCallbacks(preloadRunnable)
        if (!SP.preloadNext) {
            releaseStandby()
            return
        }
        if (hasPlayed) handler.postDelayed(preloadRunnable, PRELOAD_DELAY_MS)
    }

    /**
     * 待命播放器专用监听。
     * **不能复用 [playerListener]**：那会触发加载遮罩、播放统计、自动跳过等"当前频道才该做"的动作。
     * 预加载失败也不必补偿——换台时自然会走普通加载路径。
     */
    private val standbyListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) {
                // 这个数字就是"用户按多快才能吃到接管"的临界点：从 prepare 到可接管
                Log.i(
                    TAG,
                    "standby: ready #$standbyChannelId (+${SystemClock.elapsedRealtime() - standbyStartAt}ms)"
                )
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.i(TAG, "standby: failed ${error.errorCodeName}")
            // 记进黑名单：待命失败后 onIsPlayingChanged 还会再次排程，
            // 不挡的话会对同一个连不上的台反复重建（每次白等一个连接超时）
            if (standbyChannelId >= 0) {
                standbyFailed[standbyChannelId] = SystemClock.elapsedRealtime()
            }
            // 必须显式 release：仅置空引用不会释放解码器、加载线程与连接，只能等 GC 兜底
            standbyPlayer?.release()
            standbyPlayer = null
            standbyChannelId = -1
        }
    }

    /**
     * 按当前源类型构建 MediaSource，应用频道自定义 headers。
     * context 由调用方传入：本函数可经看门狗/错误重试等异步路径触发，
     * Fragment 可能已 detach，requireContext() 会抛 IllegalStateException。
     */
    private fun buildMediaSource(context: Context, tvViewModel: TVViewModel): MediaSource {
        // 优先用 302 落点：省掉一轮跳转往返（落点由 StreamPreheat 预热时顺手记下，见 ResolvedUrl）
        val url = ResolvedUrl.find(tvViewModel.getVideoUrlCurrent())
            ?: tvViewModel.getVideoUrlCurrent()
        val mime = if (tvViewModel.currentSourceType == TVViewModel.SourceTypes.TYPE_HLS) {
            MimeTypes.APPLICATION_M3U8
        } else {
            MimeTypes.VIDEO_MP2T
        }
        val headers = tvViewModel.getTV().headers
        val ua = headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value
            ?: UA
        // 必须是 DefaultHttpDataSource：它底层的 HttpURLConnection 连接池是进程级共享的，
        // StreamPreheat 用同一种数据源预热，握手结果才能被这里的起播直接复用。
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(ua)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(8000)
            .setReadTimeoutMs(8000)
            .setDefaultRequestProperties(
                headers.filterKeys { !it.equals("User-Agent", true) }
            )
        val dataSourceFactory = DefaultDataSource.Factory(context, httpFactory)
        val factory = DefaultMediaSourceFactory(dataSourceFactory)
            .setLoadErrorHandlingPolicy(loadErrorPolicy)
        val item = MediaItem.Builder().setUri(url).setMimeType(mime).build()
        return factory.createMediaSource(item)
    }

    /** 播放失败重试：源类型 → 线路 → 软解降级；达到上限则进入终态 */
    private fun retryOnError(error: PlaybackException? = null) {
        val vm = tvViewModel ?: return
        // 走了 302 落点却失败：落点可能带时效参数已过期（见 ResolvedUrl）。
        // 先忘掉它、退回原始地址重试一次——否则会把一条本来能播的线路直接判死。
        if (resolvedUrlInUse) {
            resolvedUrlInUse = false
            Log.i(TAG, "resolved url failed, fall back to original: ${vm.getVideoUrlCurrent()}")
            ResolvedUrl.forget(vm.getVideoUrlCurrent())
            applyCurrentSource()
            return
        }
        val sourceError = isSourceError(error)
        val limit = vm.autoLineCount().coerceAtLeast(1)

        // 源/网络类错误只在"每条线路各试一次"后判定不可用：
        // 换封装类型、换软解都救不回 HTTP 4xx/5xx 或连接被重置，多试只是让用户多等几个 8 秒超时。
        val exhausted = if (sourceError) vm.attemptCount >= limit else vm.isAttemptExhausted()

        if (exhausted) {
            // 解码/渲染类失败才值得降级软解重试一次
            if (!softDecodeTried && !sourceError) {
                softDecodeTried = true
                Log.i(TAG, "fallback to software decode: ${vm.getTV().title}")
                vm.resetAttempts()
                rebuildPlayer(soft = true)
                applyCurrentSource()
                (activity as? MainActivity)?.showInfoMessage("${vm.getTV().title} 尝试软解")
                return
            }
            Log.e(TAG, "all sources exhausted: ${vm.getTV().title} sourceError=$sourceError")
            // 最终放弃：记录当前线路，避免下次启动又从头试一遍
            LineHealth.recordFail(vm.getVideoUrlCurrent())
            enterTerminal("${vm.getTV().title} 所有线路均不可用")
            return
        }

        if (!sourceError && vm.nextSourceType()) {
            vm.countAttempt()
            Log.i(TAG, "retry sourceType ${vm.sourceTypeIndex}")
            applyCurrentSource()
        } else if (vm.getTV().programType == ProgramType.DIRECT
            && (vm.videoUrl.value?.size ?: 0) > 1
        ) {
            // 放弃这条线路：记下来，冷却期内不再优先尝试
            LineHealth.recordFail(vm.getVideoUrlCurrent())
            vm.nextSource()
            // 提示放在切换**之后**：窗口会随失败次数放宽，先切才能报出准确的"第几条 / 共几条"。
            // 自动换线属正常过程，走信息条而非弹窗。
            (activity as? MainActivity)?.showInfoMessage(
                "${vm.getTV().title} 线路 ${(vm.videoIndex.value ?: 0) + 1}/${vm.autoLineCount()}"
            )
        } else if (vm.getTV().programType == ProgramType.DIRECT) {
            // 单线路直连频道：计数后原地重试（同一 URL，不经去重逻辑）
            vm.countAttempt()
            applyCurrentSource()
        } else {
            vm.changed()
        }
    }

    /** 延迟显示加载遮罩：起播够快时用户根本看不到它，看到的是上一帧直接切到新画面 */
    private val showLoadingRunnable = Runnable {
        _binding?.loading?.visibility = View.VISIBLE
    }

    private fun showLoading() {
        // 已进入终态就不再显示加载：否则"放弃后仍在转圈"的老问题会回来
        if (terminal) return
        // 遮罩延迟出现，但看门狗立刻计时——超时语义不能因为"看不到转圈"而被推迟
        handler.removeCallbacks(showLoadingRunnable)
        handler.postDelayed(showLoadingRunnable, LOADING_SHOW_DELAY_MS)
        handler.removeCallbacks(watchdog)
        handler.postDelayed(watchdog, BUFFER_TIMEOUT_MS)
    }

    private fun hideLoading() {
        handler.removeCallbacks(showLoadingRunnable)
        handler.removeCallbacks(watchdog)
        _binding?.loading?.visibility = View.GONE
    }

    /**
     * 缓冲超时（既没出画面、也没有错误回调）。
     *
     * 与"播放报错"区分对待：报错多半是封装/解码不兼容，换源类型有意义；
     * 而**静默无数据**说明这条线本身就是死的，原地换封装类型只是白等一个超时周期，
     * 所以这里直接换下一条线路。连续 [TIMEOUT_LIMIT] 次仍无画面就进入终态，
     * 让用户尽早知道"这个台看不了"，而不是一直盯着转圈。
     */
    private fun onBufferTimeout() {
        val vm = tvViewModel ?: return
        if (terminal) return
        timeoutCount++
        Log.e(
            TAG,
            "buffer timeout ${BUFFER_TIMEOUT_MS / 1000}s (#$timeoutCount): ${vm.getTV().title} " +
                    "played=$hasPlayed url=${vm.getVideoUrlCurrent()}"
        )
        LineHealth.recordFail(vm.getVideoUrlCurrent())

        // 每条线路给一次超时机会：自动窗口内的线路都超时过，才判这个台不行。
        // 门槛随窗口放宽（2 → 3），但封顶 [TIMEOUT_LIMIT_MAX]——
        // 一次超时 15s，不能让用户在"试第 4 条"上又多干等一个周期。
        val lines = vm.videoUrl.value?.size ?: 0
        val multiLine = vm.getTV().programType == ProgramType.DIRECT && lines > 1
        val timeoutLimit = if (multiLine) {
            vm.autoLineCount().coerceIn(TIMEOUT_LIMIT, TIMEOUT_LIMIT_MAX)
        } else {
            TIMEOUT_LIMIT
        }
        if (timeoutCount >= timeoutLimit) {
            enterTerminal("${vm.getTV().title} 线路无响应")
            return
        }
        if (multiLine) {
            vm.nextSource()
        } else {
            retryOnError()
        }
    }

    /** 是否属于"源/网络"类错误（软解无法改善），判断依据是 ExoPlayer 的错误码 */
    private fun isSourceError(error: PlaybackException?): Boolean {
        if (error == null) return false
        return when (error.errorCode) {
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
            PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
            PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED,
            PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE -> true

            else -> false
        }
    }

    /**
     * 加载层重试策略：确定性失败不再等退避。
     *
     * 默认策略对同一条线路重试 3 次（退避 1s → 2s → …）。对"连接被重置 / 拒绝 / 域名解析不了"
     * 这类**当场就返回**的失败，重试几乎必然得到同样结果——实测 BRTV 的首选线路
     * `Connection reset` 在 41ms 就失败，之后却白等 3.4 秒才轮到上层换线路。
     * 这与 [retryOnError] 里"源类错误多试只是让用户多等几个超时"的判断是矛盾的。
     *
     * 这里只拦"第二次同类失败"：第一次仍走默认策略快速重试（瞬时抖动是常态），
     * 第二次起直接返回 [C.TIME_UNSET] 结束加载，把恢复动作交给线路轮换那套机制。
     * 超时（[java.net.SocketTimeoutException]）**不算**确定性失败——拥塞是临时的，值得重试。
     */
    private val loadErrorPolicy = object : DefaultLoadErrorHandlingPolicy() {
        override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
            if (loadErrorInfo.errorCount > 1 && isDeterministicFailure(loadErrorInfo.exception)) {
                Log.i(
                    TAG,
                    "give up retry x${loadErrorInfo.errorCount}: ${loadErrorInfo.exception.message}"
                )
                return C.TIME_UNSET
            }
            return super.getRetryDelayMsFor(loadErrorInfo)
        }
    }

    /** 是否为"重试也不会变"的确定性失败（逐层看 cause，数据源常把它包一层） */
    private fun isDeterministicFailure(error: IOException): Boolean {
        var e: Throwable? = error
        while (e != null) {
            when (e) {
                is UnknownHostException, is ConnectException, is NoRouteToHostException -> return true
                // 连接被重置：服务端主动断开（调度/防爬），重试通常还是断
                is SocketException -> if (e.message?.contains("reset", true) == true) return true
                // 4xx 是确定性拒绝；5xx 可能是源站临时故障，留给默认策略重试
                is HttpDataSource.InvalidResponseCodeException -> return e.responseCode in 400..499
            }
            e = e.cause
        }
        return false
    }

    /**
     * 终态：停止转圈、停掉播放器，并交给 Activity 统一提示 + 决定是否自动跳过。
     * 必须有终态——早期这里只弹一个会消失的 Toast，转圈图标会永久留在屏幕上。
     */
    private fun enterTerminal(message: String) {
        if (terminal) return
        terminal = true
        hideLoading()
        // 停在缓冲态会继续占着连接，直接停掉
        playerView?.player?.stop()
        Log.e(TAG, "terminal: $message")
        (activity as? MainActivity)?.onChannelUnavailable(message)
    }

    override fun onStart() {
        Log.i(TAG, "onStart")
        super.onStart()
        if (playerView?.player?.isPlaying == false) {
            Log.i(TAG, "replay")
            playerView?.player?.prepare()
            playerView?.player?.play()
        }
    }

    override fun onResume() {
        Log.i(TAG, "onResume")
        super.onResume()
    }

    override fun onPause() {
        super.onPause()
        if (playerView?.player?.isPlaying == true) {
            playerView?.player?.stop()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // 在此释放播放器资源，与 onCreateView 中的构建配对：
        // "view 销毁但 Fragment 保留"的场景（back stack、容器复用）里，
        // 拖到 onDestroy 才释放会让 ExoPlayer 连同 Activity 引用与解码器一直存活
        handler.removeCallbacks(showLoadingRunnable)
        handler.removeCallbacks(watchdog)
        handler.removeCallbacks(preloadRunnable)
        releaseStandby()
        playerView?.player?.removeListener(playerListener)
        (playerView?.player as? ExoPlayer)?.removeAnalyticsListener(analyticsListener)
        playerView?.player?.release()
        playerView?.player = null
        playerView = null
        _binding = null
    }

    override fun onDestroy() {
        super.onDestroy()
        // 兜底清理：onDestroyView 已释放时这些操作均为空操作（release 幂等）
        releaseStandby()
        playerView?.player?.release()
        playerView = null
    }

    companion object {
        private const val TAG = "PlayerFragment"
        private const val UA = "Mozilla/5.0 (Linux; Android) my-tv"

        /** 缓冲区下限：直播不需要囤太多，省内存也少延迟 */
        private const val MIN_BUFFER_MS = 10_000

        /** 缓冲区上限 */
        private const val MAX_BUFFER_MS = 30_000

        /**
         * 首帧起播阈值：缓冲到这么多就能出画。
         * media3 默认 2500ms——直播切台时这段完全是白等，一路压到 500ms。
         * 代价是弱网下更容易"刚出画就卡"，由"卡顿后自动降画质"与线路轮换兜底。
         */
        private const val BUFFER_FOR_PLAYBACK_MS = 500

        /** 卡顿后重新起播的门槛：略高于首帧阈值，避免在临界点来回抖 */
        private const val BUFFER_AFTER_REBUFFER_MS = 2_000

        /** 缓冲判定窗口内的抖动次数达到该值即放宽画质 */
        private const val REBUFFER_LIMIT = 3
        private const val REBUFFER_WINDOW_MS = 30_000L

        /** 放宽后的码率上限（约 2 Mbps），保证弱网可播 */
        private const val RELAXED_MAX_BITRATE = 2_000_000

        /**
         * 首帧看门狗超时。
         * 比起连接/读取超时（8s）留有余量，避免误伤慢速但正常的源；
         * 又要足够短，让"假活"线路尽早被放弃——连续 2 次约 30 秒即判定不可用。
         */
        private const val BUFFER_TIMEOUT_MS = 15_000L

        /** 同一频道连续几次"连通但不出画面"就放弃：2 次约 30 秒，兼顾慢速源与用户等待 */
        private const val TIMEOUT_LIMIT = 2

        /**
         * 超时判死的次数上限。
         * 每次超时 [BUFFER_TIMEOUT_MS]（15s），3 次即 45s——
         * 这是"多给一条线路机会"与"别让用户干等太久"之间的折中。
         */
        private const val TIMEOUT_LIMIT_MAX = 3

        /**
         * 加载遮罩延迟显示时长。
         * 起播快于此值就完全不出现遮罩，用户只看到画面从上一台切到新台；
         * 超过则说明确实要等，此时显示遮罩比让人盯着静止画面更清楚。
         */
        private const val LOADING_SHOW_DELAY_MS = 500L

        /**
         * 起播成功后延迟多久再准备下一个频道。
         *
         * 这个值直接决定"频繁连按换台"能否吃到预加载：待命就绪 = 本延迟 + 加载耗时（1~3s），
         * 用户按得比它快就永远等不到。由 3s 压到 1.5s——首帧出来后当前流的缓冲已经稳住，
         * 再拉一路不会明显拖慢它，而就绪窗口从约 5s 缩短到约 3s。
         */
        private const val PRELOAD_DELAY_MS = 1_500L

        /**
         * 由待命接管起播后，延迟多久准备再下一个。
         * 比 [PRELOAD_DELAY_MS] 短得多：接管的画面来自**已经缓冲充足**的流，
         * 再拉一路几乎不会影响它。这个值越小，"频繁连按"越有机会吃到预加载——
         * 待命就绪 = 本延迟 + 线路加载耗时（1~3s），用户按得比它快就永远等不到。
         */
        private const val PRELOAD_DELAY_AFTER_PROMOTE_MS = 300L

        /**
         * 来回翻台且**未**命中接管时，延迟多久准备待命。
         *
         * 折回的目标台是"刚播过"的台，它的流在 CDN 侧是热的，再拉一路对当前台影响很小
         * （与 [PRELOAD_DELAY_AFTER_PROMOTE_MS] 同理）。这个值直接决定"折回能不能吃到预加载"：
         * 待命就绪 = 本延迟 + 线路加载耗时（1~2.5s），1.5s 延迟下约 4s 才就绪，用户按快一点就吃不到。
         */
        private const val PRELOAD_DELAY_FLIP_FLOP_MS = 600L

        /**
         * 待命失败后的静默期。
         * 覆盖"用户在同一片频道来回翻"的整个时段；过期后自动放行重试。
         */
        private const val STANDBY_FAILED_TTL_MS = 300_000L

        /**
         * 待命播放器的缓冲水位。
         *
         * 它只需要"够接管"——缓冲到 [BUFFER_FOR_PLAYBACK_MS] 就判 READY 了（实测就绪耗时
         * 0.6~4.7s，取决于该台近期是否被访问过，与水位无关）。沿用主播放器的 [MIN_BUFFER_MS]
         * 会让它在就绪之后继续多拉约 7 秒直播数据，而频繁翻台时每次翻转都要释放它、
         * 再为下一个台重拉一路，这些数据基本是白下的——这是"待命与主播放器抢带宽"的一部分。
         *
         * 但水位也不能压太低：接管瞬间主播放器继承的就是这份缓冲，压到 3s 会让刚出画的
         * 头几秒几乎没有抗抖动余量（接管后主播放器才会按 10s 水位慢慢补回来）。
         * 取 5s 作为折中。
         */
        private const val STANDBY_MIN_BUFFER_MS = 5_000
        private const val STANDBY_MAX_BUFFER_MS = 10_000

        /** 起播时间线的输出窗口：覆盖起播全过程，又不至于把后续直播分片全打出来 */
        private const val TRACE_WINDOW_MS = 10_000L

        /** 软解码器优先的选择器（c2.android / OMX.google 为软件实现） */
        private val softwareFirstSelector: MediaCodecSelector =
            MediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
                MediaCodecSelector.DEFAULT
                    .getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder)
                    .sortedByDescending { info ->
                        if (info.name.startsWith("c2.android") ||
                            info.name.startsWith("omx.google", ignoreCase = true)
                        ) 1 else 0
                    }
            }
    }
}
