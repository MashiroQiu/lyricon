/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.xposed

import android.app.Application
import android.app.AppComponentFactory
import android.content.pm.ApplicationInfo
import android.os.Looper
import android.os.Parcel
import android.graphics.Rect
import android.view.ViewGroup
import androidx.annotation.Keep
import io.github.libxposed.api.XposedInterface
import io.github.proify.android.extensions.json
import io.github.proify.lyricon.common.util.ReloadableBinders
import io.github.proify.lyricon.common.util.ReloadJobs
import io.github.proify.lyricon.xposed.hook.ReloadResources
import io.github.proify.lyricon.xposed.systemui.lyric.StatusBarViewManager
import io.github.proify.lyricon.xposed.systemui.lyric.LyricViewController
import io.github.proify.lyricon.xposed.systemui.lyric.LyricDataHub
import io.github.proify.lyricon.xposed.systemui.hook.MiuiIslandHooker
import io.github.proify.lyricon.xposed.systemui.ai.translate.AiTranslator
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.subscriber.ProviderInfo
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import io.github.proify.lyricon.common.PackageNames
import io.github.proify.lyricon.xposed.hook.GeneralHooker
import io.github.proify.lyricon.xposed.logger.YLog
import io.github.proify.lyricon.xposed.systemui.SystemUIHooker

/**
 * ooooo        oooooo   oooo ooooooooo.   ooooo   .oooooo.     .oooooo.   ooooo      ooo
 * `888'         `888.   .8'  `888   `Y88. `888'  d8P'  `Y8b   d8P'  `Y8b  `888b.     `8'
 *  888           `888. .8'    888   .d88'  888  888          888      888  8 `88b.    8
 *  888            `888.8'     888ooo88P'   888  888          888      888  8   `88b.  8
 *  888             `888'      888`88b.     888  888          888      888  8     `88b.8
 *  888       o      888       888  `88b.   888  `88b    ooo  `88b    d88'  8       `888
 * o888ooooood8     o888o     o888o  o888o o888o  `Y8bood8P'   `Y8bood8P'  o8o        `8
 *
 *  @author Tomakino
 *  @date 2026/05/03
 */
@Keep
class ModuleEntry : XposedModule() {
    private val readyPackages = mutableMapOf<String, XposedModuleInterface.PackageReadyParam>()

    companion object {
        private const val TAG = "ModuleEntry"

        private val scopes = listOf(
            PackageNames.APPLICATION,
            PackageNames.SYSTEM_UI,
        )

        lateinit var instance: ModuleEntry
    }

