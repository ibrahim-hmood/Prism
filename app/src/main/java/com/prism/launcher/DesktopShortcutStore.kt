package com.prism.launcher

import android.content.ComponentName
import android.content.Context
import java.io.File

sealed class DesktopItem {
    data class App(val component: ComponentName) : DesktopItem()
    data class FileRef(val absolutePath: String) : DesktopItem()
    data class DirectoryRef(val absolutePath: String, val name: String) : DesktopItem()
    data class Folder(val name: String, val folderId: String) : DesktopItem()
    data class NetworkedFolder(val url: String, val name: String, val type: String) : DesktopItem()

    fun serialize(): String {
        return when (this) {
            is App -> "app|${component.flattenToString()}"
            is FileRef -> "file|$absolutePath"
            is DirectoryRef -> "dir|$absolutePath|$name"
            is Folder -> "folder|$name|$folderId"
            is NetworkedFolder -> "network|$url|$name|$type"
        }
    }

    companion object {
        fun deserialize(raw: String): DesktopItem? {
            val parts = raw.split("|")
            return when (parts.getOrNull(0)) {
                "app" -> {
                    val cn = ComponentName.unflattenFromString(parts[1])
                    if (cn != null) App(cn) else null
                }
                "file" -> FileRef(parts[1])
                "dir" -> DirectoryRef(parts[1], parts.getOrNull(2) ?: "Folder")
                "folder" -> Folder(parts[1], parts.getOrNull(2) ?: "unknown")
                "network" -> NetworkedFolder(parts[1], parts.getOrNull(2) ?: "Networked Folder", parts.getOrNull(3) ?: "ftp")
                else -> {
                    // Legacy migration
                    val cn = ComponentName.unflattenFromString(raw)
                    if (cn != null) App(cn) else null
                }
            }
        }
    }
}

class DesktopShortcutStore(private val context: Context, private val pageIndex: Int = 1) {

    private val prefs = context.getSharedPreferences("desktop_prefs", Context.MODE_PRIVATE)

    fun readGrid(size: Int): MutableList<DesktopItem?> {
        val key = "cells_page_$pageIndex"
        val raw = prefs.getString(key, null) ?: return MutableList(size) { null }
        val items = raw.split(";;").map { if (it == "null") null else DesktopItem.deserialize(it) }
        return items.take(size).toMutableList()
    }

    fun writeGrid(grid: List<DesktopItem?>) {
        val key = "cells_page_$pageIndex"
        val raw = grid.joinToString(";;") { it?.serialize() ?: "null" }
        prefs.edit().putString(key, raw).apply()
    }

    companion object {
        /** Adds an item specifically to the given desktop page index. */
        fun add(context: Context, item: DesktopItem, pageIndex: Int) {
            val store = DesktopShortcutStore(context, pageIndex)
            val grid = store.readGrid(24) // 24 is current standard
            val emptyIdx = grid.indexOfFirst { it == null }
            if (emptyIdx != -1) {
                grid[emptyIdx] = item
                store.writeGrid(grid)
            }
        }
    }
}
