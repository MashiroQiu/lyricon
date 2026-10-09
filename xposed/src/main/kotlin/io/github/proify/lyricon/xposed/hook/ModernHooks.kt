package io.github.proify.lyricon.xposed.hook

import io.github.libxposed.api.XposedInterface
import io.github.proify.lyricon.xposed.ModuleEntry
import io.github.proify.lyricon.xposed.logger.YLog
import java.lang.reflect.Member
import java.lang.reflect.Method

internal open class MethodCallback {
    class MethodHookParam(val thisObject: Any?, val args: Array<Any?>) {
        var result: Any? = null
    }
    open fun beforeHookedMethod(param: MethodHookParam) = Unit
    open fun afterHookedMethod(param: MethodHookParam) = Unit
}

internal object HookMethods {
    fun findAndHookMethod(name: String, loader: ClassLoader, method: String,
                          vararg parameters: Any?): XposedInterface.HookHandle =
        findAndHookMethod(loader.loadClass(name), method, *parameters)

    fun findAndHookMethod(type: Class<*>, name: String,
                          vararg parameters: Any?): XposedInterface.HookHandle {
        val callback = parameters.last() as MethodCallback
        val types = parameters.dropLast(1).map { it as Class<*> }.toTypedArray()
        var owner: Class<*>? = type
        while (owner != null) {
            try {
                return hookMethod(owner.getDeclaredMethod(name, *types), callback)
            } catch (_: NoSuchMethodException) { owner = owner.superclass }
        }
        throw NoSuchMethodException("${type.name}.$name")
    }

    fun hookMethod(member: Member, callback: MethodCallback): XposedInterface.HookHandle =
        ModuleEntry.instance.hook(member as Method).intercept { chain ->
            val param = MethodCallback.MethodHookParam(chain.thisObject, chain.args.toTypedArray())
            // Listener failures must never suppress the SystemUI method.
            runCatching { callback.beforeHookedMethod(param) }
                .onFailure { YLog.error("ModernHooks", "before callback failed", it) }
            param.result = chain.proceed(param.args)
            runCatching { callback.afterHookedMethod(param) }
                .onFailure { YLog.error("ModernHooks", "after callback failed", it) }
            param.result
        }
}
