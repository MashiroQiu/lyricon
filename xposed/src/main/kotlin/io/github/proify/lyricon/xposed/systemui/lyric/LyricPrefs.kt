/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.xposed.systemui.lyric

import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import io.github.proify.lyricon.xposed.ModuleEntry
import io.github.proify.lyricon.app.bridge.AppBridge
import io.github.proify.lyricon.common.PackageNames
import io.github.proify.lyricon.lyric.style.BasicStyle
import io.github.proify.lyricon.lyric.style.LyricStyle
import io.github.proify.lyricon.lyric.style.PackageStyle

private class RemoteStylePreferences(private val delegate: SharedPreferences) : SharedPreferences by delegate {
    @Volatile private var dirty = true
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        dirty = true
        Handler(Looper.getMainLooper()).post {
            LyricViewController.applyConfigurationUpdate(LyricPrefs.getLyricStyle())
        }
    }
    init { delegate.registerOnSharedPreferenceChangeListener(listener) }
    fun hasFileChanged() = dirty
    fun reload() { dirty = false }
}

object LyricPrefs {

    private val prefsCache = mutableMapOf<String, RemoteStylePreferences>()
    private val packageStyleCache = mutableMapOf<String, PackageStyleCache>()

    @Volatile
    var activePackageName: String? = null

    /* ---------------- base style ---------------- */

    private val baseStylePrefs: RemoteStylePreferences =
        createXPrefs(AppBridge.LyricStylePrefs.PREF_NAME_BASE_STYLE)

    val baseStyle: BasicStyle = BasicStyle().apply {
        load(baseStylePrefs)
    }
        get() {
            if (baseStylePrefs.hasFileChanged()) {
                baseStylePrefs.reload()
                field.load(baseStylePrefs)
            }
            return field
        }

    /* ---------------- default package style ---------------- */

    private val defaultPackageStylePrefs: RemoteStylePreferences by lazy {
        getPackagePrefs(
            AppBridge.LyricStylePrefs.DEFAULT_PACKAGE_NAME
        )
    }

    val defaultPackageStyle: PackageStyle = PackageStyle().apply {
        load(defaultPackageStylePrefs)
    }
        get() {
            if (defaultPackageStylePrefs.hasFileChanged()) {
                defaultPackageStylePrefs.reload()
                field.load(defaultPackageStylePrefs)
            }
            return field
        }

    /* ---------------- package manager ---------------- */

    private val packageStyleManagerPrefs: RemoteStylePreferences =
        createXPrefs(AppBridge.LyricStylePrefs.PREF_PACKAGE_STYLE_MANAGER)
        get() {
            if (field.hasFileChanged()) {
                field.reload()
            }
            return field
        }

    val activePackageStyle
        get() = run {
            val pkg = activePackageName
            if (pkg != null && isPackageEnabled(pkg)) {
                getPackageStyle(pkg)
            } else {
                defaultPackageStyle
            }
        }

    private fun isPackageEnabled(packageName: String): Boolean {
        packageStyleManagerPrefs.ensureLatest()
        return runCatching {
            packageStyleManagerPrefs
                .getStringSet(
                    AppBridge.LyricStylePrefs.KEY_ENABLED_PACKAGES,
                    emptySet()
                )
                ?.contains(packageName) ?: false
        }.getOrDefault(false)
    }

    /* ---------------- prefs cache ---------------- */

    private fun getPackagePrefName(packageName: String): String =
        AppBridge.LyricStylePrefs.getPackageStylePreferenceName(packageName)

    private fun getPackagePrefs(packageName: String): RemoteStylePreferences {
        val prefName = getPackagePrefName(packageName)
        return prefsCache.getOrPut(prefName) {
            createXPrefs(prefName)
        }
    }

    private fun createXPrefs(name: String): RemoteStylePreferences {
        return RemoteStylePreferences(ModuleEntry.instance.getRemotePreferences(name))
    }

    private fun RemoteStylePreferences.ensureLatest(): RemoteStylePreferences {
        runCatching { reload() }
        return this
    }

    /* ---------------- package style cache ---------------- */

    private class PackageStyleCache(
        private val prefs: RemoteStylePreferences,
        private val style: PackageStyle
    ) {
        fun getStyle(): PackageStyle {
            if (prefs.hasFileChanged()) {
                prefs.reload()
                style.load(prefs)
            }
            return style
        }
    }

    fun getPackageStyle(packageName: String): PackageStyle {
        return packageStyleCache.getOrPut(packageName) {
            val prefs = getPackagePrefs(packageName)
            val style = PackageStyle().apply {
                load(prefs)
            }
            PackageStyleCache(prefs, style)
        }.getStyle()
    }

    /* ---------------- lyric style ---------------- */

    fun getLyricStyle(packageName: String? = null): LyricStyle {
        if (packageName.isNullOrBlank()) {
            return LyricStyle(baseStyle, activePackageStyle)
        }
        return LyricStyle(
            baseStyle,
            getPackageStyle(packageName)
        )
    }
}