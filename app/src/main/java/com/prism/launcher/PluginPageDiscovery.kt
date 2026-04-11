package com.prism.launcher

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle

object PluginPageDiscovery {
    fun discover(packageManager: PackageManager): List<PluginPageInfo> {
        val intent = Intent(PrismContracts.ACTION_DESKTOP_PAGE)
        val resolves = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.queryIntentActivities(
                intent,
                PackageManager.ResolveInfoFlags.of(PackageManager.GET_META_DATA.toLong())
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.queryIntentActivities(intent, PackageManager.GET_META_DATA)
        }
        val out = ArrayList<PluginPageInfo>()
        for (ri in resolves) {
            val ai = ri.activityInfo ?: continue
            val meta: Bundle = ai.metaData ?: continue
            val cls = meta.getString(PrismContracts.META_PAGE_VIEW_CLASS) ?: continue
            val label = ri.loadLabel(packageManager).toString()
            out.add(PluginPageInfo(ai.packageName, cls, label))
        }
        return out
    }
}
