package com.prism.launcher.browser

import android.content.Context

object PrismBlocklist {
    @Volatile
    private var instance: HostBlocklist? = null

    fun get(context: Context): HostBlocklist {
        val existing = instance
        if (existing != null) return existing
        return synchronized(this) {
            instance ?: HostBlocklist(context.applicationContext).also { instance = it }
        }
    }
}
