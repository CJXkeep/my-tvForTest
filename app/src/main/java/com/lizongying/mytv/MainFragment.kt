package com.lizongying.mytv

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.lizongying.mytv.databinding.MainListBinding
import com.lizongying.mytv.models.ProgramType
import com.lizongying.mytv.models.TVListViewModel
import com.lizongying.mytv.models.TVViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 左侧选台面板（**两级：先选分组，再选频道**）。
 *
 * 左列是分组，右列是该分组的频道，右侧保留正在播放的画面。
 * 遥控器：上下在同一列内移动，左右在列间切换；OK 切台并收起面板。
 *
 * 编号统一为列表序号（`tv.id + 1`），与数字键选台完全一致：看到几就按几。
 * 源里自带的 `tvg-chno` 不再参与编号与选台，避免两套编号打架。
 */
class MainFragment : Fragment() {

    private var _binding: MainListBinding? = null
    private val binding get() = _binding!!

    private lateinit var groupAdapter: GroupListAdapter
    private lateinit var channelAdapter: ChannelListAdapter

    /** 当前播放/选中的频道下标（等于 [TV.id]） */
    private var itemPosition = 0

    private var lastVideoUrl = ""

    /** 上一次起播的频道 id：与 [lastVideoUrl] 一起做去重，避免跨频道误判 */
    private var lastChannelId = -1

    var tvListViewModel = TVListViewModel()

    /** 当前已渲染的频道结构 */
    private var currentGroups: Map<String, List<TV>> = emptyMap()

    /** 分组名（顺序与 [currentGroups] 一致） */
    private var groupNames: List<String> = emptyList()

    /** 右列当前展示的分组下标 */
    private var shownGroup = 0

    /** 焦点是否在左列分组（左右键在列间移动时用） */
    private var focusInGroups = true

    /** 上一次已探活的分组，避免同一分组反复触发 */
    private var lastProbedGroup = -1

    /** 后台补探任务（把没探过的分组逐步推进，用户切过去时不再是满屏"未探测"） */
    private var backlogJob: Job? = null

    /** 启动阶段的后台任务是否已放行（首帧或兜底超时触发后置位，只放行一次） */
    private var startupWorkReleased = false

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = MainListBinding.inflate(inflater, container, false)

        groupAdapter = GroupListAdapter(
            onFocused = { index -> onGroupFocused(index) },
            onClick = { index ->
                when (index) {
                    // 末尾两项不是分组
                    groupAdapter.settingsIndex() -> (activity as? MainActivity)?.showSetting()
                    groupAdapter.linesIndex() -> (activity as? MainActivity)?.showSourceList()
                    // 选中分组 = 进入它的频道列表
                    else -> moveFocus(false)
                }
            },
        )
        channelAdapter = ChannelListAdapter(
            descProvider = { tv -> channelDesc(tv) },
            onClick = { id -> onChannelChosen(id) },
            onFocused = { id -> onChannelFocused(id) },
        )

