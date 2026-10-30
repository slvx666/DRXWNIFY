/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.settings.integrations

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.metrolist.music.R
import timber.log.Timber

/**
 * VK's "I'm not a robot" captcha, solved by the user in a browser window. VK hands the app a page
 * ([url]) instead of a picture; once it is passed, the page gets a success token from VK. The page
 * is meant for VK's own apps, which read that token through a bridge object; this window listens on
 * every way it can come back (the bridge, a posted message, the answer of the check request, the
 * address) and passes it to [onToken].
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun VkCaptchaDialog(url: String, onToken: (String) -> Unit, onDismiss: () -> Unit) {
    val main = androidx.compose.runtime.remember { Handler(Looper.getMainLooper()) }
    val delivered = androidx.compose.runtime.remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    fun deliver(token: String?) {
        val clean = token?.trim()?.trim('"')?.takeIf { it.length >= 16 } ?: return
        main.post {
            if (!delivered.compareAndSet(false, true)) return@post
            Timber.tag("VkAuth").i("captcha solved, token received")
            onToken(clean)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
        title = { Text(stringResource(R.string.vk_captcha_title)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.vk_captcha_web_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Box(Modifier.fillMaxWidth().height(460.dp).padding(top = 8.dp)) {
                    AndroidView(
                        modifier = Modifier.fillMaxWidth().height(460.dp),
                        factory = { context ->
                            WebView(context).apply {
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                addJavascriptInterface(
                                    object {
                                        @JavascriptInterface
                                        fun onToken(token: String?) = deliver(token)

                                        /** Any call the page makes on a bridge object: the token is somewhere in its arguments. */
                                        @JavascriptInterface
                                        fun onCall(name: String?, args: String?) {
                                            Timber.tag("VkAuth").d("captcha bridge call: %s", name)
                                            deliver(tokenIn(args))
                                        }
                                    },
                                    "DrxCaptcha",
                                )
                                webViewClient = object : WebViewClient() {
                                    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                                        view.evaluateJavascript(HOOK_JS, null)
                                        deliver(url?.let(::tokenInUrl))
                                    }

                                    override fun onPageFinished(view: WebView, url: String?) {
                                        view.evaluateJavascript(HOOK_JS, null)
                                    }

                                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                        val target = request.url.toString()
                                        tokenInUrl(target)?.let { deliver(it); return true }
                                        return false
                                    }
                                }
                                loadUrl(url)
                            }
                        },
                        onRelease = { it.destroy() },
                    )
                }
            }
        },
    )
}

private fun tokenInUrl(url: String): String? =
    runCatching { android.net.Uri.parse(url).getQueryParameter("success_token") }.getOrNull()
        ?: Regex("success_token=([^&#]+)").find(url)?.groupValues?.get(1)

private fun tokenIn(text: String?): String? =
    text?.let { Regex("\"?success_token\"?\\s*[:=]\\s*\"?([A-Za-z0-9_\\-.]+)").find(it)?.groupValues?.get(1) }
        ?: text?.let { Regex("\"token\"\\s*:\\s*\"([A-Za-z0-9_\\-.]{16,})\"").find(it)?.groupValues?.get(1) }

/**
 * Installed in the page: bridge objects VK's apps provide (any method call is forwarded), posted
 * messages, and the answers of the page's own requests, all scanned for the success token.
 */
private val HOOK_JS = """
(function(){
  if (window.__drxHooked) return; window.__drxHooked = true;
  function scan(t){ try { if (typeof t !== 'string') t = JSON.stringify(t); if (t && t.indexOf('success_token') >= 0) {
    var m = /"?success_token"?\s*[:=]\s*"?([A-Za-z0-9_\-.]+)/.exec(t); if (m) DrxCaptcha.onToken(m[1]); } } catch(e){} }
  var bridge = new Proxy({}, { get: function(_, name){ return function(){ try { DrxCaptcha.onCall(String(name), JSON.stringify(Array.prototype.slice.call(arguments))); } catch(e){} }; } });
  ['AndroidBridge','VKCaptchaAndroidBridge','VkCaptchaAndroidBridge','VKWebAppBridge','VkCaptcha'].forEach(function(n){ if (!window[n]) window[n] = bridge; });
  window.addEventListener('message', function(e){ scan(e.data); DrxCaptcha.onCall('postMessage', (function(){ try { return JSON.stringify(e.data); } catch(x){ return ''; } })()); });
  var f = window.fetch; if (f) window.fetch = function(){ return f.apply(this, arguments).then(function(r){ try { r.clone().text().then(scan); } catch(e){} return r; }); };
  var o = XMLHttpRequest.prototype.open, s = XMLHttpRequest.prototype.send;
  XMLHttpRequest.prototype.send = function(){ this.addEventListener('load', function(){ try { scan(this.responseText); } catch(e){} }); return s.apply(this, arguments); };
})();
""".trimIndent()
