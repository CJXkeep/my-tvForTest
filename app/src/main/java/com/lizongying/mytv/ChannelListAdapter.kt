package com.lizongying.mytv

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.lizongying.mytv.databinding.ItemChannelListBinding

/**
 * 右列频道列表（只渲染当前分组的频道）。
 *
 * 编号统一为全局列表序号 `tv.id + 1`——与数字键选台共用同一套编号空间，
 * 界面上看到几就按几，不会再出现"显示 5 却跳到别的台"。
 */
class ChannelListAdapter(
    /** 频道行状态描述（当前节目 / 正在播放 / 暂不可用） */
    private val descProvider: (TV) -> String,
    /** 选中某频道（OK 或触摸） */
    private val onClick: (Int) -> Unit,
    /** 频道行获得焦点（上下移动） */
    private val onFocused: (Int) -> Unit,
) : RecyclerView.Adapter<ChannelListAdapter.Holder>() {

    private var channels: List<TV> = emptyList()

    fun submit(list: List<TV>) {
        channels = list
        notifyDataSetChanged()
    }

    /** 刷新行内容（EPG / 探活结果回来后调用） */
    fun refresh() {
        notifyDataSetChanged()
    }

    /** 频道 id 在本列中的行下标；不在本列返回 -1 */
    fun positionOfChannel(id: Int): Int = channels.indexOfFirst { it.id == id }

    override fun getItemCount(): Int = channels.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemChannelListBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val tv = channels[position]
        holder.binding.itemNum.text = (tv.id + 1).toString()
        holder.binding.itemTitle.text = tv.title
        val desc = descProvider(tv)
        holder.binding.itemDesc.text = desc
        holder.binding.itemDesc.visibility = if (desc.isEmpty()) View.GONE else View.VISIBLE
        holder.binding.root.setOnClickListener { onClick(tv.id) }
        holder.binding.root.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) onFocused(tv.id)
        }
    }

    class Holder(val binding: ItemChannelListBinding) : RecyclerView.ViewHolder(binding.root)
}
