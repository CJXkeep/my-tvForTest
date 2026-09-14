package com.lizongying.mytv

import android.os.Bundle
import android.os.Handler
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.lizongying.mytv.databinding.ChannelBinding
import com.lizongying.mytv.models.TVViewModel

class ChannelFragment : Fragment() {
    private var _binding: ChannelBinding? = null
    private val binding get() = _binding!!

    private val handler = Handler()
    private val delay: Long = 3000

    /** 数字输入的累积值 */
    private var pendingNumber = 0

    /** 数字输入的位数 */
    private var digitCount = 0

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = ChannelBinding.inflate(inflater, container, false)
        _binding!!.root.visibility = View.GONE

        val activity = requireActivity()
        val application = activity.applicationContext as MyApplication
        val displayMetrics = application.getDisplayMetrics()

        displayMetrics.density

        var screenWidth = displayMetrics.widthPixels
        var screenHeight = displayMetrics.heightPixels
        if (screenHeight > screenWidth) {
            screenWidth = displayMetrics.heightPixels
            screenHeight = displayMetrics.widthPixels
        }

        val ratio = 16f / 9f

        if (screenWidth / screenHeight > ratio) {
            val x = ((screenWidth - screenHeight * ratio) / 2).toInt()
            val originalLayoutParams =
                binding.channelFragment.layoutParams as ViewGroup.MarginLayoutParams
            originalLayoutParams.rightMargin += x
            binding.channelFragment.layoutParams = originalLayoutParams
        }

        if (screenWidth / screenHeight < ratio) {
            val y = ((screenHeight - screenWidth / ratio) / 2).toInt()
            val originalLayoutParams =
                binding.channelFragment.layoutParams as ViewGroup.MarginLayoutParams
            originalLayoutParams.topMargin += y
            binding.channelFragment.layoutParams = originalLayoutParams
        }

        (activity as MainActivity).fragmentReady("ChannelFragment")
        return binding.root
    }

    fun show(tvViewModel: TVViewModel) {
        handler.removeCallbacks(hideRunnable)
        handler.removeCallbacks(playRunnable)
        // 编号即列表序号：与左侧列表显示的编号、数字键选台完全一致
        binding.channelContent.text = (tvViewModel.getTV().id + 1).toString()
        view?.visibility = View.VISIBLE
        handler.postDelayed(hideRunnable, delay)
    }

    /**
     * 数字键选台：累积输入，满位或超时即跳台。
     * 早期只允许 2 位数字，导致编号 >99 的频道（大源里非常常见）无法用数字键直达。
     */
    fun show(digit: String) {
        if (digitCount >= MAX_DIGITS) return
        pendingNumber = pendingNumber * 10 + digit.toInt()
        digitCount++
        Log.i(TAG, "digit $digit -> $pendingNumber ($digitCount)")
        handler.removeCallbacks(hideRunnable)
        handler.removeCallbacks(playRunnable)
        if (digitCount >= MAX_DIGITS) {
            playRunnable.run()
            return
        }
        binding.channelContent.text = pendingNumber.toString()
        view?.visibility = View.VISIBLE
        handler.postDelayed(playRunnable, INPUT_TIMEOUT_MS)
    }

    /** 清空未完成的数字输入 */
    private fun resetInput() {
        pendingNumber = 0
        digitCount = 0
    }

    override fun onResume() {
        super.onResume()
        if (view?.visibility == View.VISIBLE) {
            handler.postDelayed(hideRunnable, delay)
        }
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(hideRunnable)
        handler.removeCallbacks(playRunnable)
        // 切后台即放弃本次输入：否则回前台后按下的数字会与旧值拼成错误编号
        resetInput()
    }

    private val hideRunnable = Runnable {
        binding.channelContent.text = ""
        view?.visibility = View.GONE
        resetInput()
        Log.i(TAG, "hideRunnable")
    }

    private val playRunnable = Runnable {
        val number = pendingNumber
        resetInput()
        binding.channelContent.text = ""
        view?.visibility = View.GONE
        Log.i(TAG, "playRunnable $number")
        (activity as MainActivity).playByNumber(number)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val TAG = "ChannelFragment"

        /** 最多允许输入的位数（覆盖 3 位 tvg-chno 与大源里的 4 位列表编号） */
        private const val MAX_DIGITS = 4

        /** 数字输入等待时间：超时即跳台，1 位与多位手感统一 */
        private const val INPUT_TIMEOUT_MS = 2000L
    }
}