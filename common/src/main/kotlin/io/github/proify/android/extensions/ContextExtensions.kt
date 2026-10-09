/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.android.extensions

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import io.github.proify.lyricon.common.util.safe

fun Context.getPrivateSharedPreferences(name: String): SharedPreferences {
    return (applicationContext ?: this).getSharedPreferences(name, Context.MODE_PRIVATE).safe()
}

/** Preferences are private; LSPosed service handles cross-process sharing. */
fun Context.getWorldReadableSharedPreferences(name: String): SharedPreferences =
    getPrivateSharedPreferences(name)

@Suppress("unused")
fun Context.getSharedPreferences(name: String, worldReadable: Boolean): SharedPreferences =
    if (worldReadable) getWorldReadableSharedPreferences(name)
    else getPrivateSharedPreferences(name)

/**
 * 默认的 SharedPreferences
 *
 * 注意：`BackupManager`不会备份此SharedPreferences的设置
 */
val Context.defaultSharedPreferences: SharedPreferences
    get() = getWorldReadableSharedPreferences(defaultSharedPreferencesName)

val Context.defaultSharedPreferencesName: String get() = packageName + "_preferences"