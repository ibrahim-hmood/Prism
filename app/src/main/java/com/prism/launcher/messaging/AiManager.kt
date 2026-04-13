package com.prism.launcher.messaging

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import com.prism.launcher.PrismSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * Orchestrates AI interactions, deciding between Cloud and Local engines.
 */
object AiManager {

    suspend fun getResponse(context: Context, userText: String, imageUri: Uri? = null): Pair<String, Pair<String?, String?>> = withContext(Dispatchers.IO) {
        val mode = PrismSettings.getAiMode(context)
        
        if (mode == PrismSettings.AI_MODE_CLOUD) {
            // Priority 1: Check for triggers (image/video)
            if (userText.contains("generate image", ignoreCase = true) || userText.contains("make a picture", ignoreCase = true)) {
                val uri = ImageGenManager.generateImage(context, userText)
                return@withContext Pair("I've generated a high-fidelity image for you.", Pair(uri?.toString(), "image"))
            }

            if (userText.contains("video", ignoreCase = true)) {
                return@withContext CloudAiService.generateResponse(userText)
            }

            // Priority 2: Real Cloud API call
            val apiKey = PrismSettings.getCloudAiKey(context)
            if (apiKey.isBlank()) return@withContext Pair("Error: API Key is missing. Check AI Settings.", Pair(null, null))
            
            val baseUrl = PrismSettings.getCloudAiBaseUrl(context)
            val model = PrismSettings.getCloudAiModel(context)
            val base64Image = imageUri?.let { encodeImageToBase64(context, it) }
            
            val textRaw = CloudAiService.fetchResponse(baseUrl, apiKey, model, userText, base64Image)
            return@withContext Pair(textRaw, Pair(null, null))
        } else {
            val modelPath = PrismSettings.getLocalAiModelPath(context)
            if (modelPath.isBlank()) return@withContext Pair("Error: No local model selected. Please download or select one in Settings.", Pair(null, null))
            
            val response = LocalAiService.generateResponse(context, modelPath, userText)
            return@withContext Pair(response, Pair(null, null))
        }
    }

    private fun encodeImageToBase64(context: Context, uri: Uri): String? {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri)
            val bitmap = BitmapFactory.decodeStream(inputStream)
            val outputStream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 70, outputStream)
            val bytes = outputStream.toByteArray()
            Base64.encodeToString(bytes, Base64.NO_WRAP)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
