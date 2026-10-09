package io.github.proify.lyricon.xposed

import androidx.annotation.Keep
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import io.github.proify.lyricon.common.PackageNames
import io.github.proify.lyricon.xposed.logger.YLog
import io.github.proify.lyricon.xposed.systemui.Directory
import io.github.proify.lyricon.xposed.systemui.SystemUIHooker

@Keep
class ModuleEntry : XposedModule() {
    companion object {
        lateinit var instance: ModuleEntry
            private set
        lateinit var processName: String
            private set
    }
    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        instance = this
        processName = param.processName
        YLog.init(this)
    }
    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        if (param.packageName != PackageNames.SYSTEM_UI || !param.isFirstPackage) return
        val helper = PackageHelper(param)
        helper.doOnAppCreated { Directory.initialize(it) }
        SystemUIHooker.onAttach(helper)
        SystemUIHooker.onHook()
    }
}
