/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.xposed.systemui.lyric

import android.graphics.Rect
import android.os.Handler
import android.view.View
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.lyric.style.LyricStyle
import io.github.proify.lyricon.statusbarlyric.StatusBarLyric
import io.github.proify.lyricon.statusbarlyric.SuperLogo
import io.github.proify.lyricon.subscriber.ActivePlayerListener
import io.github.proify.lyricon.subscriber.ProviderInfo
import io.github.proify.lyricon.xposed.logger.YLog
import io.github.proify.lyricon.xposed.systemui.hook.OplusCapsuleHooker
import io.github.proify.lyricon.xposed.systemui.hook.MiuiIslandHooker
import io.github.proify.lyricon.xposed.systemui.lyric.StatusBarViewManager.MAIN_LOOPER
import io.github.proify.lyricon.xposed.systemui.util.NotificationCoverHelper
import java.io.File

/**
 * 歌词视图核心控制器 (Lyric View Controller)
 * * 负责接收播放器状态、歌曲信息及系统 UI 变更，并将数据分发至所有已注册的状态栏控制器。
 * 实现了 [ActivePlayerListener]、[OplusCapsuleHooker.CapsuleStateChangeListener] 等核心接口。
 * * @author Tomakino
 * @since 2026
 */
object LyricViewController : ActivePlayerListener,
    OplusCapsuleHooker.CapsuleStateChangeListener,
    MiuiIslandHooker.IslandStateChangeListener,
    NotificationCoverHelper.OnCoverUpdateListener {

    private const val TAG = "LyricViewController"
    private const val DEBUG = true

    /** 当前播放状态，线程安全可见 */
    @Volatile
    var isPlaying: Boolean = false
        private set

    /** 当前活跃播放器的包名 */
    @Volatile
    var activePackage: String = ""
        private set

    /** 是否显示翻译内容 */
    @Volatile
    private var isDisplayTranslation: Boolean = true

    /** 是否显示罗马音内容 */
    @Volatile
    private var isDisplayRoma: Boolean = true

    /** 当前歌曲的逻辑播放进度（毫秒） */
    @Volatile
    private var currentLogicPosition: Long = 0

    /** 用于处理 UI 刷新任务的 Handler */
    private val mainHandler by lazy { Handler(MAIN_LOOPER) }

    /** * 高频进度更新任务。
     * 使用单例 Runnable 减少 GC 压力，仅在进度变更时由主线程调度。
     */
    private val frameUpdater = Runnable {
        val controllers = StatusBarViewManager.controllers
        for (i in controllers.indices) {
            controllers[i].lyricView.setPosition(currentLogicPosition)
        }
    }

    init {
        if (DEBUG) YLog.debug(TAG, "Initializing LyricViewController...")
        // 注册数据总线、系统钩子及封面更新监听
        LyricDataHub.addListener(this)
        OplusCapsuleHooker.registerListener(this)
        MiuiIslandHooker.registerListener(this)
        NotificationCoverHelper.registerListener(this)
    }

    /**
     * 当歌曲发生切换时回调。
     * @param song 新歌曲对象，若停止播放则为 null
     */
    override fun onSongChanged(song: Song?) {
        updateAllControllers {
            lyricView.setSong(song)
            refreshTranslationVisibility(lyricView)
        }
    }

    /**
     * 当活跃播放源发生切换时回调（如从网易云切换至 QQ 音乐）。
     * @param providerInfo 播放器信息
     */
    override fun onActiveProviderChanged(providerInfo: ProviderInfo?) {
        this.activePackage = providerInfo?.playerPackageName.orEmpty()
        LyricPrefs.activePackageName = this.activePackage

        // 切换播放器（或停止）时重置 userShowClock，避免状态残留
        updateAllControllers {
            if (providerInfo == null && userShowClock) {
                setUserShowClock(false)
                // 同步清除 clock 上可能残留的 onClockClicked 监听，
                // 防止播放器断开后 clock 僵尸监听长期残留
                val clockId = context.resources.getIdentifier("clock", "id", context.packageName)
                statusBarView.findViewById<View>(clockId)?.setOnClickListener(null)
            }
            resetViewForNewPlayer(this, providerInfo)
        }
    }

    /**
     * 播放状态变更回调（播放/暂停）。
     * @param isPlaying 播放状态
     */
    override fun onPlaybackStateChanged(isPlaying: Boolean) {
        if (this.isPlaying == isPlaying) return
        this.isPlaying = isPlaying
        updateAllControllers { lyricView.setPlaying(isPlaying) }
    }

    /**
     * 播放进度正常步进时的回调（通常每秒触发）。
     * @param position 当前逻辑时间戳
     */
    override fun onPositionChanged(position: Long) {
        this.currentLogicPosition = position
        // 进度更新极其频繁，直接 post 到 Handler
        mainHandler.post(frameUpdater)
    }

    /**
     * 用户手动调整进度（Seek）时的回调。
     * @param position 目标时间戳
     */
    override fun onSeekTo(position: Long) {
        this.currentLogicPosition = position
        updateAllControllers { lyricView.seekTo(position) }
    }

    /**
     * 接收到纯文本歌词时的回调（通常用于未匹配到 Lrc 的情况）。
     * @param text 歌词文本内容
     */
    override fun onReceiveText(text: String?) {
        updateAllControllers { lyricView.setText(text) }
    }

    /**
     * 翻译显示开关状态变更。
     * @param isDisplayTranslation 是否开启
     */
    override fun onDisplayTranslationChanged(isDisplayTranslation: Boolean) {
        this.isDisplayTranslation = isDisplayTranslation
        updateAllControllers { refreshTranslationVisibility(lyricView) }
    }

    /**
     * 罗马音显示开关状态变更。
     * @param isDisplayRoma 是否开启
     */
    override fun onDisplayRomaChanged(isDisplayRoma: Boolean) {
        this.isDisplayRoma = isDisplayRoma
        updateAllControllers { lyricView.updateDisplayTranslation(displayRoma = isDisplayRoma) }
    }

    /**
     * 应用全局配置更新（如字体颜色、阴影等样式变更）。
     * @param style 新的歌词样式配置
     */
    fun applyConfigurationUpdate(style: LyricStyle) {
        updateAllControllers { updateLyricStyle(style) }
        LyricDataHub.reprocessCurrentSong()
    }

    /**
     * 针对新播放器重置视图状态。
     * @param controller 具体的控制器实例
     * @param provider 播放源信息
     */
    private fun resetViewForNewPlayer(
        controller: StatusBarViewController,
        provider: ProviderInfo?
    ) {
        val view = controller.lyricView
        view.setSong(null)
        view.setPlaying(false)
        controller.updateLyricStyle(LyricPrefs.getLyricStyle())
        view.updateVisibility()

        view.logoView.apply {
            val pkg = provider?.playerPackageName.orEmpty()
            this.activePackage = pkg
            val cover = if (pkg.isBlank()) null else NotificationCoverHelper.getCoverFile(pkg)
            this.coverFile = cover
            controller.updateCoverThemeColors(cover)
            // Logo 加载涉及位图操作，通过 post 确保在 UI 线程执行
            post { this.providerLogo = provider?.logo }
        }
    }

    /**
     * 根据当前用户配置和样式决定翻译行的显示状态。
     * @param view 状态栏歌词视图
     */
    private fun refreshTranslationVisibility(view: StatusBarLyric) {
        val style = LyricPrefs.activePackageStyle
        val shouldShow = isDisplayTranslation &&
                !style.text.isDisableTranslation &&
                !style.text.isTranslationOnly
        view.updateDisplayTranslation(displayTranslation = shouldShow)
    }

    /**
     * 核心分发方法：在主线程遍历所有控制器并执行操作。
     * @param block 需要在每个控制器上执行的逻辑
     */
    private inline fun updateAllControllers(crossinline block: StatusBarViewController.() -> Unit) {
        StatusBarViewManager.forEachOnMainThread { controller ->
            runCatching {
                controller.block()
            }.onFailure { e ->
                YLog.error(TAG, "UI Update distribution error", e)
            }
        }
    }

    /**
     * Oplus (ColorOS) 胶囊状态变更监听。
     * 用于在系统胶囊出现时自动隐藏歌词，避免遮挡。
     * @param isShowing 胶囊是否正在显示
     */
    override fun onColorOsCapsuleVisibilityChanged(isShowing: Boolean) {
        updateAllControllers { lyricView.setOplusCapsuleVisibility(isShowing) }
    }

    /**
     * 屏幕方向变更回调（由 StatusBarViewResolver 的 onConfigurationChanged hook 驱动）。
     * 横屏时：完全退出歌词显示，隐藏歌词 View，恢复时钟和所有系统控件，重置 userShowClock。
     * 竖屏时：恢复正常状态，等待岛下次触发后重新进入歌词模式。
     */
    fun onOrientationChanged(landscape: Boolean) {
        YLog.debug(TAG, "onOrientationChanged: landscape=$landscape")
        if (landscape) {
            // 横屏：隐藏歌词，清除点击监听，完全交由系统接管
            // 借鉴1.0.32：setUserShowClock(true) → setUserHideLyric(true) → updateVisibility() 自然返回 false
            // 这样任何后续的 layout pass / updateVisibility 调用都不会把歌词再显示出来
            updateAllControllers {
                setUserShowClock(true)
                lyricView.setOnClickListener(null)
                val clockId = context.resources.getIdentifier("clock", "id", context.packageName)
                statusBarView.findViewById<View>(clockId)?.setOnClickListener(null)
                YLog.debug(TAG, "onOrientationChanged landscape: userShowClock set to true, lyric hidden")
            }
        } else {
            // 竖屏恢复：重置 userShowClock，让 updateVisibility() 根据播放状态自然决定
            updateAllControllers {
                setUserShowClock(false)
                // Fix Bug2（横屏→竖屏路径）：补刷翻译行可见性
                refreshTranslationVisibility(lyricView)
                // 横屏时清除了 lyricView 的点击监听，竖屏恢复时补回常驻监听
                lyricView.setOnClickListener { LyricViewController.onLyricClickedPublic(this) }
                YLog.debug(TAG, "onOrientationChanged portrait: userShowClock reset, lyric restored")
            }
        }
    }

    /**
     * 小米澎湃超级岛状态变更监听。
     * 岛出现时传入实际 Rect（屏幕绝对坐标），由 StatusBarLyric 动态计算歌词最大宽度；
     * 岛消失时传入 null，恢复正常宽度。
     * @param rect 超级岛的 Rect，null 表示岛已消失
     */
    override fun onMiuiIslandChanged(rect: Rect?) {
        val isShowing = rect != null
        updateAllControllers {
            isMiuiIslandShowing = isShowing
            if (isShowing) {
                // 岛出现：applyVisibilityRulesNow 会自动通过 islandRule 隐藏时钟（只要 userShowClock=false）
                // lyricView 的点击监听已在 onCreate 时常驻注册，无需每次重复设置
                YLog.debug("LyricViewController", "超级岛出现，隐藏时钟（由 islandRule 驱动）userShowClock=$userShowClock")
            } else {
                // 岛消失：若用户之前切到时钟，恢复歌词并补刷翻译行
                if (userShowClock) {
                    setUserShowClock(false)
                    // Fix Bug2（岛消失路径）：补刷翻译行防止恢复后翻译行残留在 GONE 状态
                    refreshTranslationVisibility(lyricView)
                }
                // Fix Bug3：清除时钟上可能残留的 onClockClicked 僵尸监听。
                // lyricView 的监听保持常驻，不在此清除，保证岛消失后用户仍可点击歌词切时钟。
                val clockId = context.resources.getIdentifier("clock", "id", context.packageName)
                statusBarView.findViewById<View>(clockId)?.setOnClickListener(null)
                YLog.debug("LyricViewController", "超级岛消失，恢复时钟")
            }
            lyricView.setMiuiIslandRect(rect)
            // 岛收起后，补执行被推迟的位置更新
            if (!isShowing && pendingLocationUpdate) {
                YLog.info("LyricViewController", "Flushing deferred location update after island hidden")
                updateLyricStyle(currentLyricStyle)
            }
        }
    }

    /**
     * 用户单击歌词区→切换到时钟。
     * 无论超级岛是否存在，歌词区始终可点击切换到时钟——岛存在时同时隐藏时钟，
     * 岛不存在时仅隐藏歌词、恢复时钟，给用户一致的交互体验。
     * internal 供 StatusBarViewController.onCreate 常驻注册时调用。
     */
    internal fun onLyricClickedPublic(controller: StatusBarViewController) {
        YLog.debug(TAG, "onLyricClicked → 切换到时钟")
        controller.setUserShowClock(true)
        // 给时钟注册点击，点击后切回歌词
        val clockId = controller.context.resources.getIdentifier("clock", "id", controller.context.packageName)
        controller.statusBarView.findViewById<View>(clockId)?.setOnClickListener {
            onClockClicked(controller)
        }
    }

    /**
     * 用户单击时钟→切回歌词。
     */
    private fun onClockClicked(controller: StatusBarViewController) {
        if (!controller.userShowClock) return
        YLog.debug(TAG, "onClockClicked → 切回歌词")
        controller.setUserShowClock(false)
        // Fix Bug2: 补刷翻译行，防止恢复后翻译行残留在 GONE 状态
        refreshTranslationVisibility(controller.lyricView)
        // 移除时钟点击监听；lyricView 的监听已常驻，无需重复注册
        val clockId = controller.context.resources.getIdentifier("clock", "id", controller.context.packageName)
        controller.statusBarView.findViewById<View>(clockId)?.setOnClickListener(null)
    }

    /**
     * 专辑封面更新回调。
     * @param packageName 触发更新的播放器包名
     * @param coverFile 封面文件对象
     */
    override fun onCoverUpdated(packageName: String, coverFile: File) {
        if (packageName != activePackage) return
        updateAllControllers {
            lyricView.logoView.apply {
                this.coverFile = coverFile
                (strategy as? SuperLogo.CoverStrategy)?.updateContent()
            }
            updateCoverThemeColors(coverFile)
        }
    }
}