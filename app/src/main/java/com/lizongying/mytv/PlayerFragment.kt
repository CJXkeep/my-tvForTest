package com.lizongying.mytv

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.fragment.app.Fragment
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.ui.PlayerView
import com.google.android.exoplayer2.SimpleExoPlayer
import com.lizongying.mytv.databinding.PlayerBinding
import com.lizongying.mytv.models.ProgramType
import com.lizongying.mytv.models.TVViewModel


class PlayerFragment : Fragment(), SurfaceHolder.Callback {

    private var _binding: PlayerBinding? = null
    private var playerView: PlayerView? = null
    private var tvViewModel: TVViewModel? = null
    private var mediaSourceFactory: DefaultMediaSourceFactory? = null
    private val aspectRatio = 16f / 9f


    private lateinit var surfaceView: SurfaceView
    private lateinit var surfaceHolder: SurfaceHolder
    private var exoPlayer: SimpleExoPlayer? = null

    @OptIn(UnstableApi::class)
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = PlayerBinding.inflate(inflater, container, false)

        if (Utils.isTmallDevice()) {
            _binding!!.playerView.visibility = View.GONE
            surfaceView = _binding!!.surfaceView
            surfaceHolder = surfaceView.holder
            surfaceHolder.addCallback(this)
        } else {
            _binding!!.surfaceView.visibility = View.GONE
            playerView = _binding!!.playerView
        }

