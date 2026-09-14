package com.lizongying.mytv

import android.os.Bundle
import android.os.Handler
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.bumptech.glide.Glide
import com.lizongying.mytv.databinding.InfoBinding
import com.lizongying.mytv.models.TVViewModel

class InfoFragment : Fragment() {
    private var _binding: InfoBinding? = null
    private val binding get() = _binding!!

    private val handler = Handler()
    private val delay: Long = 3000

    /** 文字容器的原始左边距与宽度（为台标预留）；消息模式下会临时借用来放长文案 */
    private var logoMarginStart = 0
    private var logoContainerWidth = 0

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = InfoBinding.inflate(inflater, container, false)
        _binding!!.root.visibility = View.GONE
        val containerLp = binding.infoTextContainer.layoutParams as ViewGroup.MarginLayoutParams
        logoMarginStart = containerLp.marginStart
        logoContainerWidth = containerLp.width
        (activity as MainActivity).fragmentReady("InfoFragment")
        return binding.root
    }

    fun show(tvViewModel: TVViewModel) {
        binding.textView.text = tvViewModel.getTV().title
        binding.infoLogo.visibility = View.VISIBLE
        setLogoSpaceReserved(true)

        Glide.with(this)
            .load(tvViewModel.getTV().logo)
            .into(binding.infoLogo)

        Log.i(TAG, "${tvViewModel.getTV().title} ${tvViewModel.epg.value}")
        val epg = tvViewModel.epg.value?.filter { it.beginTime < Utils.getDateTimestamp() }
        if (!epg.isNullOrEmpty()) {
            binding.infoDesc.text = epg.last().title
        } else {
            binding.infoDesc.text = ""
        }

        handler.removeCallbacks(removeRunnable)
        view?.visibility = View.VISIBLE
        handler.postDelayed(removeRunnable, delay)
    }

    /** 轻提示：复用信息条展示线路切换等状态，替代满屏 Toast */
    fun showMessage(message: String) {
        val binding = _binding ?: return
        binding.textView.text = message
        binding.infoLogo.visibility = View.GONE
        binding.infoLogo.setImageDrawable(null)
        binding.infoDesc.text = ""
        // 消息模式下没有台标，把台标区域让给文字，长文案才不会过早省略
        setLogoSpaceReserved(false)
        handler.removeCallbacks(removeRunnable)
        view?.visibility = View.VISIBLE
        handler.postDelayed(removeRunnable, delay)
    }

    /**
     * 是否给台标预留左侧空间。
     * 消息模式下没有台标，直接把容器撑满整个信息条，长文案才不会过早省略。
     */
    private fun setLogoSpaceReserved(reserved: Boolean) {
        val container = _binding?.infoTextContainer ?: return
        val lp = container.layoutParams as ViewGroup.MarginLayoutParams
        val targetMargin = if (reserved) logoMarginStart else 0
        val targetWidth = if (reserved) logoContainerWidth else ViewGroup.LayoutParams.MATCH_PARENT
        if (lp.marginStart != targetMargin || lp.width != targetWidth) {
            lp.marginStart = targetMargin
            lp.width = targetWidth
            container.layoutParams = lp
        }
    }

    override fun onResume() {
        super.onResume()
        handler.postDelayed(removeRunnable, delay)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(removeRunnable)
    }

    private val removeRunnable = Runnable {
        view?.visibility = View.GONE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val TAG = "InfoFragment"
    }
}