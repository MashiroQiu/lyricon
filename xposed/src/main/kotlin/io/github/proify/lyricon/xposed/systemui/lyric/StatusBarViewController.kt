/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.xposed.systemui.lyric

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.TextView
import androidx.core.graphics.toColorInt
import androidx.core.view.doOnAttach
import androidx.core.view.isVisible
import io.github.proify.android.extensions.dp
import io.github.proify.android.extensions.setColorAlpha
import io.github.proify.android.extensions.toBitmap
import io.github.proify.lyricon.colorextractor.palette.ColorExtractor
import io.github.proify.lyricon.colorextractor.palette.ColorPaletteResult
import io.github.proify.lyricon.common.util.ResourceMapper
import io.github.proify.lyricon.common.util.ScreenStateMonitor
import io.github.proify.lyricon.lyric.style.BasicStyle
import io.github.proify.lyricon.lyric.style.LyricStyle
import io.github.proify.lyricon.lyric.style.VisibilityRule
import io.github.proify.lyricon.statusbarlyric.StatusBarLyric
import io.github.proify.lyricon.xposed.logger.YLog
import io.github.proify.lyricon.xposed.systemui.hook.ClockColorMonitor
import io.github.proify.lyricon.xposed.systemui.util.OnColorChangeListener
import io.github.proify.lyricon.xposed.systemui.util.ViewVisibilityController
import java.io.File

/**
 * 状态栏歌词视图控制器：负责歌词视图的注入、位置锚定及显隐逻辑
 */