    override fun onSystemServerStarting(param: XposedModuleInterface.SystemServerStartingParam) {
        super.onSystemServerStarting(param)
        YLog.info(TAG, "onSystemServerStarting: classLoader=${param.classLoader}")
    }

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        instance = this
        ReloadableBinders.enabled = true
        ReloadJobs.enabled = true
        val method = android.os.Binder::class.java.getDeclaredMethod(
            "onTransact", Int::class.javaPrimitiveType, Parcel::class.java,
            Parcel::class.java, Int::class.javaPrimitiveType
        )
        hook(method).setId("lyricon.reloadable.binders")
            .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
            .intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? =
                    ReloadableBinders.dispatch(chain.thisObject, chain.args[0] as Int,
                        chain.args[1] as Parcel, chain.args[2] as? Parcel, chain.args[3] as Int)
                        ?: chain.proceed()
            })
        YLog.init(this)
        YLog.info(
            TAG,
            "onModuleLoaded: isSystemServer=${param.isSystemServer}, processName=${param.processName}"
        )
    }

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        initializePackage(param, null)
    }

    private fun initializePackage(param: XposedModuleInterface.PackageReadyParam, application: Application?) {
        if (ReloadResources.retiring) return
        val packageName = param.packageName
        if (packageName !in scopes) {
            YLog.debug(TAG, "onPackageReady: $packageName is not in scopes")
            return
        }

        YLog.info(TAG, "onPackageReady: $packageName")

        readyPackages[packageName] = param
        GeneralHooker.hook(this, param, application)
        when (packageName) {
            PackageNames.SYSTEM_UI -> SystemUIHooker.hook(this, param, application)
        }
    }

    override fun onHotReloading(param: XposedModuleInterface.HotReloadingParam): Boolean {
        // Draining Main dispatcher continuations must not block the Main looper itself.
        if (Looper.myLooper() == Looper.getMainLooper()) {
            YLog.error(TAG, "Hot reload callback must run off the main thread")
            return false
        }
        ReloadResources.retiring = true
        return try {
            val saved = HashMap<String, Any?>()
            val packages = HashMap<String, Any?>()
            readyPackages.forEach { (pkg, ready) ->
                packages[pkg] = hashMapOf<String, Any?>(
                    "info" to ready.applicationInfo, "loader" to ready.classLoader,
                    "defaultLoader" to ready.defaultClassLoader, "factory" to ready.appComponentFactory,
                    "first" to ready.isFirstPackage, "application" to GeneralHooker.appContext
                )
            }
            saved["packages"] = packages
            ReloadResources.onMain {
                if (readyPackages.containsKey(PackageNames.SYSTEM_UI)) {
                    saved["roots"] = ArrayList(StatusBarViewManager.controllers.map { it.statusBarView })
                    saved["song"] = LyricViewController.currentSong?.let { json.encodeToString(it) }
                    saved["provider"] = LyricDataHub.providerSnapshot()
                    saved["playing"] = LyricViewController.isPlaying
                    saved["position"] = LyricViewController.currentLogicPosition
                    saved["island"] = MiuiIslandHooker.currentRect?.let { Rect(it) }
                    saved["landscape"] = MiuiIslandHooker.isLandscape
                    SystemUIHooker.retireOnMain()
                }
            }
            if (readyPackages.containsKey(PackageNames.SYSTEM_UI)) AiTranslator.beginRetirement()
            ReloadableBinders.retire()
            ReloadJobs.retire()
            if (readyPackages.containsKey(PackageNames.SYSTEM_UI)) AiTranslator.finishRetirement()
            io.github.proify.lyricon.xposed.hook.LibraryWorkers.close()
            param.setSavedInstanceState(saved)
            readyPackages.clear()
            YLog.info(TAG, "Hot reload: old generation retired")
            true
        } catch (error: Throwable) {
            YLog.error(TAG, "Hot reload retirement failed; restart this process to recover", error)
            false
        }
    }

    @Suppress("UNCHECKED_CAST")
    override fun onHotReloaded(param: XposedModuleInterface.HotReloadedParam) {
        super.onHotReloaded(param)
        onModuleLoaded(param)
        val saved = param.savedInstanceState as? Map<String, Any?>
            ?: error("Missing hot reload state")
        val packages = saved["packages"] as? Map<String, Map<String, Any?>> ?: emptyMap()
        ReloadResources.onMain {
            MiuiIslandHooker.restoreState(saved["island"] as? Rect, saved["landscape"] == true)
            packages.forEach { (pkg, item) ->
                val application = item["application"] as? Application
                if (pkg == PackageNames.SYSTEM_UI) SystemUIHooker.restoring = application != null
                initializePackage(object : XposedModuleInterface.PackageReadyParam {
                    override fun getPackageName() = pkg
                    override fun getApplicationInfo() = item["info"] as ApplicationInfo
                    override fun isFirstPackage() = item["first"] == true
                    override fun getClassLoader() = item["loader"] as ClassLoader
                    override fun getDefaultClassLoader() = item["defaultLoader"] as ClassLoader
                    override fun getAppComponentFactory() = item["factory"] as AppComponentFactory
                }, application)
            }
            if (packages.containsKey(PackageNames.SYSTEM_UI)) {
                SystemUIHooker.restoreViews(saved["roots"] as? List<ViewGroup> ?: emptyList())
                (saved["provider"] as? String)?.let {
                    LyricViewController.onActiveProviderChanged(json.decodeFromString<ProviderInfo>(it))
                }
                (saved["song"] as? String)?.let {
                    LyricViewController.onSongChanged(json.decodeFromString<Song>(it))
                }
                LyricViewController.onPositionChanged(saved["position"] as? Long ?: 0L)
                LyricViewController.onPlaybackStateChanged(saved["playing"] == true)
            }
        }
        YLog.info(TAG, "Hot reload complete: ${param.processName}")
    }
}