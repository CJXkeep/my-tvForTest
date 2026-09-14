package com.lizongying.mytv

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import com.lizongying.mytv.databinding.SettingBinding

/**
 * 设置面板。
 *
 * 视觉与交互与选台面板保持一致：深色底、绿色小标题、整行可聚焦（聚焦高亮用同一个 drawable），
 * 开关用「文字 + 开/关」的行代替系统 Switch，动作用整行代替系统 Button。
 *
 * 关闭方式：再次按 MENU（或 OK 之外的返回键）——不做自动收起，避免用户正在读远程配置地址时被关掉。
 */
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
        val context = requireContext()
        _binding = SettingBinding.inflate(inflater, container, false)

        binding.versionName.text = "版本 v${context.appVersionName}"

        val ip = ConfigServer.lanIp()
        binding.remoteConfig.text =
            if (ip != null) "http://$ip:${ConfigServer.PORT}/?token=${SP.configToken}"
            else "未连接局域网，无法使用远程配置"

        binding.iptvSource.setText(SP.iptvSourceUrl)

        bindToggle(binding.rowTime, binding.valueTime, SP.time) {
            SP.time = it
            // 立即生效：时间显示由 MainActivity 控制（原先挂在 settingDelayHide 里，已随自动收起一并移除）
            (activity as? MainActivity)?.applySettings()
        }
        bindToggle(binding.rowBoot, binding.valueBoot, SP.bootStartup) { SP.bootStartup = it }

        binding.rowSave.setOnClickListener {
            val url = binding.iptvSource.text.toString().trim()
            if (url == SP.iptvSourceUrl) {
                return@setOnClickListener
            }
            SP.iptvSourceUrl = url
            // 换源后旧缓存失效，清掉避免回退时展示上一个源的数据
            ChannelCache.clear()
            // 换源后频道编号空间变化，重置选台位置
            SP.itemPosition = 0
            Toast.makeText(context, "数据源已保存，正在重新加载", Toast.LENGTH_SHORT).show()
            dismiss()
            // 原地重载频道列表，避免 recreate 中断播放
            (activity as? MainActivity)?.reloadChannels()
        }

        // 一键回到内置默认源：清空自定义地址即可（TVList 在留空时使用默认源）
        binding.rowDefault.setOnClickListener {
            if (SP.iptvSourceUrl.isBlank()) {
                Toast.makeText(context, "当前已是默认源", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val count = TVList.defaultSources().size
            SP.iptvSourceUrl = ""
            ChannelCache.clear()
            SP.itemPosition = 0
            binding.iptvSource.setText("")
            Toast.makeText(
                context,
                "已恢复默认源（$count 个公开聚合源），正在重新加载",
                Toast.LENGTH_SHORT
            ).show()
            dismiss()
            (activity as? MainActivity)?.reloadChannels()
        }

        // 清空"探测结果 + 失败记忆"后重新检测。
        // 公共源经常整批抽风，积累下来的旧结论会把恢复了的线路长期埋在排序后面，
        // 给一个手动重来的入口，比等冷却期过期更直接。
        binding.rowRecheck.setOnClickListener {
            ChannelProbe.reset()
            LineHealth.reset()
            Toast.makeText(
                context,
                "已清空线路记录，正在重新检测（约需数十秒）",
                Toast.LENGTH_SHORT
            ).show()
            dismiss()
            (activity as? MainActivity)?.reloadChannels()
        }

        // 默认焦点落在第一个开关行，而不是输入框（否则用户一进来就在编辑源地址）
        binding.rowTime.post { binding.rowTime.requestFocus() }

        return binding.root
    }

    /**
     * 开关行：整行可聚焦，按 OK 切换，右侧显示「开/关」。
     * 用文字状态替代 Switch——系统 Switch 的配色与本应用整体风格不一致。
     */
    private fun bindToggle(
        row: View,
        value: TextView,
        initial: Boolean,
        apply: (Boolean) -> Unit,
    ) {
        var state = initial

        fun render() {
            value.text = if (state) "开" else "关"
            value.setTextColor(if (state) COLOR_ON else COLOR_OFF)
        }

        render()
        row.setOnClickListener {
            state = !state
            apply(state)
            render()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "SettingFragment"

        private val COLOR_ON = 0xFF8BC34A.toInt()
        private val COLOR_OFF = 0xFF99EEEEEE.toInt()
    }
}
