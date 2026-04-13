package com.prism.launcher.browser

import android.content.Context
import android.net.Uri
import android.util.Log
import android.webkit.MimeTypeMap
import com.prism.launcher.PrismSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import androidx.documentfile.provider.DocumentFile
import com.prism.launcher.messaging.AiManager

/**
 * Handle serving local web content over the Prism Mesh.
 * This class implements a minimal HTTP/1.1 server specifically for P2P Hosting.
 */
object PrismWebHost {

    private const val TAG = "PrismWebHost"

    suspend fun serve(context: Context, socket: Socket, domain: String, preReadHeader: String? = null) = withContext(Dispatchers.IO) {
        val input = socket.getInputStream()
        val output = socket.getOutputStream()

        try {
            // Find matching site
            val sites = PrismSettings.getP2pHostedSites(context)
            val site = sites.find { it.domain.equals(domain, ignoreCase = true) && it.isActive }
            
            if (site == null) {
                sendError(output, 404, "Site Not Found: $domain")
                return@withContext
            }

            // Read the HTTP request (Simple parsing for GET)
            val header = preReadHeader ?: readLine(input) ?: return@withContext
            Log.d(TAG, "Serving Request for $domain: ${header.substringBefore("\n")}")
            
            val lines = header.lines()
            val requestLine = lines[0]
            val parts = requestLine.split(" ")
            if (parts.size < 2) return@withContext
            
            val method = parts[0]
            var path = parts[1]

            if (method != "GET") {
                sendError(output, 405, "Method Not Allowed")
                return@withContext
            }

            // Sanitize and resolve path
            if (path.contains("..")) {
                sendError(output, 403, "Forbidden")
                return@withContext
            }

            if (path == "/") path = "/index.html"
            
            // Resolve local file using URI permissions or raw path
            val rootUri = Uri.parse(site.localPath)
            
            // In a production environment with ACTION_OPEN_DOCUMENT_TREE, 
            // we'd use DocumentFile.fromTreeUri. But since we have MANAGE_EXTERNAL_STORAGE,
            // we'll try to resolve the actual path.
            
            val fileResult = resolveFile(context, rootUri, path)
            
            if (fileResult == null) {
                sendError(output, 404, "File Not Found: $path")
            } else if (fileResult is File) {
                if (fileResult.name.endsWith(".prism")) {
                    sendAiTemplate(context, output, fileResult)
                } else {
                    sendFile(output, fileResult)
                }
            } else if (fileResult is DocumentFile) {
                if (fileResult.name?.endsWith(".prism") == true) {
                    sendAiTemplate(context, output, fileResult)
                } else {
                    sendDocumentFile(context, output, fileResult)
                }
            }

        } catch (e: Exception) {
            com.prism.launcher.PrismLogger.logError(TAG, "Serving error for $domain", e)
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun resolveFile(context: Context, rootUri: Uri, reqPath: String): Any? {
        val cleanPath = reqPath.removePrefix("/")
        
        // Strategy A: Direct File access (Fastest, works with MANAGE_EXTERNAL_STORAGE)
        var baseDir = rootUri.path ?: ""
        if (baseDir.startsWith("/tree/primary:")) {
            baseDir = baseDir.replace("/tree/primary:", "/storage/emulated/0/")
        } else if (baseDir.startsWith("/document/primary:")) {
            baseDir = baseDir.replace("/document/primary:", "/storage/emulated/0/")
        }
        
        val file = File(baseDir, cleanPath)
        if (file.exists() && !file.isDirectory) return file
        
        // Strategy B: SAF Navigation (Fallback for non-primary storage)
        try {
            val rootDoc = DocumentFile.fromTreeUri(context, rootUri)
            if (rootDoc != null && rootDoc.exists()) {
                var current: DocumentFile? = rootDoc
                val segments = cleanPath.split("/")
                for (seg in segments) {
                    if (seg.isEmpty()) continue
                    current = current?.findFile(seg)
                }
                if (current != null && current.isFile) return current
            }
        } catch (e: Exception) {
            Log.w(TAG, "SAF Resolution failed for $reqPath", e)
        }
        
        return null
    }

    private suspend fun sendAiTemplate(context: Context, output: OutputStream, source: Any) {
        val prompt = when(source) {
            is File -> source.readText()
            is DocumentFile -> context.contentResolver.openInputStream(source.uri)?.bufferedReader()?.use { it.readText() } ?: ""
            else -> ""
        }
        
        Log.d(TAG, "Processing AI Template: $prompt")
        
        // Wrap prompt with context to ensure it returns HTML
        val systemContext = "Output ONLY valid HTML. Do not include markdown code blocks like ```html. Just the raw HTML code."
        val finalPrompt = "$systemContext\n\nUser Content Request:\n$prompt"
        
        val (htmlContent, _) = AiManager.getResponse(context, finalPrompt)
        com.prism.launcher.PrismLogger.logInfo(TAG, "AI Content Generated for $source")
        
        // Clean up any potential markdown garbage if AI insisted on it
        val cleanedHtml = htmlContent.trim()
            .removePrefix("```html")
            .removeSuffix("```")
            .trim()

        val response = StringBuilder()
        response.append("HTTP/1.1 200 OK\r\n")
        response.append("Content-Type: text/html\r\n")
        response.append("Content-Length: ${cleanedHtml.toByteArray().size}\r\n")
        response.append("Server: PrismMesh/1.0-AI\r\n")
        response.append("Connection: close\r\n")
        response.append("\r\n")
        response.append(cleanedHtml)

        output.write(response.toString().toByteArray())
        output.flush()
    }

    private fun sendDocumentFile(context: Context, output: OutputStream, doc: DocumentFile) {
        val mime = doc.type ?: "application/octet-stream"
        val size = doc.length()

        val response = StringBuilder()
        response.append("HTTP/1.1 200 OK\r\n")
        response.append("Content-Type: $mime\r\n")
        response.append("Content-Length: $size\r\n")
        response.append("Date: ${getServerTime()}\r\n")
        response.append("Server: PrismMesh/1.0\r\n")
        response.append("Connection: close\r\n")
        response.append("\r\n")
        output.write(response.toString().toByteArray())

        context.contentResolver.openInputStream(doc.uri)?.use { input ->
            val buffer = ByteArray(32 * 1024)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                output.write(buffer, 0, read)
            }
        }
        output.flush()
    }

    private fun sendFile(output: OutputStream, file: File) {
        val ext = MimeTypeMap.getFileExtensionFromUrl(file.absolutePath)
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"

        val response = StringBuilder()
        response.append("HTTP/1.1 200 OK\r\n")
        response.append("Content-Type: $mime\r\n")
        response.append("Content-Length: ${file.length()}\r\n")
        response.append("Date: ${getServerTime()}\r\n")
        response.append("Server: PrismMesh/1.0\r\n")
        response.append("Connection: close\r\n")
        response.append("\r\n")

        output.write(response.toString().toByteArray())
        
        file.inputStream().use { input ->
            val buffer = ByteArray(16 * 1024)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                output.write(buffer, 0, read)
            }
        }
        output.flush()
    }

    private fun sendError(output: OutputStream, code: Int, message: String) {
        val response = "HTTP/1.1 $code $message\r\nContent-Type: text/plain\r\n\r\n$message"
        output.write(response.toByteArray())
        output.flush()
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c == -1 || c == '\n'.code) break
            if (c != '\r'.code) sb.append(c.toChar())
        }
        val line = sb.toString()
        return if (line.isEmpty()) null else line
    }

    private fun getServerTime(): String {
        val calendar = java.util.Calendar.getInstance()
        val dateFormat = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US)
        dateFormat.timeZone = TimeZone.getTimeZone("GMT")
        return dateFormat.format(calendar.time)
    }
}
