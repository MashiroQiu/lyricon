/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.statusbarlyric

import android.animation.LayoutTransition
import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.view.ViewTreeObserver
import android.graphics.Rect
import android.os.Handler
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.contains
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import io.github.proify.android.extensions.dp
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.lyric.model.interfaces.IRichLyricLine
import io.github.proify.lyricon.lyric.style.BasicStyle
import io.github.proify.lyricon.lyric.style.LogoStyle
import io.github.proify.lyricon.lyric.style.LyricStyle
import io.github.proify.lyricon.lyric.view.LayoutTransitionX
import io.github.proify.lyricon.lyric.view.LyricPlayerView
import io.github.proify.lyricon.lyric.view.RichLyricLineView
import io.github.proify.lyricon.lyric.view.visibleIfChanged
import io.github.proify.lyricon.statusbarlyric.StatusBarLyric.LyricType.NONE
import io.github.proify.lyricon.statusbarlyric.StatusBarLyric.LyricType.SONG
import io.github.proify.lyricon.statusbarlyric.StatusBarLyric.LyricType.TEXT

@SuppressLint("ViewConstructor")
class StatusBarLyric(
    context: Context,
    initialStyle: LyricStyle,
    linkedTextView: TextView?
) : LinearLayout(context) {

    companion object {
        const val VIEW_TAG: String = "lyricon:lyric_view"
        private const val TAG = "StatusBarLyric"

    }

    val logoView: SuperLogo = SuperLogo(context).apply {
        this.linkedTextView = linkedTextView
    }

    val textView: SuperText = SuperText(context).apply {
        this.linkedTextView = linkedTextView
        eventListener = object : SuperText.EventListener {
            override fun enteringInterludeMode(duration: Long) {
                logoView.syncProgress(0, duration)
            }

            override fun exitInterludeMode() {
                logoView.clearProgress()
            }
        }
    }

    // --- 对外状态 ---

    var currentStatusColor: StatusColor = StatusColor()
        private set

    var isSleepMode: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            Log.d(TAG, "休眠模式：$value")
            if (value) {
                pendingSleepData = PendingData()
            } else {
                pendingSleepData?.let { seekTo(it.position) }
                pendingSleepData = null
            }
        }

    // --- 样式 / 播放状态 ---

    private var currentStyle: LyricStyle = initialStyle
    private var isPlaying: Boolean = false
    private var isOplusCapsuleShowing: Boolean = false
    /** 当前超级岛的屏幕绝对坐标 Rect，null 表示岛不存在 */
    private var miuiIslandRect: Rect? = null
    /**
     * 用户主动隐藏歌词（切换到时钟模式或横屏）。
     * true 时 updateVisibility() 强制返回 false，防止播放状态回调覆盖此决定。
     * 通过 setUserHideLyric() 设置，保证每次变更都触发 updateVisibility()。
     */
    private var userHideLyric: Boolean = false

    /**
     * 歌词容器所在父容器（时钟行 LinearLayout）的屏幕 X 坐标缓存。
     * 在 onAttachedToWindow 的 post{} 里读取，确保布局已稳定。
     * 初始值为 0，对应时钟行紧贴屏幕左边缘的标准情况，也是正确答案。
     */
    private var cachedContainerScreenLeft: Int = 0

    var onPlayingChanged: ((Boolean) -> Unit)? = null

    // 上一次 Logo gravity，用于避免重复重排
    private var lastLogoGravity: Int = -114

    // 休眠期间缓存的进度数据
    private var pendingSleepData: PendingData? = null

    // --- 歌词内容与超时状态 ---

    private var hasLyricContent: Boolean = false
    private var lyricTimedOut: Boolean = false
    private var currentLyric: String? = null

    // 主线程调度器
    private val mainHandler: Handler = Handler(context.mainLooper)

    // 当前生效的超时 Runnable
    private var lyricTimeoutTask: Runnable? = null

    // 跟随系统隐藏状态栏内容
    var isDisabledVisible = false
        set(value) {
            field = value
            updateVisibility()

            //修复在歌词播放结束时，来回切换StatusBarLyric可见性，导致颜色异常透明问题
            val old = currentStatusColor
            setStatusBarColor(StatusColor())
            setStatusBarColor(old)
        }

    // --- 系统 / 辅助组件 ---

    private val keyguardManager: KeyguardManager by lazy {
        context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
    }

    /**
     * 单次布局变更动画
     * 用于样式或尺寸突变时的过渡
     */
    private val singleLayoutTransition: LayoutTransition = LayoutTransitionX().apply {
        addTransitionListener(object : LayoutTransition.TransitionListener {

            override fun startTransition(
                transition: LayoutTransition?, container: ViewGroup?,
                view: View?, transitionType: Int
            ) = Unit

            override fun endTransition(
                transition: LayoutTransition?, container: ViewGroup?,
                view: View?, transitionType: Int
            ) {
                disableTransitionType(LayoutTransition.CHANGING)
                layoutTransition = null
            }
        })
    }

    // TextView 子视图结构变化监听，用于刷新可见性
    private val textHierarchyChangeListener = object : OnHierarchyChangeListener {
        override fun onChildViewAdded(parent: View?, child: View?) = updateVisibility()
        override fun onChildViewRemoved(parent: View?, child: View?) = updateVisibility()
    }

    // 歌词变化监听，用于重置超时逻辑及岛存在时的动态宽度收窄
    private val lyricCountChangeListener =
        object : LyricPlayerView.LyricCountChangeListener {

            override fun onLyricTextChanged(old: String, new: String) {
                currentLyric = new
                refreshLyricTimeoutState()
                // 岛存在时，歌词内容变化后触发容器宽度重算（幂等，只是重新设 maxWidth）。
                if (miuiIslandRect != null) {
                    post { updateWidthInternal(currentStyle) }
                }
            }

            override fun onLyricChanged(
                news: List<IRichLyricLine>,
                removes: List<IRichLyricLine>
            ) {
                currentLyric = news.lastOrNull()?.text
                refreshLyricTimeoutState()
                // 同上：逐字歌词行切换时也触发宽度重算
                if (miuiIslandRect != null) {
                    post { updateWidthInternal(currentStyle) }
                }
            }
        }

    init {
        tag = VIEW_TAG
        gravity = Gravity.CENTER_VERTICAL
        visibility = GONE
        layoutTransition = null

        addView(
            textView,
            LayoutParams(0, LayoutParams.WRAP_CONTENT).apply {
                weight = 1f
            }
        )

        updateLogoLocation()
        applyInitialStyle(initialStyle)

        textView.setOnHierarchyChangeListener(textHierarchyChangeListener)
        textView.lyricCountChangeListeners += lyricCountChangeListener
    }

    private val cameraLayoutListener = ViewTreeObserver.OnGlobalLayoutListener {
        if (usesCameraWidth() && miuiIslandRect == null && !isOplusCapsuleShowing) {
            val target = calculateContainerWidth(currentStyle.basicStyle)
            if (layoutParams?.width != target) updateWidthInternal(currentStyle)
        }
    }

    private fun usesCameraWidth(): Boolean {
        val maker = Build.MANUFACTURER.lowercase()
        val brand = Build.BRAND.lowercase()
        return (maker == "xiaomi" || brand in setOf("xiaomi", "redmi", "poco")) &&
            resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnGlobalLayoutListener(cameraLayoutListener)
        // 延迟一帧读取，确保 View 树第一次 layout 已完成，坐标稳定
        post {
            val loc = IntArray(2)
            (parent as? View)?.getLocationOnScreen(loc)
            cachedContainerScreenLeft = loc[0]
            Log.d(TAG, "onAttachedToWindow: cachedContainerScreenLeft=$cachedContainerScreenLeft")
            updateWidthInternal(currentStyle)
        }
    }

    override fun onDetachedFromWindow() {
        if (viewTreeObserver.isAlive) viewTreeObserver.removeOnGlobalLayoutListener(cameraLayoutListener)
        super.onDetachedFromWindow()
    }

    // --- 公开 API ---

    fun updateStyle(style: LyricStyle) {
        triggerSingleTransition()
        currentStyle = style
        logoView.applyStyle(style)
        updateLogoLocation()
        textView.applyStyle(style)
        updateLayoutConfig(style)

        refreshLyricTimeoutState()
        requestLayout()
    }

    fun setStatusBarColor(color: StatusColor) {
        currentStatusColor = color
        logoView.setStatusBarColor(color)
        textView.setStatusBarColor(color)
    }

    private var lastPlaying: Boolean? = null
    private var lastSong: Song? = null
    private var lastText: String? = null
    private var lyricType = NONE

    fun setPlaying(playing: Boolean) {
        if (lastPlaying == playing) return
        Log.d(TAG, "setPlaying: $playing")

        lastPlaying = playing
        isPlaying = playing
        onPlayingChanged?.invoke(playing)

        if (!playing) {
            textView.reset()
        } else {
            when (lyricType) {
                NONE -> Unit
                SONG -> setSong(lastSong)
                TEXT -> setText(lastText)
            }
        }

        refreshLyricTimeoutState()
        updateVisibility()
    }

    fun isHideOnLockScreen() =
        currentStyle.basicStyle.hideOnLockScreen && keyguardManager.isKeyguardLocked

    fun updateVisibility() {
        // 用户主动切换到时钟模式时，强制不显示歌词，忽略播放状态
        val shouldShow = !userHideLyric
                && isPlaying
                && !isHideOnLockScreen()
                && textView.shouldShow()
                && !lyricTimedOut
                && !isDisabledVisible

        visibleIfChanged = shouldShow

        // Fix Bug1: 同步 logoView 的可见性与父容器保持一致，防止 SuperLogo 的自治逻辑在
        // 父容器 GONE 后被外部事件独立唤醒（如样式更新、进度回调），导致"只剩 Logo"的异常现象。
        // 父容器隐藏时强制将 logoView 一并隐藏；父容器恢复时让 logoView 按自身条件重新评估。
        if (!shouldShow) {
            logoView.visibleIfChanged = false
        } else {
            logoView.updateVisibilityState()
        }

        Log.d(TAG, "updateVisibility: $shouldShow")
        Log.d(TAG, "textVisibility: ${textView.isVisible}")
    }

    fun setSong(song: Song?) {
        lyricType = SONG
        lastSong = song
        textView.song = song
        hasLyricContent = !song?.lyrics.isNullOrEmpty()
        refreshLyricTimeoutState()
    }

    fun setText(text: String?) {
        Log.d(TAG, "setText: $text")
        lyricType = TEXT
        lastText = text

        textView.text = text
        hasLyricContent = !text.isNullOrBlank()
        refreshLyricTimeoutState()
    }

    fun seekTo(position: Long) {
        if (isSleepMode) {
            pendingSleepData?.position = position
            return
        }

        textView.seekTo(position)
        refreshLyricTimeoutState()
    }

    fun setPosition(position: Long) {
        if (isSleepMode) {
            pendingSleepData?.position = position
            return
        }

        textView.setPosition(position)
    }

    fun updateDisplayTranslation(
        displayTranslation: Boolean = textView.isDisplayTranslation,
        displayRoma: Boolean = textView.isDisplayRoma
    ) {
        textView.updateDisplayTranslation(displayTranslation, displayRoma)
    }

    fun setOplusCapsuleVisibility(visible: Boolean) {
        isOplusCapsuleShowing = visible
        triggerSingleTransition()
        updateWidthInternal(currentStyle)
        logoView.oplusCapsuleShowing = visible
    }

    /**
     * 设置用户主动隐藏歌词的状态（切换到时钟模式或横屏时调用）。
     * 对应 1.0.32 fork 的 setUserHideLyric()，逻辑完全一致。
     */
    fun setUserHideLyric(hide: Boolean) {
        if (userHideLyric == hide) return
        userHideLyric = hide
        updateVisibility()
    }

    fun setMiuiIslandRect(rect: Rect?) {
        miuiIslandRect = rect
        triggerSingleTransition()
        updateWidthInternal(currentStyle)
    }

    // --- 内部逻辑 ---

    private fun applyInitialStyle(style: LyricStyle) {
        currentStyle = style
        logoView.applyStyle(style)
        textView.applyStyle(style)
        updateLayoutConfig(style)
    }

    private fun updateLogoLocation() {
        val logoStyle = currentStyle.packageStyle.logo
        val gravity = logoStyle.gravity

        if (gravity == lastLogoGravity) return; lastLogoGravity = gravity

        if (contains(logoView)) removeView(logoView)
        val textIndex = indexOfChild(textView).coerceAtLeast(0)

        when (gravity) {
            LogoStyle.GRAVITY_START -> addView(logoView, textIndex)
            LogoStyle.GRAVITY_END -> addView(logoView, textIndex + 1)
            else -> addView(logoView, textIndex)
        }
    }

    private fun updateLayoutConfig(style: LyricStyle) {
        val basic = style.basicStyle
        val margins = basic.margins
        val paddings = basic.paddings

        ensureMarginLayoutParams().apply {
            width = calculateContainerWidth(basic)
            leftMargin = margins.left.dp
            topMargin = margins.top.dp
            rightMargin = margins.right.dp
            bottomMargin = margins.bottom.dp
        }
        updateTextViewWidthMode(basic)

        updatePadding(
            paddings.left.dp,
            paddings.top.dp,
            paddings.right.dp,
            paddings.bottom.dp
        )
    }

    private fun updateWidthInternal(style: LyricStyle) {
        val width = calculateContainerWidth(style.basicStyle)
        ensureMarginLayoutParams().width = width
        // 超级岛 / 胶囊存在时，强制 textView 用 weight=1 填满容器，
        // 防止 dynamicWidthEnabled 场景下 WRAP_CONTENT 的 textView 溢出容器边界，
        // 导致歌词文本戳入岛区域或把时钟顶出来。
        // 岛消失后 updateTextViewWidthMode 会被 updateLayoutConfig 还原。
        if (miuiIslandRect != null || isOplusCapsuleShowing || usesCameraWidth()) {
            val lp = (textView.layoutParams as? LayoutParams)
                ?: LayoutParams(0, LayoutParams.WRAP_CONTENT)
            lp.width = 0
            lp.weight = 1f
            textView.layoutParams = lp
        }
        if (miuiIslandRect == null && !isOplusCapsuleShowing && !usesCameraWidth()) {
            updateTextViewWidthMode(style.basicStyle)
        }
        requestLayout()
        Log.d(TAG, "updateWidthInternal: $width")
    }

    private fun calculateContainerWidth(basicStyle: BasicStyle): Int {
        // ColorOS 胶囊：走百分比预设限宽
        if (isOplusCapsuleShowing) {
            return calculateTargetWidth(basicStyle).dp
        }
        // 小米超级岛：用岛的实际 islandLeft 动态计算，精确避开岛区域
        miuiIslandRect?.let { rect ->
            return calculateIslandAwareWidth(rect)
        }
        if (usesCameraWidth()) return calculateCameraWidth()
        return if (basicStyle.dynamicWidthEnabled) {
            LayoutParams.WRAP_CONTENT
        } else {
            calculateTargetWidth(basicStyle).dp
        }
    }

    /**
     * 根据岛的实际屏幕坐标动态计算歌词容器宽度。
     *
     * 有岛时：容器固定为 maxWidth（岛左边缘到容器左边缘），不做收窄预测。
     * 宽度是否溢出由 LyricLineView.isOverflow() 每帧实时判断，参考版同款逻辑。
     * 这样彻底避免了用旧歌词宽度预先收窄容器，导致新歌词误判溢出的竞态问题。
     */
    private fun calculateIslandAwareWidth(rect: Rect): Int {
        val islandLeft = rect.left
        val maxWidth = (islandLeft - cachedContainerScreenLeft + 2).coerceAtLeast(1)
        Log.d(TAG, "calculateIslandAwareWidth: islandLeft=$islandLeft cachedContainerScreenLeft=$cachedContainerScreenLeft maxWidth=$maxWidth")
        return maxWidth
    }

    /** Fill the actual space from the lyric anchor to the left edge of the camera cutout. */
    private fun calculateCameraWidth(): Int {
        val root = rootView
        val rootScreen = IntArray(2)
        val rootWindow = IntArray(2)
        val lyricScreen = IntArray(2)
        root.getLocationOnScreen(rootScreen)
        root.getLocationInWindow(rootWindow)
        getLocationOnScreen(lyricScreen)
        val windowOffset = rootScreen[0] - rootWindow[0]
        val cutout = rootWindowInsets?.displayCutout?.boundingRects
            ?.filter { !it.isEmpty && it.top <= root.height && it.left > root.width / 4 }
            ?.minByOrNull { it.left }
        val cameraLeft = cutout?.left?.plus(windowOffset)
            ?: (rootScreen[0] + (root.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels) / 2 - 12.dp)
        val lyricLeft = if (isLaidOut) lyricScreen[0] else cachedContainerScreenLeft + currentStyle.basicStyle.margins.left.dp
        return CameraWidth.available(cameraLeft, lyricLeft, 4.dp + currentStyle.basicStyle.margins.right.dp + 3)
    }

    private fun updateTextViewWidthMode(basicStyle: BasicStyle) {
        val lp = (textView.layoutParams as? LayoutParams)
            ?: LayoutParams(0, LayoutParams.WRAP_CONTENT)
        if (basicStyle.dynamicWidthEnabled && !usesCameraWidth() && miuiIslandRect == null && !isOplusCapsuleShowing) {
            lp.width = LayoutParams.WRAP_CONTENT
            lp.weight = 0f
        } else {
            lp.width = 0
            lp.weight = 1f
        }
        textView.layoutParams = lp
    }

    private fun calculateTargetWidth(basicStyle: BasicStyle) = when {
        isOplusCapsuleShowing -> basicStyle.widthInColorOSCapsuleMode
        else                  -> basicStyle.width
    }

    private fun ensureMarginLayoutParams(): MarginLayoutParams {
        val lp = layoutParams as? MarginLayoutParams
            ?: MarginLayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.MATCH_PARENT
            )
        if (layoutParams == null) layoutParams = lp
        return lp
    }

    private fun triggerSingleTransition() {
        singleLayoutTransition.enableTransitionType(LayoutTransition.CHANGING)
        layoutTransition = singleLayoutTransition
    }

    private fun refreshLyricTimeoutState() {
        resetLyricTimeout()

        val basicStyleConfig = currentStyle.basicStyle

        val noLyricTimeoutSec = basicStyleConfig.noLyricHideTimeout
        val noUpdateTimeoutSec = basicStyleConfig.noUpdateHideTimeout
        val keywordTimeoutSec = basicStyleConfig.keywordHideTimeout

        val shouldHideWhenNoLyric = noLyricTimeoutSec > 0
        val shouldHideWhenNoUpdate = noUpdateTimeoutSec > 0
        val shouldHideWhenKeywordMatched = keywordTimeoutSec > 0

        val timeoutSec = when {
            shouldHideWhenNoLyric && !hasLyricContent -> noLyricTimeoutSec

            hasLyricContent -> {
                val keywordMatched =
                    shouldHideWhenKeywordMatched && !currentLyric.isNullOrEmpty() &&
                            basicStyleConfig.keywordsHidePattern.orEmpty()
                                .any { it.containsMatchIn(currentLyric.orEmpty()) }

                when {
                    keywordMatched -> keywordTimeoutSec
                    shouldHideWhenNoUpdate -> noUpdateTimeoutSec
                    else -> -1
                }
            }

            else -> -1
        }

        if (timeoutSec > 0) {
            val timeoutRunnable = Runnable {
                lyricTimedOut = true
                updateVisibility()
            }
            lyricTimeoutTask = timeoutRunnable
            mainHandler.postDelayed(timeoutRunnable, timeoutSec * 1000L)
        }

        updateVisibility()
    }

    private fun resetLyricTimeout() {
        lyricTimedOut = false
        lyricTimeoutTask?.let { mainHandler.removeCallbacks(it) }
        lyricTimeoutTask = null
    }

    private class PendingData(var position: Long = 0)

    private enum class LyricType {
        NONE, SONG, TEXT
    }
}