@SuppressLint("DiscouragedApi")
class StatusBarViewController(
    val statusBarView: ViewGroup,
    var currentLyricStyle: LyricStyle
) : ScreenStateMonitor.ScreenStateListener {
    companion object {
        const val TAG = "StatusBarViewController"
    }

    val context: Context = statusBarView.context.applicationContext
    val visibilityController = ViewVisibilityController(statusBarView)
    val lyricView: StatusBarLyric by lazy { createLyricView(currentLyricStyle) }

    private val clockId: Int by lazy { ResourceMapper.getIdByName(context, "clock") }
    private var lastAnchor = ""
    private var lastInsertionOrder = -1
    private var internalRemoveLyricViewFlag = false
    /** 超级岛展开期间屏蔽重注入，防止时钟被顶出 */
    var isMiuiIslandShowing = false
    /**
     * 用户主动切换到时钟模式（双击/单击歌词后）。
     * 对应 1.0.32 的 userShowClock，控制 applyVisibilityRulesNow 是否应用时钟隐藏规则。
     */
    var userShowClock = false
        private set
    /** 岛展开期间有位置更新被推迟，岛收起后需补执行 */
    internal var pendingLocationUpdate = false
    private var lastHighlightView: View? = null
    private var colorMonitorView: View? = null
    private var coverColorPaletteResult: ColorPaletteResult? = null
    private var systemStatusBarColor: SystemStatusBarColor? = null

    private val onGlobalLayoutListener = ViewTreeObserver.OnGlobalLayoutListener {
        applyVisibilityRulesNow()
    }

    // --- 生命周期与初始化 ---
    fun onCreate() {
        statusBarView.addOnAttachStateChangeListener(statusBarAttachListener)
        statusBarView.viewTreeObserver.addOnGlobalLayoutListener(onGlobalLayoutListener)
        lyricView.addOnAttachStateChangeListener(lyricAttachListener)
        ScreenStateMonitor.addListener(this)
        lyricView.onPlayingChanged = { _ -> }

        val onColorChangeListener = object : OnColorChangeListener {

            private var colorFingerprint: String? = null
            override fun onColorChanged(color: Int, darkIntensity: Float) {
                val colorFingerprint = color.toString() + darkIntensity
                if (colorFingerprint == this.colorFingerprint) return
                this.colorFingerprint = colorFingerprint

                updateStatusColor(SystemStatusBarColor(color, darkIntensity))
            }
        }

        colorMonitorView = getClockView()?.also {
            ClockColorMonitor.setListener(it, onColorChangeListener)
        }

        statusBarView.doOnAttach { checkLyricViewExists() }
        // 歌词区点击监听常驻注册：无论超级岛是否存在，用户随时可点击歌词切换到时钟
        lyricView.setOnClickListener { LyricViewController.onLyricClickedPublic(this) }
        YLog.info(tag = TAG, "Lyric view created for $statusBarView")
    }

    fun onDestroy() {
        statusBarView.removeOnAttachStateChangeListener(statusBarAttachListener)
        statusBarView.viewTreeObserver.removeOnGlobalLayoutListener(onGlobalLayoutListener)
        lyricView.removeOnAttachStateChangeListener(lyricAttachListener)
        ScreenStateMonitor.removeListener(this)
        lyricView.onPlayingChanged = null
        colorMonitorView?.let { ClockColorMonitor.setListener(it, null) }
        YLog.info(tag = TAG, "Lyric view destroyed for $statusBarView")
    }

    // --- 核心业务逻辑 ---

    /**
     * 更新状态栏颜色，内部决定最终颜色
     */
    private fun updateStatusColor(systemStatusBarColor: SystemStatusBarColor) {
        this.systemStatusBarColor = systemStatusBarColor

        val textStyle = currentLyricStyle.packageStyle.text
        lyricView.apply {
            currentStatusColor.apply {
                this.darkIntensity = systemStatusBarColor.darkIntensity

                val coverColorPaletteResult = coverColorPaletteResult
                when {
                    coverColorPaletteResult != null
                            && textStyle.enableExtractCoverTextColor
                            && textStyle.enableExtractCoverTextGradient -> {
                        val themeColors = coverColorPaletteResult
                            .let { if (isLightMode) it.lightModeColors else it.darkModeColors }

                        val gradient = themeColors.swatches

                        this.color = gradient
                        this.translucentColor = gradient.map {
                            it.setColorAlpha(0.75f)
                        }.toIntArray()
                    }

                    coverColorPaletteResult != null
                            && textStyle.enableExtractCoverTextColor -> {
                        val themeColors = coverColorPaletteResult
                            .let { if (isLightMode) it.lightModeColors else it.darkModeColors }

                        val primary = themeColors.primary

                        this.color = intArrayOf(primary)
                        this.translucentColor = intArrayOf(primary.setColorAlpha(0.75f))
                    }

                    else -> {
                        this.color = intArrayOf(systemStatusBarColor.color)
                        this.translucentColor =
                            intArrayOf(systemStatusBarColor.color.setColorAlpha(0.5f))
                    }
                }
            }
            setStatusBarColor(currentStatusColor)
        }
    }

    /**
     * 更新歌词样式及位置，若锚点或顺序变化则重新注入视图
     */
    fun updateLyricStyle(lyricStyle: LyricStyle) {
        this.currentLyricStyle = lyricStyle
        val basicStyle = lyricStyle.basicStyle

        val needUpdateLocation = lastAnchor != basicStyle.anchor
                || lastInsertionOrder != basicStyle.insertionOrder
                || !lyricView.isAttachedToWindow

        if (needUpdateLocation) {
            YLog.info(
                TAG,
                "Lyric location changed: ${basicStyle.anchor}, order ${basicStyle.insertionOrder}"
            )
            updateLocation(basicStyle)
        }
        lyricView.updateStyle(lyricStyle)

        systemStatusBarColor?.let { updateStatusColor(it) }
    }

    fun updateCoverThemeColors(coverFile: File?) {
        coverColorPaletteResult = null
        try {
            val bitmap = coverFile?.toBitmap() ?: return
            ColorExtractor.extractAsync(bitmap) {
                coverColorPaletteResult = it
                systemStatusBarColor?.let { updateStatusColor(it) }
                bitmap.recycle()
            }
        } catch (e: Exception) {
            YLog.error(TAG, "Failed to extract cover theme colors", e)
        }
    }

    /**
     * 处理视图注入逻辑：根据 BasicStyle 寻找锚点并插入歌词视图
     */
    private fun updateLocation(baseStyle: BasicStyle) {
        // 超级岛展开时父容器有额外 View，anchorIndex 会偏移导致时钟被顶出
        // 推迟到岛收起后再执行
        if (isMiuiIslandShowing) {
            YLog.info(TAG, "updateLocation deferred: MiuiIsland is showing")
            pendingLocationUpdate = true
            return
        }
        pendingLocationUpdate = false

        val anchor = baseStyle.anchor
        val anchorId = context.resources.getIdentifier(anchor, "id", context.packageName)
        val anchorView = statusBarView.findViewById<View>(anchorId) ?: return run {
            YLog.error(TAG, "Lyric anchor view $anchor not found")
        }

        val anchorParent = anchorView.parent as? ViewGroup ?: return run {
            YLog.error(TAG, "Lyric anchor parent not found")
        }

        // 标记内部移除，避免触发冗余的 detach 逻辑
        internalRemoveLyricViewFlag = true

        (lyricView.parent as? ViewGroup)?.removeView(lyricView)

        val anchorIndex = anchorParent.indexOfChild(anchorView)
        val lp = lyricView.layoutParams ?: ViewGroup.LayoutParams(
            if (baseStyle.dynamicWidthEnabled) ViewGroup.LayoutParams.WRAP_CONTENT else baseStyle.width.dp,
            ViewGroup.LayoutParams.MATCH_PARENT
        )

        // 执行插入：在前或在后
        val targetIndex =
            if (baseStyle.insertionOrder == BasicStyle.INSERTION_ORDER_AFTER) anchorIndex + 1 else anchorIndex
        anchorParent.addView(lyricView, targetIndex, lp)

        lyricView.updateVisibility()
        lastAnchor = anchor
        lastInsertionOrder = baseStyle.insertionOrder
        internalRemoveLyricViewFlag = false

        YLog.info(TAG, "Lyric injected: anchor $anchor, index $targetIndex")
    }

    fun checkLyricViewExists() {
        if (lyricView.isAttachedToWindow) return
        // 岛展开时父容器布局不稳定，推迟到岛收起后再重注入
        if (isMiuiIslandShowing) {
            YLog.info(TAG, "checkLyricViewExists deferred: MiuiIsland is showing")
            pendingLocationUpdate = true
            return
        }
        lastAnchor = ""
        lastInsertionOrder = -1
        updateLyricStyle(currentLyricStyle)
    }

    // --- 辅助方法 ---

    private fun getClockView(): View? = statusBarView.findViewById(clockId)

    /**
     * 设置用户主动切换到时钟的状态，对应 1.0.32 的 setUserShowClock()。
     * show=true：隐藏歌词，恢复时钟；show=false：恢复歌词，重新隐藏时钟（若岛在）。
     */
    fun setUserShowClock(show: Boolean) {
        if (userShowClock == show) return
        userShowClock = show
        lyricView.setUserHideLyric(show)
        applyVisibilityRulesNow()
    }

    private fun applyVisibilityRulesNow() {
        fun computeShouldApplyPlayingRules(): Boolean {
            return LyricViewController.isPlaying && when {
                lyricView.isDisabledVisible -> !lyricView.isHideOnLockScreen()
                lyricView.isVisible -> true
                else -> false
            }
        }

        // 1.0.32 做法：userShowClock=true 时直接不应用任何规则（时钟恢复正常）
        if (userShowClock) {
            visibilityController.applyVisibilityRules(
                rules = currentLyricStyle.basicStyle.visibilityRules,
                isPlaying = false
            )
            return
        }

        val isPlaying = computeShouldApplyPlayingRules()

        // 超级岛存在 + 歌词可见时，自动追加 clock=HIDE_WHEN_PLAYING 规则，
        // 每次 layout 触发都重新隐藏时钟，防止系统 layout pass 把时钟恢复。
        // 关键修复：必须同时检查 lyricView.isVisible，
        // 歌词因超时/无歌词被 GONE 后不能继续强制隐藏时钟（Bug2修复）。
        val islandRule = if (isMiuiIslandShowing && lyricView.isVisible) {
            listOf(VisibilityRule("clock", VisibilityRule.MODE_HIDE_WHEN_PLAYING))
        } else {
            emptyList()
        }

        // 岛规则优先于用户规则
        val mergedRules = currentLyricStyle.basicStyle.visibilityRules
            .filter { rule -> islandRule.none { it.id == rule.id } } + islandRule

        visibilityController.applyVisibilityRules(
            rules = mergedRules,
            isPlaying = if (islandRule.isNotEmpty()) true else isPlaying
        )
    }

    private fun createLyricView(style: LyricStyle) =
        StatusBarLyric(context, style, getClockView() as? TextView)

    fun highlightView(idName: String?) {
        YLog.info(TAG, "Highlighting view id:$idName")

        lastHighlightView?.background = null
        if (idName.isNullOrBlank()) return

        val id = ResourceMapper.getIdByName(context, idName)
        statusBarView.findViewById<View>(id)?.let { view ->
            view.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor("#FF3582FF".toColorInt())
                cornerRadius = 20.dp.toFloat()
            }
            lastHighlightView = view
        } ?: YLog.error(TAG, "Highlight target $idName not found")
    }

    private val lyricAttachListener = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) {
            YLog.info(TAG, "LyricView attached")
        }

        override fun onViewDetachedFromWindow(v: View) {
            YLog.info(TAG, "LyricView detached")
            if (!internalRemoveLyricViewFlag) {
                checkLyricViewExists()
            } else {
                YLog.info(TAG, "LyricView detached by internal flag")
            }
        }
    }

    private val statusBarAttachListener = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) {}
        override fun onViewDetachedFromWindow(v: View) {}
    }

    override fun onScreenOn() {
        lyricView.updateVisibility()
        lyricView.isSleepMode = false
    }

    override fun onScreenOff() {
        lyricView.updateVisibility()
        lyricView.isSleepMode = true
    }

    override fun onScreenUnlocked() {
        lyricView.updateVisibility()
        lyricView.isSleepMode = false
    }

    fun onDisableStateChanged(shouldHide: Boolean) {
        lyricView.isDisabledVisible = shouldHide
    }

    override fun equals(other: Any?): Boolean =
        (this === other) || (other is StatusBarViewController && statusBarView == other.statusBarView)

    override fun hashCode(): Int = 31 * 17 + statusBarView.hashCode()

    data class SystemStatusBarColor(val color: Int, val darkIntensity: Float)
}