package com.prism.launcher.browser

import android.graphics.Bitmap
import android.os.Build
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import okhttp3.Response
import java.io.ByteArrayInputStream

class PrismWebViewClient(
    private val blocklist: HostBlocklist,
    private val isPrivateTab: Boolean,
    private val onTitle: (String) -> Unit,
    private val onUrl: (String) -> Unit,
    private val getEngine: () -> PrismTunnelEngine?,
) : WebViewClient() {

    private val hostHealthMap = mutableMapOf<String, Long>() // host -> last_failure_timestamp
    private val HEALTH_TRACK_MS = 30000L // 30 seconds

    // NOTE: History recording must explicitly check 'isPrivateTab' to ensure
    // private browsing URLs are NEVER logged to a persistent database.

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        view.loadUrl(request.url.toString())
        return true
    }

    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
        val url = request?.url ?: return super.shouldInterceptRequest(view, request)
        val host = url.host ?: return super.shouldInterceptRequest(view, request)

        // 1. P2P Domain interception (Universal — works in public AND private tabs)
        if (P2pDnsManager.resolve(host, onlyP2p = true) != null) {
            val now = System.currentTimeMillis()
            val lastFail = hostHealthMap[host] ?: 0L
            val isHealthy = (now - lastFail) > HEALTH_TRACK_MS

            if (!isHealthy && !request.isForMainFrame) {
                // If the host is unhealthy, immediately skip mesh fetch for sub-resources
                return handleSubResourceFallback(view?.context, url)
            }

            val engine = getEngine()
            
            // Sub-resources get a much shorter timeout to prevent page hanging
            val cTimeout = if (request.isForMainFrame) 8000L else 2000L
            val rTimeout = if (request.isForMainFrame) 10000L else 3000L
            
            val response: Response? = engine?.fetchMeshContent(url.toString(), cTimeout, rTimeout)
 
            if (response == null) {
                // Mark host as unhealthy on timeout
                hostHealthMap[host] = System.currentTimeMillis()

                if (request.isForMainFrame) {
                    // Return an explicit error page ONLY for the main frame.
                    val errorHtml = """
                        <html><body style="font-family:sans-serif;padding:32px;color:#ccc;background:#111">
                        <h2>&#x26D4; Mesh Unreachable</h2>
                        <p>Could not connect to <b>$host</b> via the P2P network.</p>
                        <p style="color:#888;font-size:0.9em">Check that the domain is mapped correctly in P2P DNS settings and that the peer node is online.</p>
                        </body></html>
                    """.trimIndent()
                    return WebResourceResponse(
                        "text/html", "utf-8", 503, "Service Unavailable",
                        mapOf("Content-Type" to "text/html; charset=utf-8"),
                        java.io.ByteArrayInputStream(errorHtml.toByteArray())
                    )
                } else {
                    // Silent fallback for sub-resources (favicon, scripts, etc.)
                    return handleSubResourceFallback(view?.context, url)
                }
            }

            val statusCode = response.code
            if (statusCode == 404 && !request.isForMainFrame) {
                return handleSubResourceFallback(view?.context, url)
            }

            val contentType = response.header("Content-Type") ?: "text/html"
            val parts = contentType.split(";")
            val mimeType = parts[0].trim().lowercase()
            var encoding = "utf-8"
            for (i in 1 until parts.size) {
                val p = parts[i].trim().lowercase()
                if (p.startsWith("charset=")) {
                    encoding = p.substringAfter("charset=").trim()
                    break
                }
            }

            val headers = mutableMapOf<String, String>()
            for (name in response.headers.names()) {
                response.header(name)?.let { headers[name] = it }
            }

            val reasonPhrase = response.message.ifBlank {
                when (statusCode) {
                    200 -> "OK"; 301 -> "Moved Permanently"; 302 -> "Found"
                    404 -> "Not Found"; 500 -> "Internal Server Error"; else -> "Unknown"
                }
            }

            return WebResourceResponse(
                mimeType, encoding, statusCode, reasonPhrase, headers,
                response.body?.byteStream() ?: java.io.ByteArrayInputStream(ByteArray(0))
            )
        }

        // 2. Ad-blocking (Private Mode ONLY)
        if (isPrivateTab && blocklist.shouldBlockHost(host)) {
            return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
        }

        return super.shouldInterceptRequest(view, request)
    }

    private fun handleSubResourceFallback(context: android.content.Context?, url: android.net.Uri): WebResourceResponse {
        val path = url.path?.lowercase() ?: ""
        val isImage = path.endsWith(".png") || path.endsWith(".jpg") || 
                      path.endsWith(".jpeg") || path.endsWith(".gif") || 
                      path.endsWith(".ico") || path.endsWith(".svg") || 
                      path.endsWith(".webp")
        
        if (isImage) {
            val ctx = context ?: com.prism.launcher.PrismApp.instance
            val iconBytes = PrismWebHost.getAppIconBytes(ctx)
            if (iconBytes != null) {
                return WebResourceResponse("image/png", "utf-8", 200, "OK", 
                    mapOf("Content-Type" to "image/png"), 
                    java.io.ByteArrayInputStream(iconBytes))
            }
        }
        
        // Default silent 404 for other sub-resources (scripts, CSS, etc.)
        return WebResourceResponse("text/plain", "utf-8", 404, "Not Found", 
            mapOf("Content-Type" to "text/plain"), 
            java.io.ByteArrayInputStream(ByteArray(0)))
    }

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        if (url != null) onUrl(url)
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        val title = view?.title?.takeIf { it.isNotBlank() } ?: (url ?: "")
        onTitle(title)
        if (url != null) onUrl(url)
        
        // --- Custom Font Injection ---
        view?.context?.let { ctx ->
            val css = com.prism.launcher.PrismFontEngine.getWebViewCss(ctx)
            if (css.isNotEmpty()) {
                val script = """
                    (function() {
                        var style = document.createElement('style');
                        style.type = 'text/css';
                        style.innerHTML = `${css.replace("`", "\\`")}`;
                        document.head.appendChild(style);
                    })();
                """.trimIndent()
                view.evaluateJavascript(script, null)
            }
        }
        // -----------------------------
    }
}
