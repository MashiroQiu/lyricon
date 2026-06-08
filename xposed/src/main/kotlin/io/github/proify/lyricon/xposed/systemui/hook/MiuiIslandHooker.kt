/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.xposed.systemui.hook

import android.graphics.Rect
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import io.github.proify.lyricon.xposed.logger.YLog
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 小米澎湃超级岛感知器
 *
 * 监听 IslandMonitor 的 onIslandSizeChanged 回调：
 *   - Rect 非全零 → 岛出现，将实际 Rect 传递给监听器（包含 islandLeft 坐标）
 *   - Rect 全零   → 岛消失，传递 null
 *
 * 与 [OplusCapsuleHooker] 保持一致的接口风格，供 LyricViewController 并联注册。
 * 感知层不做任何宽度决策，将原始 Rect 原样上报，由消费方动态计算限宽。
 */
object MiuiIslandHooker {

    private const val TAG = "MiuiIslandHooker"

    /**
     * Hook 目标：IslandMonitor 的匿名 stateListener 实现类。
     * 类名由 MIUI/HyperOS SystemUI 反编译确认。
     */
    private const val STATE_LISTENER_CLASS =
        "com.android.systemui.statusbar.IslandMonitor\$RealContainerIslandMonitor\$stateListener\$1"

    private val listeners = CopyOnWriteArrayList<IslandStateChangeListener>()

    /** 当前岛的 Rect（屏幕绝对坐标），null 表示岛不存在 */
    var currentRect: Rect? = null
        private set

    val isShowing: Boolean get() = currentRect != null

    /**
     * 当前是否处于横屏状态。
     * 横屏时所有岛回调静默丢弃，不分发给监听器。
     */
    @Volatile
    var isLandscape: Boolean = false

    private var unhook: XC_MethodHook.Unhook? = null

    /**
     * 检测当前 ClassLoader 是否含有小米超级岛目标类。
     * 在非小米机型上静默返回 false，initialize() 直接跳过。
     */
    fun isSupported(classLoader: ClassLoader): Boolean = try {
        classLoader.loadClass(STATE_LISTENER_CLASS)
        true
    } catch (_: Exception) {
        false
    }

    /**
     * 初始化 Hook。
     * 若机型不支持超级岛则静默退出，不影响其他机型。
     */
    fun initialize(classLoader: ClassLoader) {
        unhook?.unhook()
        unhook = null

        if (!isSupported(classLoader)) {
            YLog.info(TAG, "超级岛目标类不存在，跳过初始化（非小米机型）")
            return
        }

        try {
            unhook = XposedHelpers.findAndHookMethod(
                classLoader.loadClass(STATE_LISTENER_CLASS),
                "onIslandSizeChanged",
                Rect::class.java,
                Boolean::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val rect = param.args[0] as? Rect ?: return
                        val isEmpty = rect.left == 0 && rect.right == 0
                                && rect.top == 0 && rect.bottom == 0
                        onIslandStateChanged(if (isEmpty) null else rect)
                    }
                }
            )
            YLog.info(TAG, "Hook 注册成功 ✅")
        } catch (t: Throwable) {
            YLog.error(TAG, "Hook 注册失败", t)
        }
    }

    fun registerListener(listener: IslandStateChangeListener): Boolean =
        listeners.add(listener)

    fun unregisterListener(listener: IslandStateChangeListener): Boolean =
        listeners.remove(listener)

    private fun onIslandStateChanged(rect: Rect?) {
        // 横屏时静默丢弃，不影响系统状态栏行为
        if (isLandscape) {
            YLog.info(TAG, "横屏状态，忽略岛回调 rect=$rect")
            return
        }
        // 存内容副本，防止 SystemUI 在回调结束后修改原 Rect 对象导致静默数据污染
        val newRect = rect?.let { Rect(it) }
        // 内容相等则不重复分发（包含 null==null 的情况）
        if (newRect == currentRect) return
        currentRect = newRect
        val showing = newRect != null
        YLog.debug(TAG, "超级岛状态变更: isShowing=$showing${if (newRect != null) " rect=$newRect" else ""}")
        listeners.forEach { it.onMiuiIslandChanged(newRect) }
    }

    /**
     * 横竖屏切换时调用。
     * 横屏：立即通知所有监听器岛已消失（让歌词/时钟恢复正常），并清除 currentRect。
     * 竖屏：恢复正常分发，等待下一次真实的岛回调。
     */
    fun onOrientationChanged(landscape: Boolean) {
        if (isLandscape == landscape) return
        isLandscape = landscape
        YLog.info(TAG, "屏幕方向变更: landscape=$landscape")
        if (landscape) {
            // 横屏：强制通知岛消失，让所有监听器恢复默认状态（时钟显示，歌词可见）
            currentRect = null
            listeners.forEach { it.onMiuiIslandChanged(null) }
        }
        // 竖屏恢复：什么都不做，等岛下次真实触发
    }

    interface IslandStateChangeListener {
        fun onMiuiIslandChanged(rect: Rect?)
    }
}
