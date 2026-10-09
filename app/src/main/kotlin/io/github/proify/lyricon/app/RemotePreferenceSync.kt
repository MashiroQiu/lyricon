package io.github.proify.lyricon.app

import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.github.libxposed.service.XposedService
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow

/** Local private preferences remain the backup source; LSPosed shares copies with SystemUI. */
internal object RemotePreferenceSync {
    val active = MutableStateFlow(false)
    private val local = ConcurrentHashMap<String, SharedPreferences>()
    private val listeners = ConcurrentHashMap<String, SharedPreferences.OnSharedPreferenceChangeListener>()
    @Volatile private var service: XposedService? = null
    private val main = Handler(Looper.getMainLooper())
    fun track(name: String, prefs: SharedPreferences) {
        if (local.putIfAbsent(name, prefs) != null) return
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            main.post { publish(name, prefs) }
        }
        listeners[name] = listener
        prefs.registerOnSharedPreferenceChangeListener(listener)
        publish(name, prefs)
    }
    fun bind(value: XposedService?) {
        service = value
        active.value = value != null
        if (value != null) local.forEach { (name, prefs) -> publish(name, prefs) }
    }
    private fun publish(name: String, prefs: SharedPreferences) {
        val current = service ?: return
        runCatching {
            val editor = current.getRemotePreferences(name).edit().clear()
            prefs.all.forEach { (key, value) ->
                when (value) {
                    is String -> editor.putString(key, value)
                    is Boolean -> editor.putBoolean(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is Float -> editor.putFloat(key, value)
                    is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                }
            }
            editor.apply()
        }.onFailure { Log.e("Lyricon", "Remote preference sync failed: $name", it) }
    }
}
