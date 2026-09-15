package com.lizongying.mytv

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
import android.view.WindowManager
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.lizongying.mytv.models.TVViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch


class MainActivity : FragmentActivity(), Request.RequestListener {

    /** 已就绪的 Fragment 标签集合（比裸计数更健壮，视图重建重复回调也不会错乱） */
    private val readyFragments = mutableSetOf<String>()

    /** 首帧播放是否已启动，防止就绪信号重复触发 */
    private var playbackStarted = false

    private val playerFragment = PlayerFragment()
    private val mainFragment = MainFragment()
    private val infoFragment = InfoFragment()
    private val channelFragment = ChannelFragment()
    private var timeFragment = TimeFragment()
    private val settingFragment = SettingFragment()
    private val errorFragment = ErrorFragment()
    private val sourceFragment = SourceFragment()

    private var doubleBackToExitPressedOnce = false

    private var centerLongPressed = false

    private lateinit var gestureDetector: GestureDetector

    private val handler = Handler(Looper.getMainLooper())

    /** 连续不可播的频道数：达到上限就停下提示，避免无意义地一直自动跳过 */
    private var unavailableStreak = 0

    /** 待执行的自动跳过任务（用户一有操作就取消） */
    private var autoSkipTask: Runnable? = null

    /** 已自动换源次数：轮完所有候选源还不行就停手，避免无意义地反复切换 */
    private var sourceSwitchCount = 0

    /** 待执行的自动换源任务 */
    private var sourcePromptTask: Runnable? = null

    init {
        lifecycleScope.launch(Dispatchers.IO) {
            Utils.init()
            // 盒子长时间运行/待机后系统时钟会漂移（影响 EPG 与显示时间），定期重新校准；
            // 校准失败会静默退回设备时钟，不影响播放
            while (isActive) {
                delay(CLOCK_RESYNC_INTERVAL_MS)
                Utils.init()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        Log.i(TAG, "onCreate")
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_main)

        Request.setRequestListener(this)

        // 远程配置保存后原地刷新频道列表（不 recreate，避免中断播放）
        // 同时重新应用设置项：远程页的「恢复默认设置」会改到显示时间等开关
        (application as MyApplication).configServer.onConfigChanged = {
            reloadChannels()
            applySettings()
        }

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.decorView.systemUiVisibility = SYSTEM_UI_FLAG_HIDE_NAVIGATION

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .add(R.id.main_browse_fragment, playerFragment)
                .add(R.id.main_browse_fragment, timeFragment)
                .add(R.id.main_browse_fragment, infoFragment)
                .add(R.id.main_browse_fragment, channelFragment)
                .add(R.id.main_browse_fragment, mainFragment)
                .hide(mainFragment)
                .commit()
        }
        gestureDetector = GestureDetector(this, GestureListener())

        errorFragment.buttonClickListener = View.OnClickListener {
            supportFragmentManager.beginTransaction()
                .remove(errorFragment)
                .commit()
        }

        // 每秒刷新右上角时间（关闭时只在整半小时窗口内自动露面）
        startClockTick()
    }

    fun showInfoFragment(tvViewModel: TVViewModel) {
        infoFragment.show(tvViewModel)
        // 换台时的频道号：短暂显示后自动消失，无需开关控制
        channelFragment.show(tvViewModel)
    }

    /** 信息条轻提示（线路切换等），替代播放中满屏 Toast */
    fun showInfoMessage(message: String) {
        infoFragment.showMessage(message)
    }

    /** 原地重载频道列表：换源 / 收藏变更 / 远程配置保存后调用，避免 recreate */
    fun reloadChannels() {
        mainFragment.reload()
    }

    /** 重新应用设置项（如恢复默认后同步时间显示） */
    fun applySettings() {
        showTime()
        // 预加载开关可能刚被改动：开启则重新准备，关闭则立刻释放待命播放器
        playerFragment.onPreloadSettingChanged()
    }

