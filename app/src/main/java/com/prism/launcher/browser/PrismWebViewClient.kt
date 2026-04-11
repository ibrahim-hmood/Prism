package com.prism.launcher.browser

import android.graphics.Bitmap
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream

class PrismWebViewClient(
    private val blocklist: HostBlocklist,
    private val isPrivateTab: Boolean,
    private val onTitle: (String) -> Unit,
    private val onUrl: (String) -> Unit,
) : WebViewClient() {

    // NOTE: History recording must explicitly check 'isPrivateTab' to ensure 
    // private browsing URLs are NEVER logged to a persistent database.

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        view.loadUrl(request.url.toString())
        return true
    }

    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
        if (!isPrivateTab) return super.shouldInterceptRequest(view, request)
        val url = request?.url ?: return super.shouldInterceptRequest(view, request)
        val host = url.host ?: return super.shouldInterceptRequest(view, request)
        if (blocklist.shouldBlockHost(host)) {
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
    }
}
