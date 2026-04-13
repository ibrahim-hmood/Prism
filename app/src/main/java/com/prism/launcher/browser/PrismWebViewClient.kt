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
        if (P2pDnsManager.resolve(host) != null) {
            val engine = getEngine()
            val response: Response? = engine?.fetchMeshContent(url.toString())

            if (response == null) {
                // Return an explicit error page rather than null.
                // Returning null causes WebView to try its own network stack for a domain
                // that doesn't exist in real DNS — causing a second, silent hang.
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

            val statusCode = response.code
            val reasonPhrase = response.message.ifBlank {
                when (statusCode) {
                    200 -> "OK"; 301 -> "Moved Permanently"; 302 -> "Found"
                    404 -> "Not Found"; 500 -> "Internal Server Error"; else -> "Unknown"
                }
            }

            return WebResourceResponse(
                mimeType, encoding, statusCode, reasonPhrase, headers,
                response.body?.byteStream() ?: ByteArrayInputStream(ByteArray(0))
            )
        }

        // 2. Ad-blocking (Private Mode ONLY)
        if (isPrivateTab && blocklist.shouldBlockHost(host)) {
            return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
        }

        return super.shouldInterceptRequest(view, request)
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
