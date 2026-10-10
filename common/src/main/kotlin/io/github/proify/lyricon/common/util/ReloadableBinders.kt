package io.github.proify.lyricon.common.util

import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * Only enabled in the injected module. Remote clients receive boot-classloader Binder objects,
 * so cached AIDL endpoints cannot keep retired module Stubs / classloaders alive.
 */
object ReloadableBinders {
    @Volatile var enabled = false
    @Volatile private var retired = false
    private val endpoints = IdentityHashMap<Binder, Binder>()
    private val delegates = ConcurrentHashMap<Binder, Binder>()

    @Synchronized
    fun wrap(stub: Binder): IBinder {
        if (!enabled) return stub
        return endpoints.getOrPut(stub) {
            Binder().also {
                it.attachInterface(null, stub.interfaceDescriptor)
                if (!retired) delegates[it] = stub
            }
        }
    }

    fun dispatch(endpoint: Any?, code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean? {
        if (endpoint !is Binder) return null
        val stub = delegates[endpoint] ?: return null
        return stub.transact(code, data, reply, flags)
    }

    @Synchronized
    fun retire() {
        retired = true
        delegates.clear()
        endpoints.clear()
    }
}