    /** 相邻频道的 ViewModel（供播放器预加载用），offset 为 ±1 */
    fun neighborTVViewModel(offset: Int): TVViewModel? =
        mainFragment.neighborTVViewModel(offset)

    /** 按频道 id 取 ViewModel（供播放器"回翻预加载"用，见 MainFragment.tvViewModelById） */
    fun tvViewModelById(id: Int): TVViewModel? =
        mainFragment.tvViewModelById(id)

    /** 打开线路列表：列出当前频道的所有线路及其画质/延迟/录像标记 */
    fun showSourceList() {
        val vm = mainFragment.getCurrentTVViewModel() ?: return
        val urls = vm.videoUrl.value ?: return
        if (urls.size <= 1) {
            showInfoMessage("${vm.getTV().title} 只有一条线路")
            return
        }
        if (sourceFragment.isVisible) return

        val current = vm.videoIndex.value ?: 0
        // 打开时**固定顺序快照**：探活结果只更新文案，不改变顺序与「当前」标记。
        // 否则运行时的动态重排会让列表在用户眼皮底下重新排序（第 i 行换成另一条线路，焦点却没动）。
        val snapshot = urls.toList()
        val currentUrl = urls.getOrNull(current)

        fun buildItems() = snapshot.mapIndexed { index, url ->
            SourceFragment.Item(
                title = "线路 ${index + 1}" + if (url == currentUrl) "（当前）" else "",
                detail = describeSource(vm.getTV(), url),
                current = url == currentUrl,
            )
        }
        // 按 URL 而不是下标回调：列表用的是「打开时」的顺序快照，
        // 而 videoUrl 可能已被动态重排，两者顺序不一致时按下标会切到错误的线路
        sourceFragment.onSelected = { position ->
            snapshot.getOrNull(position)?.let { mainFragment.selectSourceByUrl(it, position) }
        }
        sourceFragment.setData("切换线路 · ${vm.getTV().title}", buildItems())
        sourceFragment.show(supportFragmentManager, "source")

        // 按需探活本频道的线路：否则探活范围收窄后，这里会大量显示"未探测"
        ChannelProbe.probeAsync(listOf(vm.getTV())) {
            if (sourceFragment.isVisible) sourceFragment.updateDetails(buildItems())
        }
    }

    /**
     * 数据源面板：列出全部候选源及其健康状况，选中即切换（改为只使用它）。
     *
     * 之前"看源/换源"只能开浏览器，对只看电视的人太麻烦；
     * 地址的**编辑**仍留在远程页（遥控器输字符不现实），这里只做**选择**。
     */
    fun showSourcePicker() {
        val candidates = TVList.allSources()
        if (candidates.isEmpty()) return
        val stats = SourceHealth.snapshot()
        val currentList = TVList.currentSources()

        fun buildItems() = candidates.map { url ->
            val agg = stats[url]
            val detail = if (agg == null) {
                "暂无记录"
            } else {
                "成功率 ${agg.successRate}% (${agg.okCount}/${agg.attempts}) · " +
                        "平均 ${agg.avgMs}ms · 最近 ${agg.lastChannels} 台"
            }
            val isCurrent = currentList.contains(url)
            // 多源模式下所有源都在用，全标"（当前）"会失去意义：
            // 只有单选一个源时才强调"当前"，多源时标"已启用"
            val single = currentList.size == 1
            SourceFragment.Item(
                // 只在"单选一个源"时标记，多源模式下全都在用、标记没有信息量，还会把标题挤到截断
                title = SourceHealth.label(url) + if (single && isCurrent) "（当前）" else "",
                detail = detail,
                current = single && isCurrent,
            )
        }

        sourceFragment.onSelected = { index ->
            candidates.getOrNull(index)?.let { switchToSource(it) }
        }
        sourceFragment.setData(
            "数据源 · ${candidates.size} 个",
            buildItems(),
            "上下选择数据源，OK 切换为只使用它，返回键关闭",
        )
        sourceFragment.show(supportFragmentManager, "source")
    }

