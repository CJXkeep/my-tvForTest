package com.lizongying.mytv

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.fragment.app.DialogFragment
import com.lizongying.mytv.databinding.SourceBinding
import com.lizongying.mytv.databinding.SourceItemBinding

/**
 * 线路列表：把当前频道的多条线路列出来，标注画质 / 延迟 / 录像标记，供用户手动选源。
 * 相比左右键盲切，用户能先看到每条线路的情况再决定。
 */
class SourceFragment : DialogFragment() {

    data class Item(
        val title: String,
        val detail: String,
        val current: Boolean,
    )

    private var _binding: SourceBinding? = null
    private val binding get() = _binding!!

    private var title: String = ""
    private var items: List<Item> = emptyList()

    /** 选中某条线路（0 基下标） */
    var onSelected: ((Int) -> Unit)? = null

    fun setData(title: String, items: List<Item>) {
        this.title = title
        this.items = items
    }

    /**
     * 原地刷新条目文案（探活结果回来后更新延迟等）。
     * 只改文本、不重建视图，避免打断用户的焦点位置。
     */
    fun updateDetails(newItems: List<Item>) {
        items = newItems
        val binding = _binding ?: return
        for (i in newItems.indices) {
            val child = binding.sourceContainer.getChildAt(i) ?: continue
            SourceItemBinding.bind(child).apply {
                itemTitle.text = newItems[i].title
                itemDetail.text = newItems[i].detail
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_TITLE, 0)
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = SourceBinding.inflate(inflater, container, false)
        binding.sourceTitle.text = title
        binding.sourceContainer.removeAllViews()

        val rowHeight = Utils.dpToPx(ROW_HEIGHT_DP)
        var currentRow: View? = null

        items.forEachIndexed { index, item ->
            val row = SourceItemBinding.inflate(inflater, binding.sourceContainer, false)
            row.itemTitle.text = item.title
            row.itemDetail.text = item.detail
            row.root.isFocusable = true
            row.root.isFocusableInTouchMode = true
            row.root.setOnClickListener {
                onSelected?.invoke(index)
                dismiss()
            }
            binding.sourceContainer.addView(row.root)
            if (item.current) currentRow = row.root
        }

        // 列表高度自适应条目数，最多 MAX_HEIGHT_DP，超出可滚动
        binding.sourceScroll.layoutParams = binding.sourceScroll.layoutParams.apply {
            height = (items.size * rowHeight)
                .coerceAtMost(Utils.dpToPx(MAX_HEIGHT_DP))
                .coerceAtLeast(rowHeight)
        }

        currentRow?.post { currentRow?.requestFocus() }
        return binding.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val ROW_HEIGHT_DP = 58
        private const val MAX_HEIGHT_DP = 360
    }
}
