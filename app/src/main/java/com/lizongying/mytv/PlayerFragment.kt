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
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.PlayerView
import com.lizongying.mytv.databinding.PlayerBinding
import com.lizongying.mytv.models.ProgramType
import com.lizongying.mytv.models.TVViewModel


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

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = PlayerBinding.inflate(inflater, container, false)
        playerView = _binding!!.playerView
        playerView!!.player = buildPlayer(requireContext(), softDecode)
        playerView!!.player?.playWhenReady = true
        playerView!!.player?.addListener(playerListener)

        (activity as MainActivity).fragmentReady("PlayerFragment")
        return _binding!!.root
    }

    /** 构建播放器（可按需启用软解优先） */
    private fun buildPlayer(context: Context, soft: Boolean): ExoPlayer {
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
        return ExoPlayer.Builder(context, renderersFactory)
            .setTrackSelector(selector)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
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
        val old = playerView?.player
        playerView?.player = null
        old?.removeListener(playerListener)
        old?.release()

        val player = buildPlayer(context, soft)
        player.playWhenReady = true
        player.addListener(playerListener)
        playerView?.player = player
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
                (activity as MainActivity).isPlaying()
            }
        }
    }

    fun play(tvViewModel: TVViewModel) {
        this.tvViewModel = tvViewModel
        // 只有"换频道"才清零重试计数。
        // 自动换线同样会走这里，早期无条件 resetAttempts() 会把上限反复清零，
        // 结果就是死台永远重试下去（日志里每 3 秒一个 Source error，停不下来）。
        if (lastChannelId != tvViewModel.getTV().id) {
            lastChannelId = tvViewModel.getTV().id
            timeoutCount = 0
            tvViewModel.resetAttempts()
        }
        // 新频道重新开始卡顿统计（首帧缓冲不计入）
        hasPlayed = false
        terminal = false
        rebufferTimes.clear()
        showLoading()
        // 线路变了要重新判断封装类型（同一个 URL 的类型记忆仍会命中）
        tvViewModel.resetSourceTypes()
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
        (playerView?.player as? ExoPlayer)?.apply {
            setMediaSource(buildMediaSource(vm))
            prepare()
        }
    }

    /** 按当前源类型构建 MediaSource，应用频道自定义 headers */
    private fun buildMediaSource(tvViewModel: TVViewModel): MediaSource {
        val url = tvViewModel.getVideoUrlCurrent()
        val mime = if (tvViewModel.currentSourceType == TVViewModel.SourceTypes.TYPE_HLS) {
            MimeTypes.APPLICATION_M3U8
        } else {
            MimeTypes.VIDEO_MP2T
        }
        val headers = tvViewModel.getTV().headers
        val ua = headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value
            ?: UA
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(ua)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(8000)
            .setReadTimeoutMs(8000)
            .setDefaultRequestProperties(
                headers.filterKeys { !it.equals("User-Agent", true) }
            )
        val dataSourceFactory = DefaultDataSource.Factory(requireContext(), httpFactory)
        val factory = DefaultMediaSourceFactory(dataSourceFactory)
        val item = MediaItem.Builder().setUri(url).setMimeType(mime).build()
        return factory.createMediaSource(item)
    }

    /** 播放失败重试：源类型 → 线路 → 软解降级；达到上限则进入终态 */
    private fun retryOnError(error: PlaybackException? = null) {
        val vm = tvViewModel ?: return
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
            val next = ((vm.videoIndex.value ?: 0) + 1) % limit
            // 自动换线属正常过程，走信息条而非弹窗
            (activity as? MainActivity)?.showInfoMessage(
                "${vm.getTV().title} 线路 ${next + 1}/$limit"
            )
            // 放弃这条线路：记下来，冷却期内不再优先尝试
            LineHealth.recordFail(vm.getVideoUrlCurrent())
            vm.nextSource()
        } else if (vm.getTV().programType == ProgramType.DIRECT) {
            // 单线路直连频道：计数后原地重试（同一 URL，不经去重逻辑）
            vm.countAttempt()
            applyCurrentSource()
        } else {
            vm.changed()
        }
    }

    private fun showLoading() {
        // 已进入终态就不再显示加载：否则"放弃后仍在转圈"的老问题会回来
        if (terminal) return
        _binding?.loading?.visibility = View.VISIBLE
        handler.removeCallbacks(watchdog)
        handler.postDelayed(watchdog, BUFFER_TIMEOUT_MS)
    }

    private fun hideLoading() {
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

        if (timeoutCount >= TIMEOUT_LIMIT) {
            enterTerminal("${vm.getTV().title} 线路无响应")
            return
        }
        val lines = vm.videoUrl.value?.size ?: 0
        if (vm.getTV().programType == ProgramType.DIRECT && lines > 1) {
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

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(watchdog)
        playerView?.player?.removeListener(playerListener)
        playerView?.player?.release()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val TAG = "PlayerFragment"
        private const val UA = "Mozilla/5.0 (Linux; Android) my-tv"

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

        /** 同一频道连续几次"连通但不出画面"就放弃：2 次约 40 秒，兼顾慢速源与用户等待 */
        private const val TIMEOUT_LIMIT = 2

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
