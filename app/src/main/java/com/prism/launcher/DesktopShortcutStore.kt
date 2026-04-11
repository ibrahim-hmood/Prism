package com.prism.launcher

import android.content.ComponentName
import android.content.Context

class DesktopShortcutStore(context: Context) {
    private val prefs = context.getSharedPreferences("prism_desktop", Context.MODE_PRIVATE)

    fun readGrid(cellCount: Int): Array<ComponentName?> {
        val raw = prefs.getString(KEY, null) ?: return Array(cellCount) { null }
        val tokens = raw.split(";")
        val out = Array<ComponentName?>(cellCount) { null }
        for (token in tokens) {
            if (token.isBlank()) continue
            val parts = token.split(",")
            if (parts.size != 3) continue
            val idx = parts[0].toIntOrNull() ?: continue
            if (idx !in out.indices) continue
            out[idx] = ComponentName(parts[1], parts[2])
        }
        return out
    }

    fun writeGrid(cells: Array<ComponentName?>) {
        val sb = StringBuilder()
        cells.forEachIndexed { index, cn ->
            if (cn != null) {
                if (sb.isNotEmpty()) sb.append(';')
                sb.append(index).append(',').append(cn.packageName).append(',').append(cn.className)
            }
        }
        prefs.edit().putString(KEY, sb.toString()).apply()
    }

    companion object {
        private const val KEY = "cells_v1"
    }
}