        playerView?.viewTreeObserver?.addOnGlobalLayoutListener(object :
            ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                playerView!!.viewTreeObserver.removeOnGlobalLayoutListener(this)
                playerView!!.player = activity?.let {
                    val httpDataSourceFactory = DefaultHttpDataSource.Factory()
                        .setUserAgent("Mozilla/5.0 (Linux; Android) my-tv")
                        .setAllowCrossProtocolRedirects(true)
                        .setConnectTimeoutMs(8000)
                        .setReadTimeoutMs(8000)
                    val dataSourceFactory = DefaultDataSource.Factory(it, httpDataSourceFactory)
                    mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)
                    ExoPlayer.Builder(it)
                        .setMediaSourceFactory(mediaSourceFactory!!)
                        .build()
                }
                playerView!!.player?.playWhenReady = true
                playerView!!.player?.addListener(object : Player.Listener {
                    override fun onVideoSizeChanged(videoSize: VideoSize) {
                        val ratio = playerView?.measuredWidth?.div(playerView?.measuredHeight!!)
                        if (ratio != null) {
                            val layoutParams = playerView?.layoutParams
                            if (ratio < aspectRatio) {
                                layoutParams?.height =
                                    (playerView?.measuredWidth?.div(aspectRatio))?.toInt()
                                playerView?.layoutParams = layoutParams
                            } else if (ratio > aspectRatio) {
                                layoutParams?.width =
                                    (playerView?.measuredHeight?.times(aspectRatio))?.toInt()
                                playerView?.layoutParams = layoutParams
                            }
                        }
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        super.onPlaybackStateChanged(playbackState)
                        when (playbackState) {
                            Player.STATE_BUFFERING -> showLoading()
                            Player.STATE_READY, Player.STATE_ENDED -> hideLoading()
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        super.onPlayerError(error)

                        Log.e(TAG, "PlaybackException $error")
                        hideLoading()
                        retryOnError()
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        super.onIsPlayingChanged(isPlaying)
                        if (isPlaying) {
                            hideLoading()
                            tvViewModel?.confirmSourceType()
                            (activity as MainActivity).isPlaying()
                        }
                    }
                })
            }
        })
        (activity as MainActivity).fragmentReady("PlayerFragment")
        return _binding!!.root
    }

    @OptIn(UnstableApi::class)
    fun play(tvViewModel: TVViewModel) {
        this.tvViewModel = tvViewModel
        showLoading()
        tvViewModel.resetSourceTypes()
        val player = playerView?.player as? ExoPlayer
        if (player != null) {
            player.setMediaSource(buildMediaSource(tvViewModel))
            player.prepare()
        }
        exoPlayer?.run {
            setMediaItem(com.google.android.exoplayer2.MediaItem.fromUri(tvViewModel.getVideoUrlCurrent()))
            prepare()
        }
    }

    /** 按当前源类型构建 MediaSource（借鉴 my-tv-0 两级轮换），应用频道自定义 headers */
    @OptIn(UnstableApi::class)
    private fun buildMediaSource(tvViewModel: TVViewModel): MediaSource {
        val url = tvViewModel.getVideoUrlCurrent()
        val mime = if (tvViewModel.currentSourceType == TVViewModel.SourceTypes.TYPE_HLS) {
            MimeTypes.APPLICATION_M3U8
        } else {
            MimeTypes.VIDEO_MP2T
        }
        val headers = tvViewModel.getTV().headers
        val ua = headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value
            ?: "Mozilla/5.0 (Linux; Android) my-tv"
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

    /** 播放失败重试：先轮换源类型，类型穷尽再换线路 */
    @OptIn(UnstableApi::class)
    private fun retryOnError() {
        val vm = tvViewModel ?: return
        if (vm.nextSourceType()) {
            Log.i(TAG, "retry sourceType ${vm.sourceTypeIndex}")
            val player = playerView?.player as? ExoPlayer
            player?.setMediaSource(buildMediaSource(vm))
            player?.prepare()
        } else if (vm.getTV().programType == ProgramType.DIRECT
            && (vm.videoUrl.value?.size ?: 0) > 1
        ) {
            val size = vm.videoUrl.value?.size ?: 0
            val next = ((vm.videoIndex.value ?: 0) + 1) % size
            Toast.makeText(
                context,
                "本线路异常，自动切换 ${vm.getTV().title} 线路 ${next + 1}/$size",
                Toast.LENGTH_SHORT
            ).show()
            vm.nextSource()
        } else {
            vm.changed()
        }
    }

    private fun showLoading() {
        _binding?.loading?.visibility = View.VISIBLE
    }

    private fun hideLoading() {
        _binding?.loading?.visibility = View.GONE
    }

    override fun onStart() {
        Log.i(TAG, "onStart")
        super.onStart()
        if (playerView != null && playerView!!.player?.isPlaying == false) {
            Log.i(TAG, "replay")
            playerView!!.player?.prepare()
            playerView!!.player?.play()
        }
        if (exoPlayer?.isPlaying == false) {
            Log.i(TAG, "replay")
            exoPlayer?.prepare()
            exoPlayer?.play()
        }
    }

    override fun onResume() {
        Log.i(TAG, "onResume")
        super.onResume()
    }

    override fun onPause() {
        super.onPause()
        if (playerView != null && playerView!!.player?.isPlaying == true) {
            playerView!!.player?.stop()
        }
        if (exoPlayer?.isPlaying == true) {
            exoPlayer?.stop()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (playerView != null) {
            playerView!!.player?.release()
        }
        exoPlayer?.release()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val TAG = "PlaybackVideoFragment"
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        exoPlayer = SimpleExoPlayer.Builder(requireContext()).build()
        exoPlayer?.setVideoSurfaceHolder(surfaceHolder)
        exoPlayer?.playWhenReady = true
        exoPlayer?.addListener(object : com.google.android.exoplayer2.Player.EventListener {
            override fun onPlayerStateChanged(playWhenReady: Boolean, playbackState: Int) {
                super.onPlayerStateChanged(playWhenReady, playbackState)
                when (playbackState) {
                    com.google.android.exoplayer2.Player.STATE_BUFFERING -> showLoading()
                    com.google.android.exoplayer2.Player.STATE_READY,
                    com.google.android.exoplayer2.Player.STATE_ENDED -> hideLoading()
                }
            }

            override fun onPlayerError(error: com.google.android.exoplayer2.ExoPlaybackException) {
                super.onPlayerError(error)
                hideLoading()
            }
        })
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
    }
}