    /** 切换到指定数据源：旧缓存、探活与失败记忆都属于上一个源，一并清掉 */
    private fun switchToSource(url: String) {
        cancelSourcePrompt()
        cancelAutoSkip()
        unavailableStreak = 0
        SP.iptvSourceUrl = url
        ChannelCache.clear()
        ChannelProbe.reset()
        LineHealth.reset()
        SP.itemPosition = 0
        showInfoMessage("已切换到 ${SourceHealth.label(url)}，正在重新加载")
        reloadChannels()
    }

    /** 线路信息描述：画质 · 探测状态 · 录像标记 · 域名 */
    private fun describeSource(tv: TV, url: String): String {
        val parts = mutableListOf<String>()
        parts.add(TVList.qualityLabel(tv, url))
        // 本机没有 IPv6 出口时，这类线路"探测失败"是设备能力问题，说清楚避免误判
        if (Utils.isIpv6Url(url) && !Utils.hasIpv6) {
            parts.add("本机无 IPv6")
        } else {
            parts.add(ChannelProbe.label(url))
        }
        if (TVList.isLoopSuspectLine(url)) parts.add("疑似录像")
        // 优先显示探活得到的真实来源（302 之后）：入口域名可能只是调度器，
        // 真实服务器才代表"这条线其实连到哪"，也是判断"多条线路是否同源"的依据
        val realHost = ChannelProbe.finalHostOf(url)
        val entryHost = runCatching { android.net.Uri.parse(url).host }.getOrNull()
        val host = realHost?.takeIf { it.isNotBlank() } ?: entryHost
        if (!host.isNullOrBlank()) parts.add(host)
        return parts.joinToString(" · ")
    }

    private fun showChannel(channel: String) {
        if (settingFragment.isVisible) {
            return
        }

        // 数字键选台时显示输入的频道号，随后自动消失。
        // 列表打开时同样允许输入：直接跳到该编号并收起列表。
        channelFragment.show(channel)
    }

    fun play(tvViewModel: TVViewModel) {
        playerFragment.play(tvViewModel)
        mainFragment.view?.requestFocus()
    }

    fun play(itemPosition: Int) {
        mainFragment.play(itemPosition)
    }

    /**
     * 数字选台：编号即列表序号（列表里显示几就是第几条）。
     * 不再用源里的 tvg-chno——两套编号混用会出现"显示 5 却跳到别的台"。
     */
    fun playByNumber(number: Int) {
        play(number - 1)
        // 即使输入的正好是当前频道（URL 去重不会触发 change），也要把列表收起来
        hideListAndPlay()
    }

    fun prev() {
        mainFragment.prev()
    }

    fun next() {
        mainFragment.next()
    }

    private fun prevSource() {
        mainFragment.prevSource()
    }

    private fun nextSource() {
        mainFragment.nextSource()
    }

    fun switchMainFragment() {
        val transaction = supportFragmentManager.beginTransaction()

        if (mainFragment.isHidden) {
            transaction.show(mainFragment)
            // 打开时滚动到当前频道并聚焦，省得用户从列表头翻起
            mainFragment.onShown()
        } else {
            transaction.hide(mainFragment)
        }

        transaction.commit()
    }

    private fun mainFragmentIsHidden(): Boolean {
        return mainFragment.isHidden
    }

    private fun hideMainFragment() {
        if (!mainFragment.isHidden) {
            supportFragmentManager.beginTransaction()
                .hide(mainFragment)
                .commit()
        }
    }

    /** 播放时确保频道列表收起 */
    fun hideListAndPlay() {
        if (!mainFragment.isHidden) {
            hideMainFragment()
        }
    }

