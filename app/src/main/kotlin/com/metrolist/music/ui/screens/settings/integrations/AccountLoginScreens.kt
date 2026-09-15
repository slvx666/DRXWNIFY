/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.settings.integrations

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.datastore.preferences.core.edit
import androidx.navigation.NavController
import com.metrolist.music.R
import com.metrolist.music.constants.MetadataSource
import com.metrolist.music.constants.PrimaryMetadataSourceKey
import com.metrolist.music.constants.SpotifyAccessTokenKey
import com.metrolist.music.constants.VkAccessTokenKey
import com.metrolist.music.constants.VkUserIdKey
import com.metrolist.music.constants.YandexAccessTokenKey
import com.metrolist.music.constants.YandexUidKey
import com.metrolist.music.constants.YandexUsernameKey
import com.metrolist.music.resolver.providers.VkAudioProvider
import com.metrolist.music.ui.component.IconButton
import com.metrolist.music.ui.component.TextFieldDialog
import com.metrolist.music.ui.utils.backToMain
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.get
import com.metrolist.yandex.YandexMusic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean

/** Connects a Yandex Music account (metadata + library source) through Yandex's OAuth page. */
@Composable
fun YandexLoginScreen(navController: NavController) {
    val context = LocalContext.current
    OAuthTokenLoginScreen(
        navController = navController,
        title = stringResource(R.string.yandex_login),
        startUrl = YandexMusic.OAUTH_URL,
        onToken = { params ->
            val token = params["access_token"] ?: return@OAuthTokenLoginScreen "no token"
            YandexMusic.accessToken = token
            YandexMusic.uid = null
            YandexMusic.account().fold(
                onSuccess = { account ->
                    context.dataStore.edit { prefs ->
                        prefs[YandexAccessTokenKey] = token
                        prefs[YandexUidKey] = account.uid
                        prefs[YandexUsernameKey] = account.displayName ?: account.login ?: account.uid
                        // First account linked → it owns the library.
                        if (prefs[SpotifyAccessTokenKey].isNullOrEmpty()) {
                            prefs[PrimaryMetadataSourceKey] = MetadataSource.YANDEX.name
                        }
                    }
                    null
                },
                onFailure = { e ->
                    YandexMusic.accessToken = context.dataStore.get(YandexAccessTokenKey, "").ifBlank { null }
                    Timber.w(e, "Yandex login: token rejected")
                    e.message ?: e.javaClass.simpleName
                },
            )
        },
    )
}

/** Connects VK so VK Music can serve as an audio fallback (the audio API requires a user token). */
@Composable
fun VkLoginScreen(navController: NavController) {
    val context = LocalContext.current
    OAuthTokenLoginScreen(
        navController = navController,
        title = stringResource(R.string.vk_login),
        startUrl = VkAudioProvider.OAUTH_URL,
        note = stringResource(R.string.vk_privacy_note),
        onToken = { params ->
            val token = params["access_token"] ?: return@OAuthTokenLoginScreen "no token"
            context.dataStore.edit { prefs ->
                prefs[VkAccessTokenKey] = token
                params["user_id"]?.let { prefs[VkUserIdKey] = it }
            }
            null
        },
    )
}

/**
 * Generic implicit-grant OAuth login: shows the provider's login page and captures the token from the
 * redirect fragment (`#access_token=…`). A "paste token" action covers devices where the web login
 * is blocked. [onToken] returns null on success or an error message.
 */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun OAuthTokenLoginScreen(
    navController: NavController,
    title: String,
    startUrl: String,
    note: String? = null,
    onToken: suspend (Map<String, String>) -> String?,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var processing by remember { mutableStateOf(false) }
    var showPasteDialog by remember { mutableStateOf(false) }
    val handled = remember { AtomicBoolean(false) }

    fun finish(params: Map<String, String>) {
        if (!handled.compareAndSet(false, true)) return
        processing = true
        scope.launch(Dispatchers.IO) {
            val error = runCatching { onToken(params) }.getOrElse { it.message ?: "error" }
            withContext(Dispatchers.Main) {
                processing = false
                if (error == null) {
                    Toast.makeText(context, context.getString(R.string.login_connected), Toast.LENGTH_SHORT).show()
                    navController.navigateUp()
                } else {
                    handled.set(false)
                    Toast.makeText(context, context.getString(R.string.login_failed_reason, error), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    fun tokenParams(url: String?): Map<String, String>? {
        val fragment = url?.substringAfter('#', "")?.takeIf { it.contains("access_token=") } ?: return null
        return fragment.split('&').mapNotNull { part ->
            val k = part.substringBefore('=', "")
            val v = part.substringAfter('=', "")
            if (k.isBlank()) null else k to java.net.URLDecoder.decode(v, "UTF-8")
        }.toMap()
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(title) },
            navigationIcon = {
                IconButton(onClick = navController::navigateUp, onLongClick = navController::backToMain) {
                    Icon(painterResource(R.drawable.arrow_back), contentDescription = null)
                }
            },
            actions = {
                TextButton(onClick = { showPasteDialog = true }) {
                    Text(stringResource(R.string.login_paste_token))
                }
            },
        )
        if (note != null) {
            Text(
                text = note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        if (loading || processing) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    CookieManager.getInstance().setAcceptCookie(true)
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                val params = tokenParams(request?.url?.toString()) ?: return false
                                finish(params)
                                return true
                            }

                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                loading = true
                                tokenParams(url)?.let {
                                    view?.stopLoading()
                                    finish(it)
                                }
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                loading = false
                                tokenParams(url)?.let(::finish)
                            }
                        }
                        loadUrl(startUrl)
                    }
                },
            )
        }
    }

    if (showPasteDialog) {
        TextFieldDialog(
            title = { Text(stringResource(R.string.login_paste_token)) },
            placeholder = { Text(stringResource(R.string.login_token_hint)) },
            onDone = { value ->
                val trimmed = value.trim()
                // Accept a bare token or a whole redirect url.
                val asUrl = when {
                    !trimmed.contains("access_token=") -> "#access_token=$trimmed"
                    trimmed.contains('#') -> trimmed
                    trimmed.contains('?') -> trimmed.replaceFirst('?', '#')
                    else -> "#$trimmed"
                }
                val params = tokenParams(asUrl)
                if (params != null) finish(params)
            },
            onDismiss = { showPasteDialog = false },
        )
    }
}
