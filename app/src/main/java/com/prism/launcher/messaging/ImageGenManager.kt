package com.prism.launcher.messaging

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.prism.launcher.PrismSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * High-level manager for image generation requests.
 * Handles storage of the resulting AI images and engine selection.
 */
object ImageGenManager {

    suspend fun generateImage(context: Context, prompt: String): Uri? = withContext(Dispatchers.IO) {
        val mode = PrismSettings.getAiMode(context)
        
        return@withContext if (mode == PrismSettings.AI_MODE_CLOUD) {
            val apiKey = PrismSettings.getCloudAiKey(context)
            val baseUrl = PrismSettings.getCloudAiBaseUrl(context)
            if (apiKey.isBlank()) return@withContext null
            
            val bitmap = CloudAiService.fetchImage(baseUrl, apiKey, prompt)
            bitmap?.let { saveToPublicStore(context, it) }
        } else {
            val modelPath = PrismSettings.getLocalImageModelPath(context)
            if (modelPath.isBlank()) return@withContext null
            
            val bitmap = LocalImageService.generateImage(context, modelPath, prompt)
            bitmap?.let { saveToPublicStore(context, it) }
        }
    }

    private fun saveToPublicStore(context: Context, bitmap: Bitmap): Uri? {
        val fileName = "gen_${UUID.randomUUID()}.jpg"
        val relativePath = "DCIM/Prism/Nebula"
        
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, relativePath)
        }
        
        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        return try {
            uri?.let {
                context.contentResolver.openOutputStream(it).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out!!)
                }
            }
            uri
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Extracts a descriptive image prompt from an LLM response if it contains a [VISUAL] tag.
     * Use this to bridge LLM text generation with Diffusion image generation.
     */
    fun extractVisualPrompt(text: String): String? {
        val tag = "[VISUAL]"
        if (text.contains(tag)) {
            return text.substringAfter(tag).trim()
        }
        return null
    }
}
