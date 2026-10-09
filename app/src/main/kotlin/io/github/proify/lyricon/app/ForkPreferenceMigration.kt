package io.github.proify.lyricon.app

import android.content.Context
import android.content.SharedPreferences
import io.github.libxposed.service.XposedService
import io.github.proify.android.extensions.toJson
import io.github.proify.android.extensions.fromJson
import io.github.proify.lyricon.lyric.style.VisibilityRule
import io.github.proify.lyricon.lyric.style.BasicStyle
import java.io.File

/** Import tested fork settings before exposing the upstream preference service to the UI. */
object ForkPreferenceMigration {
    fun migrate(context: Context, service: XposedService) {
        val marker = context.getSharedPreferences("fork_migrations", Context.MODE_PRIVATE)
        if (marker.getBoolean("upstream_remote_v1", false)) return
        val files = File(context.applicationInfo.dataDir, "shared_prefs").listFiles().orEmpty()
        val names = files.map { it.name.removeSuffix(".xml") }.filter { !it.endsWith(".bak") }
        for (name in names) {
            val destination = when {
                name == "baseLyricStyle" -> "lyricon_style_base"
                name == "packageStyleManager" -> "lyricon_style_package_manager"
                name.startsWith("package_style_") -> name.replaceFirst("package_style_", "lyricon_style_app_")
                name == context.packageName + "_preferences" -> "default"
                name.startsWith("lyricon_style_") -> name
                else -> continue
            }
            val source = context.getSharedPreferences(name, Context.MODE_PRIVATE)
            val target = service.getRemotePreferences(destination)
            val existing = target.all
            val editor = target.edit()
            for ((key, value) in source.all) {
                if (key in existing) continue
                when (value) {
                    is String -> {
                        val imported = if (name == "baseLyricStyle" && key == "lyric_style_base_visibility_rules") {
                            value.fromJson<List<VisibilityRule>>()?.map {
                                if (it.mode == 3) it.copy(mode = VisibilityRule.MODE_HIDE_WHEN_PLAYING) else it
                            }?.toJson() ?: value
                        } else value
                        editor.putString(key, imported)
                    }
                    is Boolean -> editor.putBoolean(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is Float -> editor.putFloat(key, value)
                    is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                }
            }
            check(editor.commit()) { "Preference import failed: $destination" }
        }
        val basic = service.getRemotePreferences("lyricon_style_base")
        val rules = basic.getString("lyric_style_base_visibility_rules", null)
        if (rules.isNullOrBlank() || rules.trim() == "[]") {
            check(basic.edit().putString("lyric_style_base_visibility_rules", BasicStyle.Defaults.VISIBILITY_RULES.toJson()).commit())
        }
        marker.edit().putBoolean("upstream_remote_v1", true).apply()
    }
}