    /**
     * 首帧已渲染。
     * 用来放行那些"会抢带宽"的后台任务——目前是线路探活：
     * 它在起播期间跑会把首帧从约 2s 拖到约 9s（实测），所以推迟到画面出来之后。
     */
    fun onFirstFrameRendered() {
        mainFragment.onFirstFrameRendered()
    }

    /** OK 键：短按=切换频道列表，长按=打开当前频道的线路列表 */
    private fun handleCenterKey(event: KeyEvent?) {
        if (event?.action == KeyEvent.ACTION_DOWN && event.repeatCount == 1) {
            centerLongPressed = true
            showSourceList()
        }
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            if (centerLongPressed) {
                // 长按已处理收藏，抬起时不再切换
                centerLongPressed = false
                return true
            }
            switchMainFragment()
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            if (settingFragment.isVisible) return true
            val isLeft = keyCode == KeyEvent.KEYCODE_DPAD_LEFT
            if (mainFragment.isHidden) {
                // 播放中：短按切上/下一条线路
                if (isLeft) prevSource() else nextSource()
            } else {
                // 面板打开：在「分组列 ↔ 频道列」之间移动焦点
                mainFragment.moveFocus(isLeft)
            }
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    fun fragmentReady(tag: String) {
        readyFragments.add(tag)
        Log.i(TAG, "ready $tag ${readyFragments.size}")
        if (!playbackStarted && readyFragments.containsAll(REQUIRED_FRAGMENTS)) {
            playbackStarted = true
            mainFragment.fragmentReady()
            showTime()
        }
    }

    /**
     * 右上角时间：
     * - 设置里打开 → 常显；
     * - 默认关闭 → 只在**整半小时**附近自动露面（提前 5 秒出现、持续 20 秒），
     *   既随时知道时间，又不长期占着画面。
     */
    private fun showTime() {
        if (SP.time) {
            timeFragment.show()
            return
        }
        if (inHalfHourWindow()) timeFragment.show() else timeFragment.hide()
    }

    /** 是否落在"整半小时"的显示窗口内（:29:55~:30:15 与 :59:55~:00:15，各 20 秒） */
    private fun inHalfHourWindow(): Boolean {
        // 时区偏移都是整小时，所以"时内秒数"在任何时区都一致
        val secOfHour = (Utils.getDateTimestamp() % 3600L).toInt()
        if (secOfHour in HALF_HOUR_LEAD_START..HALF_HOUR_TAIL_END) return true
        return secOfHour >= FULL_HOUR_LEAD_START || secOfHour <= WINDOW_TAIL_SECONDS
    }

    /** 每秒刷新时间显示（常显时无副作用，关闭时负责在整半小时窗口内自动现身） */
    private fun startClockTick() {
        handler.post(object : Runnable {
            override fun run() {
                showTime()
                handler.postDelayed(this, 1_000L)
            }
        })
    }

    fun isPlaying() {
        // 播出来了：清空连续不可播与换源计数，并取消待执行的自动跳过
        unavailableStreak = 0
        sourceSwitchCount = 0
        cancelAutoSkip()
        cancelSourcePrompt()
        if (errorFragment.isVisible) {
            supportFragmentManager.beginTransaction()
                .remove(errorFragment)
                .commit()
        }
    }

    /**
     * 频道不可播的统一出口。
     *
     * 覆盖所有"没有终态"的路径：全部线路失败、没有可用线路、凤凰频道取流失败、缓冲超时。
     * 早期这些路径只弹一个会消失的 Toast 或仅打日志，画面就停在黑屏 + 转圈图标上，
     * 用户既不知道是继续等还是该换台。
     *
     * 处理：信息条说明原因 + 自动跳到下一个频道；连续多个都不可播就停下提示
     * （那通常是网络或数据源的问题，继续跳没有意义）。
     */
    fun onChannelUnavailable(message: String) {
        cancelAutoSkip()
        unavailableStreak++
        if (unavailableStreak >= MAX_AUTO_SKIP) {
            // 连续多个频道都播不出来 → 大概率是数据源失效，直接给出"一键换源"提示
            Log.i(TAG, "channels unavailable x$unavailableStreak, prompt source switch")
            promptSwitchSource("频道暂时都播不出来\n可能是数据源失效了")
            return
        }
        Log.i(TAG, "unavailable $unavailableStreak: $message")
        showInfoMessage("$message，${AUTO_SKIP_DELAY_MS / 1000} 秒后自动跳过")
        val task = Runnable {
            autoSkipTask = null
            mainFragment.next()
        }
        autoSkipTask = task
        handler.postDelayed(task, AUTO_SKIP_DELAY_MS)
    }

    /** 用户一有操作就取消待执行的自动跳过，避免"刚按了键画面又自己跳走" */
    private fun cancelAutoSkip() {
        autoSkipTask?.let { handler.removeCallbacks(it) }
        autoSkipTask = null
    }

    /**
     * 疑似数据源失效：全屏提示 + 单个按钮。
     *
     * 为什么做成"提示页"而不是小弹窗：电视上弹窗的按钮焦点不可控，
     * 而这个页面的焦点一定在按钮上，老人按一次 OK 就能换源；
     * 就算完全不操作，倒计时结束后也会自动换源——尽量不给用户留操作负担。
     */
    private fun promptSwitchSource(reason: String) {
        if (sourceSwitchCount >= TVList.sourceCandidateCount()) {
            unavailableStreak = 0
            showInfoMessage("试过的数据源都不可用，请检查网络或稍后再试")
            return
        }
        if (!errorFragment.isVisible) {
            supportFragmentManager.beginTransaction()
                .add(R.id.main_browse_fragment, errorFragment)
                .commitNow()
        }
        val seconds = SOURCE_SWITCH_DELAY_MS / 1000
        errorFragment.setSwitchSourceContent(
            "$reason\n\n按 OK 换一个数据源\n（$seconds 秒后也会自动切换）",
            "换个数据源"
        ) { switchSourceNow() }

        cancelSourcePrompt()
        val task = Runnable { switchSourceNow() }
        sourcePromptTask = task
        handler.postDelayed(task, SOURCE_SWITCH_DELAY_MS)
    }

    /**
     * 频道列表为空（源全部失败且没有兜底频道）：这是"连台都没有"的最坏情况，
     * 直接给出换源提示，避免用户面对一个全黑且没有任何文字的屏幕。
     */
    fun onChannelListEmpty() {
        promptSwitchSource("频道列表加载失败\n可能是数据源失效了")
    }

    private fun cancelSourcePrompt() {
        sourcePromptTask?.let { handler.removeCallbacks(it) }
        sourcePromptTask = null
    }

    /**
     * 一键换源：切到下一个候选数据源并就地重载。
     * 旧缓存、旧的探活与失败记录都属于上一个源，一并清掉，避免"换了源还沿用坏结论"。
     */
    fun switchSourceNow() {
        cancelSourcePrompt()
        cancelAutoSkip()
        sourceSwitchCount++
        unavailableStreak = 0
        TVList.rotateSource()
        ChannelCache.clear()
        ChannelProbe.reset()
        LineHealth.reset()
        SP.itemPosition = 0
        if (errorFragment.isVisible) {
            supportFragmentManager.beginTransaction()
                .remove(errorFragment)
                .commit()
        }
        // 只告诉用户"换好了"，不展示域名（对老人来说域名没有意义）
        showInfoMessage("已自动换个数据源，正在重新加载")
        reloadChannels()
    }

    override fun onTouchEvent(event: MotionEvent?): Boolean {
        if (event != null) {
            gestureDetector.onTouchEvent(event)
        }
        return super.onTouchEvent(event)
    }

    private inner class GestureListener : GestureDetector.SimpleOnGestureListener() {

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            switchMainFragment()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            showSetting()
            return true
        }

        override fun onFling(
            e1: MotionEvent?,
            e2: MotionEvent,
            velocityX: Float,
            velocityY: Float
        ): Boolean {
            if (velocityY > 0) {
                if (mainFragment.isHidden) {
                    prev()
                } else {
//                    if (mainFragment.selectedPosition == 0) {
//                        mainFragment.setSelectedPosition(
//                            mainFragment.tvListViewModel.maxNum.size - 1,
//                            false
//                        )
//                    }
                }
            }
            if (velocityY < 0) {
                if (mainFragment.isHidden) {
                    next()
                } else {
//                    if (mainFragment.selectedPosition == mainFragment.tvListViewModel.maxNum.size - 1) {
////                        mainFragment.setSelectedPosition(0, false)
//                        hideMainFragment()
//                        return false
//                    }
                }
            }
            return super.onFling(e1, e2, velocityX, velocityY)
        }
    }

