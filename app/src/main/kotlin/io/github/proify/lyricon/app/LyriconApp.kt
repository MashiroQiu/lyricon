/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package io.github.proify.lyricon.app

import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import io.github.proify.lyricon.app.bridge.AppBridge
import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageInfo
import androidx.core.content.pm.PackageInfoCompat
import io.github.proify.lyricon.app.util.AppLangUtils
import io.github.proify.lyricon.common.util.safe

class LyriconApp : Application() {

    init {
        instance = this
    }

    override fun attachBaseContext(base: Context) {
        AppLangUtils.setDefaultLocale(base)
        super.attachBaseContext(AppLangUtils.wrapContext(base))
    }

    override fun onCreate() {
        super.onCreate()
        // Reuse the existing config and back up files; no world-readable filesystem access.
        dataDir.resolve("shared_prefs").listFiles()?.filter { it.extension == "xml" }?.forEach {
            getSharedPreferences(it.nameWithoutExtension, MODE_PRIVATE)
        }
        getSharedPreferences(packageName + "_preferences", MODE_PRIVATE)
        XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
            override fun onServiceBind(service: XposedService) {
                AppBridge.moduleActive = true
                RemotePreferenceSync.bind(service)
            }
            override fun onServiceDied(service: XposedService) {
                AppBridge.moduleActive = false
                RemotePreferenceSync.bind(null)
            }
        })
    }

    override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences {
        val prefs = super.getSharedPreferences(name, MODE_PRIVATE)
        if (name != null) RemotePreferenceSync.track(name, prefs)
        return prefs.safe()
    }

    companion object {
        const val TAG: String = "LyriconApp"

        @SuppressLint("StaticFieldLeak")
        lateinit var instance: LyriconApp
            private set

        fun get(): LyriconApp = instance

        val packageInfo: PackageInfo by lazy {
            instance.packageManager.getPackageInfo(
                instance.packageName, 0
            )
        }
        val versionCode: Long by lazy { PackageInfoCompat.getLongVersionCode(packageInfo) }

        private var _safeMode: Boolean = false

        val safeMode: Boolean get() = _safeMode

        fun updateSafeMode(safeMode: Boolean) {
            _safeMode = safeMode
        }
    }
}