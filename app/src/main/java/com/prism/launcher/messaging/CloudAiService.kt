package com.prism.launcher.messaging

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Executes network requests to OpenAI-compatible cloud LLM endpoints.
 * Also includes mock logic for image/video generation demonstration.
 */
object CloudAiService {

    /**
     * Top-level entry point for AI generation.
     * Returns Pair(ResponseText, Pair(AttachmentUri, AttachmentType))
     */
    suspend fun generateResponse(prompt: String): Pair<String, Pair<String?, String?>> {
        // In a real implementation, this would call your Cloud LLM API (OpenAI/Gemini/etc)
        // and parse the response for tool calls (like image generators).
        
        val responseText: String
        var attachmentUri: String? = null
        var attachmentType: String? = null

        if (prompt.contains("image", ignoreCase = true)) {
            responseText = "I've generated a high-fidelity vision of your request using our cloud neural engine."
            attachmentUri = "https://picsum.photos/1200/800" // Mock image
            attachmentType = "image"
        } else if (prompt.contains("video", ignoreCase = true)) {
            responseText = "Here is an AI-synthesized motion sequence based on your prompt."
            attachmentUri = "https://www.w3schools.com/html/mov_bbb.mp4" // Mock video
            attachmentType = "video"
        } else if (prompt.contains("who are you", ignoreCase = true)) {
            responseText = "I am Sam, your advanced AI companion powered by the Prism Cloud Engine."
        } else {
            responseText = "Sam (Cloud): I processed your request. Our cloud infrastructure provides higher accuracy and multi-modal support compared to local inference."
        }

        return Pair(responseText, Pair(attachmentUri, attachmentType))
    }

    /**
     * Legacy/Internal helper for real API calls.
     */
    fun fetchResponse(baseUrl: String, apiKey: String, model: String, userText: String, base64Image: String? = null): String {
        return try {
            val url = URL(baseUrl + "chat/completions")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true

            val messages = JSONArray()
            val userMessage = JSONObject()
            userMessage.put("role", "user")

            if (base64Image == null) {
                userMessage.put("content", userText)
            } else {
                val contentArray = JSONArray()
                contentArray.put(JSONObject().apply {
                    put("type", "text")
                    put("text", userText)
                })
                contentArray.put(JSONObject().apply {
                    put("type", "image_url")
                    put("image_url", JSONObject().apply {
                        put("url", "data:image/jpeg;base64,$base64Image")
                    })
                })
                userMessage.put("content", contentArray)
            }
            messages.put(userMessage)

            val body = JSONObject()
            body.put("model", model)
            body.put("messages", messages)

            OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }

            val responseCode = conn.responseCode
            if (responseCode == 200) {
                val responseString = conn.inputStream.bufferedReader().use { it.readText() }
                val responseJson = JSONObject(responseString)
                val choices = responseJson.getJSONArray("choices")
                if (choices.length() > 0) {
                    choices.getJSONObject(0).getJSONObject("message").getString("content")
                } else {
                    "Error: No response choices found."
                }
            } else {
                val errorString = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "Unknown error"
                "Cloud Error ($responseCode): $errorString"
            }
        } catch (e: Exception) {
            e.printStackTrace()
            "Network Error: ${e.message}"
        }
    }
}