    /**
     * 打开/关闭设置面板（MENU 键 / 触屏双击 / 选台面板里的"设置"入口）。
     * 不自动收起：用户可能在照着屏幕上的远程配置地址输入，被关掉会很难受。
     */
    fun showSetting() {
        Log.i(TAG, "settingFragment ${settingFragment.isVisible}")
        if (settingFragment.isVisible) {
            settingFragment.dismiss()
        } else {
            settingFragment.show(supportFragmentManager, "setting")
        }
    }

    /**
     * 上一频道。
     * 面板与对话框（线路列表 / 设置）打开时，上下键属于"在面板内移动焦点"，**绝不能切台**——
     * 它们都是 DialogFragment，不影响 [mainFragment] 的 hidden 状态，
     * 早期只判断 `isHidden` 会导致"在线路列表里按上下键，频道被切走"、列表根本选不了。
     */
    private fun channelUp() {
        if (sourceFragment.isVisible || settingFragment.isVisible) return
        if (mainFragment.isHidden) prev()
    }

    /** 下一频道（规则同 [channelUp]） */
    private fun channelDown() {
        if (sourceFragment.isVisible || settingFragment.isVisible) return
        if (mainFragment.isHidden) next()
    }

    private fun back() {
        // 设置页打开时，返回键只负责关掉设置，不计入"再按一次退出"
        if (settingFragment.isVisible) {
            settingFragment.dismiss()
            return
        }

        if (!mainFragmentIsHidden()) {
            hideMainFragment()
            return
        }

        if (doubleBackToExitPressedOnce) {
            super.onBackPressed()
            return
        }

        doubleBackToExitPressedOnce = true
        Toast.makeText(this, "再按一次退出", Toast.LENGTH_SHORT).show()

        Handler(Looper.getMainLooper()).postDelayed({
            doubleBackToExitPressedOnce = false
        }, 2000)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // 用户开始操作就取消自动跳过（否则按键后画面可能突然自己跳走）
        cancelAutoSkip()
        Log.i(TAG, "keyCode $keyCode, event $event")
        when (keyCode) {
            KeyEvent.KEYCODE_0 -> {
                showChannel("0")
                return true
            }

            KeyEvent.KEYCODE_1 -> {
                showChannel("1")
                return true
            }

            KeyEvent.KEYCODE_2 -> {
                showChannel("2")
                return true
            }

            KeyEvent.KEYCODE_3 -> {
                showChannel("3")
                return true
            }

            KeyEvent.KEYCODE_4 -> {
                showChannel("4")
                return true
            }

            KeyEvent.KEYCODE_5 -> {
                showChannel("5")
                return true
            }

            KeyEvent.KEYCODE_6 -> {
                showChannel("6")
                return true
            }

            KeyEvent.KEYCODE_7 -> {
                showChannel("7")
                return true
            }

            KeyEvent.KEYCODE_8 -> {
                showChannel("8")
                return true
            }

            KeyEvent.KEYCODE_9 -> {
                showChannel("9")
                return true
            }

            KeyEvent.KEYCODE_ESCAPE -> {
                back()
                return true
            }

            KeyEvent.KEYCODE_BACK -> {
                back()
                return true
            }

            KeyEvent.KEYCODE_BOOKMARK -> {
                showSetting()
                return true
            }

            KeyEvent.KEYCODE_UNKNOWN -> {
                showSetting()
                return true
            }

            KeyEvent.KEYCODE_HELP -> {
                showSetting()
                return true
            }

            KeyEvent.KEYCODE_SETTINGS -> {
                showSetting()
                return true
            }

            KeyEvent.KEYCODE_MENU -> {
                showSetting()
                return true
            }

            KeyEvent.KEYCODE_ENTER -> {
                handleCenterKey(event)
                return true
            }

            KeyEvent.KEYCODE_DPAD_CENTER -> {
                handleCenterKey(event)
                return true
            }

            KeyEvent.KEYCODE_DPAD_UP -> {
                channelUp()
            }

            KeyEvent.KEYCODE_CHANNEL_UP -> {
                channelUp()
            }

            KeyEvent.KEYCODE_DPAD_DOWN -> {
                channelDown()
            }

            KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                channelDown()
            }

            KeyEvent.KEYCODE_DPAD_LEFT -> {
                // 播放中=切上一条线路；列表打开=切上一个分组（动作统一在 onKeyUp 处理）
                return true
            }

            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                // 播放中=切下一条线路；列表打开=切下一个分组（动作统一在 onKeyUp 处理）
                return true
            }
        }

        return super.onKeyDown(keyCode, event)
    }

    override fun onStart() {
        Log.i(TAG, "onStart")
        super.onStart()
    }

    override fun onResume() {
        Log.i(TAG, "onResume")
        super.onResume()
    }

    override fun onPause() {
        Log.i(TAG, "onPause")
        super.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        Request.onDestroy()
    }

    override fun onRequestFinished(message: String?) {
        if (message != null && !errorFragment.isVisible) {
            supportFragmentManager.beginTransaction()
                .add(R.id.main_browse_fragment, errorFragment)
                .commitNow()
            errorFragment.setErrorContent(message)
        }
    }

    private companion object {
        const val TAG = "MainActivity"

        /** 整半小时显示窗口：:29:55 开始（提前 5 秒）、到 :30:15 结束（共 20 秒） */
        const val HALF_HOUR_LEAD_START = 29 * 60 + 55

        const val HALF_HOUR_TAIL_END = 30 * 60 + 15

        /** 整点窗口的起点 :59:55（终点复用 [WINDOW_TAIL_SECONDS]，跨到下一小时） */
        const val FULL_HOUR_LEAD_START = 59 * 60 + 55

        /** 窗口结束的"分钟后秒数"：:00:15 / :30:15 共用 */
        const val WINDOW_TAIL_SECONDS = 15

        /** 时钟重新校准间隔：长时间运行后设备时钟漂移会直接影响 EPG 匹配与显示时间 */
        const val CLOCK_RESYNC_INTERVAL_MS = 6 * 3600_000L

        /** 不可播频道自动跳过的等待时间：留给用户看清提示 */
        const val AUTO_SKIP_DELAY_MS = 4_000L

        /** 最多连续自动跳过几个频道；超过说明是网络/数据源问题，改为提示换源 */
        const val MAX_AUTO_SKIP = 3

        /** 换源提示的自动执行等待时间：老人不操作时也会自动换，避免停在提示页 */
        const val SOURCE_SWITCH_DELAY_MS = 20_000L

        /** 起播所需的 Fragment 全部就绪后才开始播放 */
        val REQUIRED_FRAGMENTS = setOf(
            "PlayerFragment",
            "TimeFragment",
            "InfoFragment",
            "ChannelFragment",
            "MainFragment",
        )
    }
}