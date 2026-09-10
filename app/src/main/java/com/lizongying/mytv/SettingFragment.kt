package com.lizongying.mytv

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import com.lizongying.mytv.databinding.SettingBinding


class SettingFragment : DialogFragment() {

    private var _binding: SettingBinding? = null
    private val binding get() = _binding!!

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_TITLE, 0)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val context = requireContext() // It‘s safe to get context here.
        _binding = SettingBinding.inflate(inflater, container, false)
        binding.versionName.text = "当前版本: v${context.appVersionName}"
        binding.version.text = "数据源支持 M3U/TXT/JSON 订阅"

        val ip = ConfigServer.lanIp()
        binding.remoteConfig.text =
            if (ip != null) "远程配置: http://$ip:${ConfigServer.PORT}" else "远程配置: 未连接局域网"

        binding.iptvSource.setText(SP.iptvSourceUrl)

        binding.switchChannelReversal.run {
            isChecked = SP.channelReversal
            setOnCheckedChangeListener { _, isChecked ->
                SP.channelReversal = isChecked
                (activity as MainActivity).settingDelayHide()
            }
        }

        binding.switchChannelNum.run {
            isChecked = SP.channelNum
            setOnCheckedChangeListener { _, isChecked ->
                SP.channelNum = isChecked
                (activity as MainActivity).settingDelayHide()
            }
        }

        binding.switchTime.run {
            isChecked = SP.time
            setOnCheckedChangeListener { _, isChecked ->
                SP.time = isChecked
                (activity as MainActivity).settingDelayHide()
            }
        }

        binding.switchBootStartup.run {
            isChecked = SP.bootStartup
            setOnCheckedChangeListener { _, isChecked ->
                SP.bootStartup = isChecked
                (activity as MainActivity).settingDelayHide()
            }
        }

        binding.saveSource.setOnClickListener {
            val url = binding.iptvSource.text.toString().trim()
            if (url != SP.iptvSourceUrl) {
                SP.iptvSourceUrl = url
                // 换源后频道编号空间变化，重置选台位置
                SP.itemPosition = 0
                Toast.makeText(context, "数据源已保存，正在重新加载", Toast.LENGTH_SHORT).show()
                requireActivity().recreate()
            } else {
                (activity as MainActivity).settingDelayHide()
            }
        }

        binding.switchSoftDecode.run {
            isChecked = SP.softDecode
            setOnCheckedChangeListener { _, isChecked ->
                SP.softDecode = isChecked
                Toast.makeText(
                    context,
                    if (isChecked) "已开启软解优先，重启应用生效" else "已关闭软解优先，重启应用生效",
                    Toast.LENGTH_SHORT
                ).show()
                (activity as MainActivity).settingDelayHide()
            }
        }

        binding.resetAll.setOnClickListener {
            SP.reset()
            // 清理 EPG 缓存
            try {
                java.io.File(context.filesDir, "epg_cache.json").delete()
            } catch (_: Exception) {
            }
            Toast.makeText(context, "已恢复默认设置", Toast.LENGTH_SHORT).show()
            requireActivity().recreate()
        }

        binding.exit.setOnClickListener{
            requireActivity().finishAffinity()
        }

        return binding.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "SettingFragment"
    }
}