        // 默认动画会让列表在刷新时闪动，直接关掉
        binding.groupList.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = groupAdapter
            itemAnimator = null
            setHasFixedSize(true)
        }
        binding.channelList.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = channelAdapter
            itemAnimator = null
            setHasFixedSize(true)
        }
        return binding.root
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)

        lifecycleScope.launch {
            // 优先用本地缓存：启动即可渲染并起播（秒开），不必等网络
            val cached = withContext(Dispatchers.IO) {
                EpgStore.initCache(requireContext())
                // 预热探活/降权记录（都是磁盘文件）：否则首次列表绑定会在主线程触发读盘
                ChannelProbe.warmUp()
                LineHealth.warmUp()
                TVList.loadCached()
            }

            if (cached != null) {
                loadRows(cached)
                (activity as MainActivity).fragmentReady("MainFragment")
                // 拉源 / EPG / 探活统一推迟到首帧之后，避免与起播抢带宽
                scheduleStartupWork()
            } else {
                // 无缓存（首次安装）：等待远程拉取，失败会自动回退内置源
                (activity as? MainActivity)?.showInfoMessage("正在加载频道…")
                val list = withContext(Dispatchers.IO) { TVList.load() }
                loadRows(list)
                (activity as MainActivity).fragmentReady("MainFragment")
                scheduleStartupWork()
                notifyIfEmpty(list)
            }
        }
    }

    // ---------------- 列表渲染 ----------------

    private fun loadRows(list: Map<String, List<TV>>) {
        // 可重入：换源 / 远程配置保存后原地重建，避免 recreate 中断播放
        tvListViewModel = TVListViewModel()
        lastVideoUrl = ""
        lastChannelId = -1
        // 列表重建后行号含义已变，重置分组探活去重状态
        lastProbedGroup = -1
        currentGroups = list
        groupNames = list.keys.toList()

        // 按顺序建 ViewModel：下标与 TV.id 一致，保证与数字选号对得上
        list.forEach { (_, v) ->
            v.forEach { tv ->
                val vm = TVViewModel(tv)
                tvListViewModel.addTVViewModel(vm)
                observeTVViewModel(vm)
            }
        }

        // 记忆的位置越界（例如开启白名单过滤后列表大幅变小）时回到第一台，
        // 而不是夹到最后一条——否则用户会在一个莫名其妙的频道上开播
        itemPosition = SP.itemPosition
        if (itemPosition >= tvListViewModel.size()) itemPosition = 0
        tvListViewModel.setItemPosition(itemPosition)

        refreshGroupColumn()
        showGroup(currentGroupIndex().coerceAtLeast(0))
    }

    /** 刷新左列分组（含"▶ 正在播放"标记） */
    private fun refreshGroupColumn() {
        groupAdapter.submit(groupNames, currentGroupIndex())
    }

    /** 当前播放频道所在分组下标；找不到返回 -1 */
    private fun currentGroupIndex(): Int {
        val name = tvListViewModel.getTVViewModel(itemPosition)?.getTV()?.channel ?: return -1
        val byField = groupNames.indexOf(name)
        if (byField >= 0) return byField
        return groupNames.indexOfFirst { g -> currentGroups[g]?.any { it.id == itemPosition } == true }
    }

    /**
     * 切换右列展示的分组。
     * [focusChannels] 为真时把焦点移进频道列（用于"从分组列按右键"的场景）。
     */
    private fun showGroup(index: Int, focusChannels: Boolean = false) {
        if (groupNames.isEmpty()) return
        val g = index.coerceIn(0, groupNames.size - 1)
        shownGroup = g
        val name = groupNames[g]
        val list = currentGroups[name].orEmpty()
        channelAdapter.submit(list)
        binding.channelTitle.text = "$name · ${list.size} 台"

        // 右列滚到当前频道（在本分组内时），否则回到第一条
        val pos = channelAdapter.positionOfChannel(itemPosition).takeIf { it >= 0 } ?: 0
        (binding.channelList.layoutManager as? LinearLayoutManager)
            ?.scrollToPositionWithOffset(pos, 0)
        if (focusChannels) binding.channelList.post { focusChannelRow(pos) }
    }

    /** 面板打开时：定位到当前频道所在分组，焦点落在分组列 */
    fun onShown() {
        refreshGroupColumn()
        val g = currentGroupIndex().coerceAtLeast(0)
        showGroup(g)
        focusInGroups = true
        focusGroupRow(g)
    }

    /**
     * 左右键在「分组列 ↔ 频道列」之间移动焦点。
     * 不依赖原生焦点搜索：两列都是 RecyclerView，横向搜索不会自己跨列，显式控制更可靠。
     */
    fun moveFocus(isLeft: Boolean) {
        if (isLeft) {
            if (!focusInGroups) {
                focusInGroups = true
                focusGroupRow(shownGroup)
            }
        } else {
            if (focusInGroups) {
                focusInGroups = false
                val pos = channelAdapter.positionOfChannel(itemPosition).takeIf { it >= 0 } ?: 0
                binding.channelList.post { focusChannelRow(pos) }
            }
        }
    }

    private fun focusGroupRow(index: Int) {
        val list = _binding?.groupList ?: return
        (list.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(index, 0)
        list.post {
            val vh = list.findViewHolderForAdapterPosition(index)
            if (vh != null) vh.itemView.requestFocus() else list.requestFocus()
        }
    }

    private fun focusChannelRow(position: Int) {
        val list = _binding?.channelList ?: return
        val vh = list.findViewHolderForAdapterPosition(position)
        if (vh != null) vh.itemView.requestFocus() else list.requestFocus()
    }

    /** 分组列上下移动：右列跟着换组，并顺带补探该分组 */
    private fun onGroupFocused(index: Int) {
        // 末尾的「设置」项不切换分组
        if (index >= groupAdapter.groupCount()) return
        focusInGroups = true
        showGroup(index)
        probeGroupIfNeeded(index)
    }

    /** 频道列上下移动：只更新选中与自动收起计时（真正切台在 OK 时） */
    private fun onChannelFocused(id: Int) {
        focusInGroups = false
        tvListViewModel.setItemPositionCurrent(id)
    }

    /**
     * 频道行状态：当前节目 > 正在播放 > 暂不可用。
     *
     * 它在 onBindViewHolder 里被逐行调用，所以刻意避免分配：
     * EPG 已按开始时间排好序，从后往前找第一条"已开始"的就是当前节目，通常一两次比较就返回；
     * 早期写法是 `filter{}.last()`，每绑定一行都要遍历整张节目单并新建一个 List。
     */
    private fun channelDesc(tv: TV): String {
        val now = Utils.getDateTimestamp()
        val epg = tvListViewModel.getTVViewModel(tv.id)?.epg?.value
        if (!epg.isNullOrEmpty()) {
            for (i in epg.lastIndex downTo 0) {
                if (epg[i].beginTime < now) return epg[i].title
            }
        }
        if (tv.id == itemPosition) return "正在播放"
        if (ChannelProbe.isChannelDown(tv.videoUrl) || LineHealth.isChannelDown(tv.videoUrl)) {
            return "暂不可用"
        }
        return ""
    }

    /** 列表选中某频道：切台并收起面板 */
    private fun onChannelChosen(id: Int) {
        if (id != itemPosition) {
            itemPosition = id
            tvListViewModel.setItemPosition(itemPosition)
            tvListViewModel.getTVViewModel(itemPosition)?.changed()
        }
        // changed() 的观察者负责起播；同台重选时不会触发，这里统一收起面板
        (activity as? MainActivity)?.hideListAndPlay()
    }

    /**
     * 原地重载频道列表：换源 / 远程配置保存后调用，尽量保持当前频道。
     * 注意：这里会重新起播一次（结构与线路都可能变），与"仅刷新列表"不同。
     */
    fun reload() {
        view?.post {
            lifecycleScope.launch {
                val currentTitle = getCurrentTVViewModel()?.getTV()?.title
                val list = withContext(Dispatchers.IO) { TVList.load() }
                loadRows(list)
                restorePosition(currentTitle)
                // 换源由用户主动发起，不在启动争抢窗口内，直接探活
                probePriority()
                notifyIfEmpty(list)
            }
        }
    }

    /**
     * 一个频道都没有时给出换源提示。
     *
     * 源全部拉取失败、又没有可用兜底频道时，界面会停在**全黑且没有任何文字**的状态，
     * 用户（尤其老人）既不知道发生了什么也不知道该按哪里——这里必须给一个明确出口。
     */
    private fun notifyIfEmpty(list: Map<String, List<TV>>) {
        if (list.values.sumOf { it.size } > 0) return
        Log.e(TAG, "channel list is empty, ask user to switch source")
        (activity as? MainActivity)?.onChannelListEmpty()
    }

    // ---------------- 数据刷新 ----------------

    /** 后台刷新：内容结构有变化时才原地重建，并保持当前频道 */
    private fun refreshFromRemote() {
        lifecycleScope.launch {
            val fresh = withContext(Dispatchers.IO) { TVList.loadRemote() } ?: return@launch
            // 有源没拉成功时可能只拿到残缺列表：只有不比当前更少时才采用，
            // 免得用户看到频道凭空少了一半（残缺但更多仍然采用，下次全成功时再刷新）
            val freshCount = fresh.values.sumOf { it.size }
            val currentCount = currentGroups.values.sumOf { it.size }
            if (!TVList.lastLoadComplete && freshCount < currentCount) {
                Log.w(TAG, "remote load incomplete ($freshCount < $currentCount), keep current")
                return@launch
            }
            if (signature(fresh) == signature(currentGroups)) {
                Log.i(TAG, "remote channels unchanged, keep cache")
                return@launch
            }
            Log.i(TAG, "remote channels changed, rebuild list")
            val currentTitle = getCurrentTVViewModel()?.getTV()?.title
            loadRows(fresh)
            restorePosition(currentTitle)
            probePriority()
            updateEpg()
        }
    }

    /** EPG：订阅源自带 x-tvg-url + 设置里的地址，多源合并 */
    private fun updateEpg() {
        EpgStore.updateAsync(
            TVList.epgUrls + listOfNotNull(SP.epgUrl.takeIf { it.isNotBlank() })
        ) {
            // 节目单回来后刷新列表副标题
            _binding?.let { channelAdapter.refresh() }
        }
    }

    /** 按频道名恢复选中/播放位置 */
    private fun restorePosition(title: String?) {
        val canonical = title?.let { TVList.canonicalName(it) }
        val restored = canonical?.let { key ->
            tvListViewModel.tvListViewModel.value?.indexOfFirst {
                TVList.canonicalName(it.getTV().title) == key
            }?.takeIf { it >= 0 }
        }
        itemPosition = restored
            ?: itemPosition.coerceIn(0, maxOf(0, tvListViewModel.size() - 1))
        tvListViewModel.setItemPosition(itemPosition)
        tvListViewModel.getTVViewModel(itemPosition)?.changed()
    }

    /**
     * 频道阵容指纹：只用「分组名 + 频道名」判断是否需要重建。
     * 不纳入线路数量/地址——直播源的线路本身高频变动，纳入会导致每次启动都误判重建并重播。
     */
    private fun signature(groups: Map<String, List<TV>>): Int {
        var h = 1
        groups.forEach { (k, v) ->
            h = 31 * h + k.hashCode()
            v.forEach { tv -> h = 31 * h + tv.title.hashCode() }
        }
        return h
    }

    // ---------------- 探活 ----------------

    /**
     * 探活范围：只探当前分组，其余分组在用户切到时按需补探。
     * 全表探测（上限 1000 条线路）请求量过大，收益却只在当前观看的分组上。
     */
    private fun probeScope(groupNames: Set<String>) {
        val list = currentGroups
        val targets = groupNames.mapNotNull { list[it] }.flatten()
        if (targets.isEmpty()) return
        ChannelProbe.probeAsync(targets) { channelAdapter.refresh() }
    }

    /**
     * 压住启动阶段的后台任务，等首帧渲染出来再放行（见 [releaseStartupWork]）。
     *
     * 兜底：迟迟没有首帧时（例如这个频道本身就播不出来）按 [STARTUP_RELEASE_FALLBACK_MS] 强制放行，
     * 否则这些任务永远不会跑：探活停在"未探测"、EPG 空白、列表也不再更新。
     */
    private fun scheduleStartupWork() {
        if (startupWorkReleased) {
            // 首帧早就来过（例如用户在播放中重新加载列表），不必再等
            releaseStartupWork()
            return
        }
        view?.postDelayed(startupWorkFallback, STARTUP_RELEASE_FALLBACK_MS)
    }

    private val startupWorkFallback = Runnable { releaseStartupWork() }

    /** 首帧已渲染（由 [MainActivity] 转发）：立刻放行后台任务，不再等兜底 */
    fun onFirstFrameRendered() {
        view?.removeCallbacks(startupWorkFallback)
        releaseStartupWork()
    }

    /**
     * 放行启动阶段被压住的后台任务。
     *
     * 这三件都是网络密集的：拉订阅源、拉 EPG 节目单、探活最多 200 条线路（10 路并发）。
     * 它们在起播期间会把首帧从约 2s 拖到约 9s（实测），所以统一等到画面出来再跑。
     * 只放行一次——后续的换源、列表刷新已不在启动争抢窗口内。
     */
    private fun releaseStartupWork() {
        if (startupWorkReleased) return
        startupWorkReleased = true
        updateEpg()
        refreshFromRemote()
        probePriority()
        // 起播稳定后补一次相邻台预热。
        // 启动路径不经过 switchTo()/play()，所以"启动后的第一次换台"目标台还是冷的：
        // 实测 CCTV-1 → CCTV-2（目标台未预热）4030ms，而换到已预热过的 CCTV-3 只要 1941ms。
        preheatAround(itemPosition)
    }

    /**
     * 按优先级探活。顺序即优先级——[ChannelProbe] 按传入顺序取前 N 条，所以当前频道一定排最前：
     * 1. **当前播放频道**的线路（用户马上要用它）；
     * 2. **当前分组**（正在浏览）；
     * 3. **其余分组**（后台慢慢铺，切过去时不再是满屏"未探测"）。
     */
    private fun probePriority() {
        val current = tvListViewModel.getTVViewModel(itemPosition)?.getTV()
        val currentGroupName = groupNames.getOrNull(currentGroupIndex())
        val inGroup = currentGroupName?.let { currentGroups[it] }.orEmpty()
        val others = groupNames.filter { it != currentGroupName }
            .flatMap { currentGroups[it].orEmpty() }
        val targets = listOfNotNull(current) + inGroup + others
        if (targets.isEmpty()) return
        ChannelProbe.probeAsync(targets) { refreshIfAlive() }
        startProbeBacklog()
    }

    /**
     * 后台补探：每隔一段时间挑一批**还没探过**的频道继续探，把"未探测"逐步清掉。
     *
     * 限制轮数——探活本身已有 10 分钟重探间隔，这里只负责"第一次覆盖"，
     * 不做无休止轮询，避免给源站持续压力。
     */
    private fun startProbeBacklog() {
        if (backlogJob?.isActive == true) return
        backlogJob = viewLifecycleOwner.lifecycleScope.launch {
            var rounds = 0
            while (isActive && rounds < BACKLOG_MAX_ROUNDS) {
                delay(BACKLOG_INTERVAL_MS)
                rounds++
                val targets = currentGroups.values.asSequence()
                    .flatten()
                    .filter { tv -> tv.videoUrl.any { !ChannelProbe.isProbed(it) } }
                    .toList()
                if (targets.isEmpty()) break
                Log.i(TAG, "probe backlog round $rounds: ${targets.size} channels")
                ChannelProbe.probeAsync(targets) { refreshIfAlive() }
            }
        }
    }

    /**
     * 探活回调统一入口：视图已销毁时不再触碰 adapter。
     *
     * 同时立刻重排当前频道的线路——让"实测优选"即时生效，而不是等下次启动才用上探活结论。
     */
    private fun refreshIfAlive() {
        if (_binding == null) return
        channelAdapter.refresh()
        tvListViewModel.getTVViewModel(itemPosition)?.resortLines()
    }

    private fun probeGroupIfNeeded(groupIndex: Int) {
        if (groupIndex < 0 || groupIndex == lastProbedGroup) return
        lastProbedGroup = groupIndex
        groupNames.getOrNull(groupIndex)?.let { probeScope(setOf(it)) }
    }

    // ---------------- 频道状态观察 ----------------

    /** 单个频道的状态监听（列表重建时逐个重新注册） */
    private fun observeTVViewModel(tvViewModel: TVViewModel) {
        tvViewModel.errInfo.observe(viewLifecycleOwner) { _ ->
            val info = tvViewModel.errInfo.value
            if (info != null && tvViewModel.getTV().id == itemPosition) {
                // 走统一出口：信息条说明 + 有上限的自动跳过，而不是弹一个会消失的 Toast
                (activity as? MainActivity)?.onChannelUnavailable(info)
            }
        }
        tvViewModel.ready.observe(viewLifecycleOwner) { _ ->
            if (tvViewModel.ready.value != null
                && tvViewModel.getTV().id == itemPosition
                && check(tvViewModel)
            ) {
                Log.i(TAG, "ready ${tvViewModel.getTV().title}")
                (activity as? MainActivity)?.play(tvViewModel)
            }
        }
        tvViewModel.change.observe(viewLifecycleOwner) { _ ->
            if (tvViewModel.change.value != null) {
                val title = tvViewModel.getTV().title
                Log.i(TAG, "switch $title")
                if (tvViewModel.getTV().pid != "") {
                    Log.i(TAG, "request $title")
                    lifecycleScope.launch(Dispatchers.IO) {
                        tvViewModel.let { Request.fetchData(it) }
                    }
                    (activity as? MainActivity)?.showInfoFragment(tvViewModel)
                } else {
                    if (check(tvViewModel)) {
                        (activity as? MainActivity)?.play(tvViewModel)
                        (activity as? MainActivity)?.hideListAndPlay()
                        val epg = EpgStore.find(
                            tvViewModel.getTV().title,
                            tvViewModel.getTV().tvgId,
                        )
                        if (epg.isNotEmpty()) {
                            tvViewModel.addDirectEPG(epg)
                        }
                        (activity as? MainActivity)?.showInfoFragment(tvViewModel)
                    }
                }
            }
        }
    }

    // ---------------- 线路切换 ----------------

    fun prevSource() {
        view?.post {
            val tvViewModel = tvListViewModel.getTVViewModel(itemPosition) ?: return@post
            val size = tvViewModel.videoUrl.value?.size ?: 0
            if (size <= 1) return@post
            val idx = ((tvViewModel.videoIndex.value ?: 0) - 1 + size) % size
            tvViewModel.setVideoIndex(idx)
            tvViewModel.changed()
            // 必须放在 changed() 之后：change 观察者会刷新频道信息条，顺序反了会把线路提示顶掉
            showSourceToast(tvViewModel, idx, size)
        }
    }

    fun nextSource() {
        view?.post {
            val tvViewModel = tvListViewModel.getTVViewModel(itemPosition) ?: return@post
            val size = tvViewModel.videoUrl.value?.size ?: 0
            if (size <= 1) return@post
            val idx = ((tvViewModel.videoIndex.value ?: 0) + 1) % size
            tvViewModel.setVideoIndex(idx)
            tvViewModel.changed()
            showSourceToast(tvViewModel, idx, size)
        }
    }

    /**
     * 按 URL 切换线路（供线路列表使用）。
     *
     * **不能用下标**：线路列表展示的是"打开时"的顺序快照，而 `videoUrl` 可能已被动态重排；
     * 两者顺序不一致时按下标会切到错误的线路。[displayPosition] 是用户在列表里看到的位置，
     * 仅用于提示文案（保证提示与他看到的编号一致）。
     */
    fun selectSourceByUrl(url: String, displayPosition: Int) {
        view?.post {
            val tvViewModel = tvListViewModel.getTVViewModel(itemPosition) ?: return@post
            val urls = tvViewModel.videoUrl.value ?: return@post
            val index = urls.indexOf(url)
            if (index < 0) {
                Log.w(TAG, "selectSourceByUrl: line not found $url")
                return@post
            }
            Log.i(
                TAG,
                "select line: display=${displayPosition + 1} -> actual=${index + 1} $url"
            )
            // 记住用户的手动选择（下次该频道优先用它）——这是比自动轮换更强的偏好信号
            TVList.rememberPreference(tvViewModel.getTV(), url)
            tvViewModel.setVideoIndex(index)
            tvViewModel.changed()
            showSourceToast(tvViewModel, displayPosition, urls.size)
        }
    }

    /** 线路切换提示：走信息条，避免播放中弹 Toast */
    private fun showSourceToast(tvViewModel: TVViewModel, index: Int, size: Int) {
        (activity as? MainActivity)?.showInfoMessage(
            "${tvViewModel.getTV().title} 线路 ${index + 1}/$size"
        )
    }

    // ---------------- 播放控制 ----------------

    fun check(tvViewModel: TVViewModel): Boolean {
        val title = tvViewModel.getTV().title
        val videoUrl = tvViewModel.videoIndex.value?.let { tvViewModel.videoUrl.value?.get(it) }
        if (videoUrl.isNullOrEmpty()) {
            Log.e(TAG, "$title videoUrl is empty")
            return false
        }
        // 去重必须带上频道：不同频道共用同一个流的情况真实存在（源里很常见），
        // 只比 URL 会把"切到另一个台"误判成重复，结果界面不换、也不报错，静默卡住。
        if (videoUrl == lastVideoUrl && tvViewModel.getTV().id == lastChannelId) {
            Log.i(TAG, "$title videoUrl is duplication")
            return false
        }
        lastVideoUrl = videoUrl
        lastChannelId = tvViewModel.getTV().id
        return true
    }

    /** 当前播放/选中的频道 */
    fun getCurrentTVViewModel(): TVViewModel? = tvListViewModel.getTVViewModel(itemPosition)

    /**
     * 相邻频道的 ViewModel（供播放器的预加载使用），offset 为 ±1，越界自动回绕。
     * 列表只有一个频道时返回 null——没有"下一个"可以预备。
     */
    fun neighborTVViewModel(offset: Int): TVViewModel? {
        val size = tvListViewModel.size()
        if (size <= 1) return null
        return tvListViewModel.getTVViewModel(Math.floorMod(itemPosition + offset, size))
    }

    /**
     * 按频道 id 取 ViewModel（id 即列表序号）。
     *
     * 供播放器的"回翻预加载"使用：上下反复翻台时，下一个要看的是**刚离开的那个台**，
     * 它不一定是当前台的相邻台——在列表首尾处相邻台会绕到另一头，而"刚离开的台"不会。
     */
    fun tvViewModelById(id: Int): TVViewModel? =
        if (id >= 0 && id < tvListViewModel.size()) tvListViewModel.getTVViewModel(id) else null

    fun fragmentReady() {
        tvListViewModel.getTVViewModel(itemPosition)?.changed()
        tvListViewModel.tvListViewModel.value?.forEach { updateEPG(it) }
    }

    fun play(itemPosition: Int) {
        view?.post {
            if (itemPosition > -1 && itemPosition < tvListViewModel.size()) {
                // 明确选台（数字键/列表选中）立即起播，不再走换台去抖
                switchHandler.removeCallbacks(playSwitch)
                pendingSwitch = null
                this.itemPosition = itemPosition
                tvListViewModel.setItemPosition(itemPosition)
                tvListViewModel.getTVViewModel(itemPosition)?.changed()
                preheatAround(itemPosition)
            } else {
                Toast.makeText(context, "频道不存在", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun prev() {
        view?.post {
            val size = tvListViewModel.size()
            if (size == 0) return@post
            switchTo(if (itemPosition <= 0) size - 1 else itemPosition - 1)
        }
    }

    fun next() {
        view?.post {
            val size = tvListViewModel.size()
            if (size == 0) return@post
            switchTo(if (itemPosition >= size - 1) 0 else itemPosition + 1)
        }
    }

    /**
     * 换台：位置与信息条**立即**更新，但起播延迟 [SWITCH_DEBOUNCE_MS]。
     *
     * 连续换台时，中间那些频道原本也会各自去加载（白占带宽、还拖慢最后一次），
     * 去抖后只在用户停手时加载最后一个——"相邻换台老是缓冲"主要就是这个原因。
     */
    private fun switchTo(position: Int) {
        itemPosition = position
        tvListViewModel.setItemPosition(position)
        // 立即反馈"按到哪了"，不用等起播
        tvListViewModel.getTVViewModel(position)?.let {
            (activity as? MainActivity)?.showInfoFragment(it)
        }
        pendingSwitch = position
        switchHandler.removeCallbacks(playSwitch)
        switchHandler.postDelayed(playSwitch, SWITCH_DEBOUNCE_MS)
    }

    /**
     * 预热前后各 2 个频道的首选线路。
     *
     * "换台就有台"的关键动作：DNS / TCP / TLS / 302 都是硬延迟，等用户按下再去走就已经晚了。
     *
     * 取前后各 2 个而不是各 1 个：用户经常连按两下（CCTV-1 → CCTV-3），
     * 只预热相邻 1 个的话第二个目标还是冷的——实测未预热 4030ms，已预热 1941ms。
     * 偏移按"近 → 远"排列，[StreamPreheat] 依此顺序取用。
     */
    private fun preheatAround(position: Int) {
        val size = tvListViewModel.size()
        if (size <= 1) return
        val urls = intArrayOf(1, -1, 2, -2)
            .map { offset -> Math.floorMod(position + offset, size) }
            .distinct()
            .mapNotNull { tvListViewModel.getTVViewModel(it) }
            .flatMapIndexed { index, vm ->
                // 最近的邻居多预热一条候选线路：那条正是自动轮换 / 手动换线的下一个目标，
                // 提前握手能让"换线路"和换台一样快。播放列表只有几百字节，多一条的代价很小。
                vm.videoUrl.value.orEmpty().take(if (index == 0) 2 else 1)
            }
        StreamPreheat.warm(urls)
    }

    private val switchHandler = Handler(Looper.getMainLooper())

    private var pendingSwitch: Int? = null

    private val playSwitch = Runnable {
        pendingSwitch?.let {
            pendingSwitch = null
            tvListViewModel.getTVViewModel(it)?.changed()
            // 预热放在这里（位置已稳定）而不是每次按键里：连按时位置一路在变，
            // 对"路过"的台预热纯属白做，却会在后台积压成串的握手请求——
            // 实测连按 20 次会提交 17 轮共 20+ 个任务、持续近 9 秒才消化完，
            // 而用户 1.5 秒后就停手了，这些握手正好与他要看的那个台抢带宽。
            preheatAround(it)
        }
    }

    private fun updateEPG(tvViewModel: TVViewModel) {
        when (tvViewModel.getTV().programType) {
            ProgramType.F -> Request.fetchFEPG(tvViewModel)
            ProgramType.DIRECT -> {
                // 直连 IPTV 源暂无节目单
            }
        }
    }

    override fun onStop() {
        super.onStop()
        SP.itemPosition = itemPosition
        Log.i(TAG, "position $itemPosition saved")
    }

    override fun onDestroyView() {
        super.onDestroyView()
        backlogJob?.cancel()
        view?.removeCallbacks(startupWorkFallback)
        switchHandler.removeCallbacks(playSwitch)
        pendingSwitch = null
        _binding = null
    }

    companion object {
        private const val TAG = "MainFragment"

        /**
         * 换台去抖：停手这么久才真正起播。
         * 太短起不到作用（连按会逐个去加载），太长会让"按一下要等一下"变得明显。
         *
         * 由 350ms 下调到 150ms：实测相邻换台的首帧里，有 350ms 是纯等这个去抖，
         * 而遥控器连按的间隔通常快于 150ms，防连按的作用依然在。
         */
        private const val SWITCH_DEBOUNCE_MS = 150L

        /** 后台补探间隔：太短会给源站压力，太长则用户切过去后的空窗期太久 */
        private const val BACKLOG_INTERVAL_MS = 60_000L

        /** 后台补探轮数上限：避免为了"全量覆盖"持续发请求 */
        private const val BACKLOG_MAX_ROUNDS = 3

        /**
         * 启动阶段后台任务的兜底放行延迟。
         * 正常路径是"首帧出来后立刻放行"，这个值只在首帧迟迟不来时生效。
         * 取 8s：足够覆盖绝大多数起播（实测 1.3~2.0s），又不至于让探活 / EPG / 列表更新长时间不跑。
         */
        private const val STARTUP_RELEASE_FALLBACK_MS = 8_000L
    }
}
