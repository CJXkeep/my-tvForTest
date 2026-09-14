package com.lizongying.mytv

import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.lizongying.mytv.databinding.ItemGroupListBinding

/**
 * 左列分组列表。
 *
 * 行结构：各分组 + 末尾固定的「设置」项。
 * 「设置」放在列表内部而不是列表外面：遥控器的焦点搜索出不了 RecyclerView，
 * 放外面的项根本按不到。
 *
 * 正在播放的频道所在分组加 ▶ 标记，方便一眼看出在哪一组。
 */
class GroupListAdapter(
    /** 行获得焦点（上下移动）时回调，用于切换右列 */
    private val onFocused: (Int) -> Unit,
    /** 行被选中（OK / 触摸）；位置 >= [groupCount] 时表示选中了「设置」 */
    private val onClick: (Int) -> Unit,
) : RecyclerView.Adapter<GroupListAdapter.Holder>() {

    private var names: List<String> = emptyList()

    /** 当前播放频道所在分组，-1 表示不在任何分组内 */
    private var playingGroup = -1

    fun submit(names: List<String>, playingGroup: Int) {
        this.names = names
        this.playingGroup = playingGroup
        notifyDataSetChanged()
    }

    /** 分组数量（不含末尾的「换线路」与「设置」项） */
    fun groupCount(): Int = names.size

    /** 「换线路」项位置：当前频道的线路列表入口（比"长按 OK"可发现得多） */
    fun linesIndex(): Int = names.size

    /** 「设置」项位置 */
    fun settingsIndex(): Int = names.size + 1

    override fun getItemCount(): Int = names.size + 2

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemGroupListBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val text = holder.binding.groupName
        if (position == linesIndex()) {
            text.text = "换线路"
            text.setTextColor(SETTING_COLOR)
        } else if (position == settingsIndex()) {
            text.text = "设置"
            text.setTextColor(SETTING_COLOR)
        } else {
            val name = names[position]
            text.text = if (position == playingGroup) "▶ $name" else name
            text.setTextColor(GROUP_COLOR)
        }
        text.setOnClickListener { onClick(position) }
        text.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) onFocused(position)
        }
    }

    class Holder(val binding: ItemGroupListBinding) : RecyclerView.ViewHolder(binding.root)

    private companion object {
        val GROUP_COLOR = Color.parseColor("#FFEEEEEE")
        val SETTING_COLOR = Color.parseColor("#8BC34A")
    }
}
