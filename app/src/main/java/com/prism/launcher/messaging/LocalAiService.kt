package com.prism.launcher.messaging

import android.app.ActivityManager
import android.content.Context
import java.io.File

import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInference.LlmInferenceOptions

/**
 * Executes on-device inference using MediaPipe LLM Inference API.
 * Includes safety guards to prevent native crashes on underpowered hardware.
 */
object LocalAiService {

    private var llmInference: LlmInference? = null
    private var currentModelPath: String? = null

    private fun getOrInitInference(context: Context, modelPath: String): LlmInference? {
        if (llmInference != null && currentModelPath == modelPath) {
            return llmInference
        }

        // --- Stabilization Guard: Hardware & Format Verification ---
        val error = validateHardwareAndFormat(context, modelPath)
        if (error != null) {
            throw IllegalStateException(error)
        }

        return try {
            llmInference?.close()
            val options = LlmInferenceOptions.builder()
                .setModelPath(modelPath)
                .setMaxTokens(512)
                .setTopK(40)
                .setTemperature(0.7f)
                .setRandomSeed(System.currentTimeMillis().toInt())
                .build()
            
            // This is where native crashes (SIGSEGV) occur if the model is raw TFLite
            // or if the device is an x86 Emulator.
            llmInference = LlmInference.createFromOptions(context, options)
            currentModelPath = modelPath
            llmInference
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun validateHardwareAndFormat(context: Context, modelPath: String): String? {
        // 1. Signature Guard: Extensions
        // MediaPipe LlmInference ONLY supports bundles (.bin or .task).
        // Providing a raw .tflite weight file causes a native C++ crash.
        val lowerPath = modelPath.lowercase()
        if (!lowerPath.endsWith(".bin") && !lowerPath.endsWith(".task")) {
            return "Incompatible Format: MediaPipe LLM requires a '.task' or '.bin' bundle. The provided .tflite file is raw weights and would cause a native crash."
        }

        // 2. Memory Guard
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        
        val availableRamGb = memInfo.availMem / (1024.0 * 1024.0 * 1024.0)
        val totalRamGb = memInfo.totalMem / (1024.0 * 1024.0 * 1024.0)

        // 2.7B+ models (like Phi-2) typically need at least 1.2GB of *free* RAM.
        // 1.5B models (like Qwen) need ~1.0GB.
        // 1.0B models need ~0.8GB.
        val thresholdGb = when {
            modelPath.lowercase().contains("phi") -> 1.2
            modelPath.lowercase().contains("qwen") -> 1.0
            else -> 0.8
        }
        
        if (availableRamGb < thresholdGb) {
            return "Insufficient RAM: Your device only has ${String.format("%.2f", availableRamGb)}GB free. This model requires ~${thresholdGb}GB free to initialize safely."
        }

        return null
    }

    fun generateResponse(context: Context, modelPath: String, userText: String): String {
        if (modelPath.isEmpty()) return "No local model selected. Please check Settings."

        val modelFile = File(modelPath)
        if (!modelFile.exists()) {
            return "Error: Local model file not found at $modelPath. Please re-select it in Settings."
        }

        return try {
            val inference = getOrInitInference(context, modelPath)
            if (inference == null) {
                return "Error: Failed to initialize AI engine. The model may be corrupted."
            }
            
            val result = inference.generateResponse(userText)
            if (result.isNullOrBlank()) "The model returned an empty response." else result
        } catch (e: IllegalStateException) {
            "Safety Guard: ${e.message}"
        } catch (e: Exception) {
            e.printStackTrace()
            "Local AI Error: ${e.message}"
        }
    }
}
