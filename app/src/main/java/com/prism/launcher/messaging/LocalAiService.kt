package com.prism.launcher.messaging

import android.app.ActivityManager
import android.content.Context
import java.io.File

import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInference.LlmInferenceOptions
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import com.prism.launcher.PrismLogger

/**
 * Executes on-device inference using MediaPipe LLM Inference API.
 * Includes safety guards to prevent native crashes on underpowered hardware.
 */
object LocalAiService {

    private var llmInference: LlmInference? = null
    private var llmSession: LlmInferenceSession? = null
    private var currentModelPath: String? = null

    private fun getOrInitSession(context: Context, modelPath: String): LlmInferenceSession? {
        if (llmInference != null && llmSession != null && currentModelPath == modelPath) {
            return llmSession
        }

        // --- Stabilization Guard: Hardware & Format Verification ---
        val error = validateHardwareAndFormat(context, modelPath)
        if (error != null) {
            throw IllegalStateException(error)
        }

        return try {
            llmSession = null
            llmInference?.close()
            llmInference = null

            val options = LlmInferenceOptions.builder()
                .setModelPath(modelPath)
                .setMaxTokens(2048)
                .setPreferredBackend(LlmInference.Backend.GPU)
                .build()

            val modelFile = java.io.File(modelPath)
            if (!modelFile.exists()) {
                PrismLogger.logError("LocalAiService", "Initialization ABORTED: Model file not found at $modelPath")
                return null
            }
            
            PrismLogger.logInfo("LocalAiService", "Initializing engine for $modelPath (Size: ${modelFile.length()} bytes, Readable: ${modelFile.canRead()})")
            
            // This is where native crashes (SIGSEGV) occur if the model is raw TFLite
            val inference = LlmInference.createFromOptions(context, options)
            llmInference = inference

            val sessionOptions = LlmInferenceSession.LlmInferenceSessionOptions.builder()
                .setTopK(40)
                .setTemperature(0.7f)
                .build()

            val session = LlmInferenceSession.createFromOptions(inference, sessionOptions)
            llmSession = session
            currentModelPath = modelPath
            session
        } catch (e: Exception) {
            PrismLogger.logError("LocalAiService", "FAILED to initialize AI engine for $modelPath", e)
            e.printStackTrace()
            null
        }
    }

    private fun validateHardwareAndFormat(context: Context, modelPath: String): String? {
        val modelFile = java.io.File(modelPath)
        if (!modelFile.exists()) return "File not found: $modelPath"

        // 1. Signature Guard: Magic Bytes
        // MediaPipe .task files ARE ZIP archives (starting with PK\03\04)
        // Raw TFLite files start with TFL3.
        try {
            val raf = java.io.RandomAccessFile(modelFile, "r")
            val head = ByteArray(4)
            raf.read(head)
            raf.close()

            val hex = head.joinToString("") { String.format("%02X", it) }
            
            // ZIP Magic: 50 4B 03 04
            if (hex != "504B0304") {
                val type = when (hex) {
                    "54464C33" -> "Raw TFLite File (TFL3)"
                    else -> "Unknown/Binary (Hex: $hex)"
                }
                PrismLogger.logError("LocalAiService", "Format Mismatch: File is not a ZIP/Task bundle. Type detected: $type")
                return "Incompatible Model Format: This file is a $type, but MediaPipe Android requires a wrapped '.task' ZIP bundle. Please ensure you are using a model exported specifically for the MediaPipe Android LLM Inference API."
            }
        } catch (e: Exception) {
            return "Read Error: Unable to verify model header: ${e.message}"
        }

        // 2. Hardware/RAM Guard
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        
        val availableRamGb = memInfo.availMem / (1024.0 * 1024.0 * 1024.0)
        
        val thresholdGb = when {
            modelPath.lowercase().contains("gemma") -> 1.2
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
            val session = getOrInitSession(context, modelPath)
            if (session == null) {
                return "Error: Failed to initialize AI engine. The model may be incompatible with your device's GPU, or requires more RAM. Check System Diagnostics for details."
            }
            
            session.addQueryChunk(userText)
            val result = session.generateResponse()
            if (result.isNullOrBlank()) "The model returned an empty response." else result
        } catch (e: IllegalStateException) {
            "Safety Guard: ${e.message}"
        } catch (e: Exception) {
            e.printStackTrace()
            "Local AI Error: ${e.message}"
        }
    }
}
