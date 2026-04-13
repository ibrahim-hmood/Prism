package com.prism.launcher.files

import java.io.File

/**
 * Singleton to manage global "Cut" operations in the file system.
 */
object FileExplorerClipboard {
    
    private var cutFile: File? = null
    
    fun setCutFile(file: File) {
        cutFile = file
    }
    
    fun getCutFile(): File? {
        return cutFile
    }
    
    fun clear() {
        cutFile = null
    }
}
