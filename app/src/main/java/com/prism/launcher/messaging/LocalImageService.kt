package com.prism.launcher.messaging

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.tasks.vision.imagegenerator.ImageGenerator
import com.google.mediapipe.tasks.vision.imagegenerator.ImageGenerator.ImageGeneratorOptions
import com.google.mediapipe.framework.image.BitmapExtractor
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Executes on-device Diffusion (Stable Diffusion) using MediaPipe Vision.
 */
object LocalImageService {

    private var imageGenerator: ImageGenerator? = null
    private var currentModelPath: String? = null

    private fun initGenerator(context: Context, modelPath: String) {
        if (imageGenerator != null && currentModelPath == modelPath) return

        val file = File(modelPath)
        if (!file.exists()) throw IllegalStateException("Model file not found")

        val options = ImageGeneratorOptions.builder()
            .setImageGeneratorModelDirectory(modelPath) // SD typically expects a directory or bundle
            .build()

        imageGenerator?.close()
        imageGenerator = ImageGenerator.createFromOptions(context, options)
        currentModelPath = modelPath
    }

    suspend fun generateImage(context: Context, modelPath: String, prompt: String): Bitmap? = withContext(Dispatchers.IO) {
        try {
            initGenerator(context, modelPath)
            // SD v1.5 usually takes ~20-60s on mobile. 
            // We use the synchronous generate method inside IO dispatcher.
            val result = imageGenerator?.generate(prompt, 20, 0)
            
            val mediaImage = result?.generatedImage()
            if (mediaImage != null) {
                BitmapExtractor.extract(mediaImage)
            } else {
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
