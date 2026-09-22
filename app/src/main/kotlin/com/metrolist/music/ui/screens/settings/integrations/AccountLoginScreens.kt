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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import coil3.compose.AsyncImage
import com.metrolist.music.resolver.providers.VkDirectAuth
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
import androidx.core.net.toUri
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
import com.metrolist.music.resolver.providers.VkAudioAccess
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
        note = stringResource(R.string.yandex_vpn_hint),
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

/**
 * Connects VK so VK Music can serve as an audio fallback (the audio API requires a user token).
 *
 * A native form is the default: typing into VK's own page inside the embedded browser inserts the
 * characters in reverse order on some phones. The page login stays available as an alternative.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VkLoginScreen(navController: NavController) {
    val context = LocalContext.current
    var useWebLogin by rememberSaveable { mutableStateOf(false) }

    suspend fun saveToken(token: String, userId: String?) {
        context.dataStore.edit { prefs ->
            prefs[VkAccessTokenKey] = token
            userId?.let { prefs[VkUserIdKey] = it }
        }
        // A token is not the same as music access: VK serves audio.* only to some of them, and the
        // difference shows up as "VK finds nothing" much later. Ask VK right away and say so.
        when (val access = VkAudioProvider.checkAudioAccess(token)) {
            is VkAudioAccess.Ok -> Unit
            is VkAudioAccess.NoToken -> Unit
            is VkAudioAccess.Failed -> Toast.makeText(
                context,
                context.getString(
                    R.string.vk_check_audio_failed,
                    access.code?.toString() ?: "—",
                    access.message,
                ) + "\n" + context.getString(R.string.vk_check_audio_relogin),
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    if (useWebLogin) {
        OAuthTokenLoginScreen(
            navController = navController,
            title = stringResource(R.string.vk_login),
            startUrl = VkAudioProvider.OAUTH_URL,
            note = stringResource(R.string.vk_privacy_note),
            onToken = { params ->
                val token = params["access_token"] ?: return@OAuthTokenLoginScreen "no token"
                saveToken(token, params["user_id"])
                null
            },
        )
        return
    }

    val scope = rememberCoroutineScope()
    var login by rememberSaveable { mutableStateOf("") }
    // Deliberately not saveable: the password never goes into saved instance state.
    var password by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var captchaKey by remember { mutableStateOf("") }
    var needCode by remember { mutableStateOf<VkDirectAuth.Result.NeedCode?>(null) }
    var captcha by remember { mutableStateOf<VkDirectAuth.Result.NeedCaptcha?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun submit() {
        if (busy || login.isBlank() || password.isEmpty()) return
        busy = true
        error = null
        scope.launch {
            val result = VkDirectAuth.login(
                username = login,
                password = password,
                code = code.takeIf { needCode != null },
                captchaSid = captcha?.captchaSid,
                captchaKey = captchaKey.takeIf { captcha != null },
            )
            when (result) {
                is VkDirectAuth.Result.Success -> {
                    saveToken(result.token, result.userId)
                    password = ""
                    Toast.makeText(context, context.getString(R.string.login_connected), Toast.LENGTH_SHORT).show()
                    navController.navigateUp()
                }
                is VkDirectAuth.Result.NeedCode -> {
                    needCode = result
                    captcha = null
                }
                is VkDirectAuth.Result.NeedCaptcha -> {
                    captcha = result
                    captchaKey = ""
                }
                is VkDirectAuth.Result.Error -> error = result.message
            }
            busy = false
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.vk_login)) },
            navigationIcon = {
                IconButton(onClick = navController::navigateUp, onLongClick = navController::backToMain) {
                    Icon(painterResource(R.drawable.arrow_back), contentDescription = null)
                }
            },
        )
        Column(
            Modifier
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(
                text = stringResource(R.string.vk_password_note) + "\n" + stringResource(R.string.vk_privacy_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = login,
                onValueChange = { login = it },
                label = { Text(stringResource(R.string.vk_login_phone_or_email)) },
                singleLine = true,
                enabled = !busy,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text(stringResource(R.string.password)) },
                singleLine = true,
                enabled = !busy,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = if (needCode == null && captcha == null) ImeAction.Done else ImeAction.Next,
                ),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                modifier = Modifier.fillMaxWidth(),
            )

            needCode?.let { request ->
                Spacer(Modifier.height(12.dp))
                Text(
                    text = if (request.viaApp) {
                        stringResource(R.string.vk_code_hint_app)
                    } else {
                        stringResource(R.string.vk_code_hint_sms, request.phoneMask.orEmpty())
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it },
                    label = { Text(stringResource(R.string.vk_code)) },
                    singleLine = true,
                    enabled = !busy,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            captcha?.let { request ->
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.vk_captcha_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                AsyncImage(
                    model = request.imageUrl,
                    contentDescription = null,
                    modifier = Modifier
                        .height(56.dp)
                        .fillMaxWidth(),
                )
                OutlinedTextField(
                    value = captchaKey,
                    onValueChange = { captchaKey = it },
                    singleLine = true,
                    enabled = !busy,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            error?.let {
                Spacer(Modifier.height(12.dp))
                Text(text = it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }

            Spacer(Modifier.height(16.dp))
            Button(
                onClick = ::submit,
                enabled = !busy && login.isNotBlank() && password.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Text(stringResource(R.string.action_login))
                }
            }
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = { useWebLogin = true }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.vk_login_via_page))
            }
        }
    }
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
    var loadFailed by remember { mutableStateOf(false) }
    var webView by remember { mutableStateOf<WebView?>(null) }
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
                // Escape hatch: some keyboards misbehave inside an embedded WebView, so the same
                // login can be done in the real browser and the token pasted back here.
                IconButton(
                    onClick = {
                        runCatching {
                            context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, startUrl.toUri()))
                        }
                    },
                    onLongClick = {},
                ) {
                    Icon(painterResource(R.drawable.language), contentDescription = stringResource(R.string.login_open_in_browser))
                }
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
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    CookieManager.getInstance().setAcceptCookie(true)
                    WebView(ctx).also { webView = it }.apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        // Without an explicit focus the page's inputs can end up sharing focus with
                        // the Compose host, which made typed characters land in reverse order.
                        isFocusable = true
                        isFocusableInTouchMode = true
                        setOnTouchListener { v, event ->
                            if (event.action == android.view.MotionEvent.ACTION_DOWN ||
                                event.action == android.view.MotionEvent.ACTION_UP
                            ) {
                                if (!v.hasFocus()) v.requestFocus()
                            }
                            false
                        }
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                val params = tokenParams(request?.url?.toString()) ?: return false
                                finish(params)
                                return true
                            }

                            override fun onReceivedError(
                                view: WebView?,
                                request: WebResourceRequest?,
                                error: android.webkit.WebResourceError?,
                            ) {
                                // Only the login page itself matters; a failing tracker or font is
                                // not worth a warning.
                                if (request?.isForMainFrame == true) {
                                    loading = false
                                    loadFailed = true
                                }
                            }

                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                loading = true
                                loadFailed = false
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
                        requestFocus()
                    }
                },
            )
            // Overlay, so the page never gets re-laid out mid-typing when loading toggles.
            if (loading || processing) {
                LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
            }
            // The usual reason a login page refuses to load is a VPN the service doesn't like.
            if (loadFailed) {
                androidx.compose.material3.Card(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp),
                ) {
                    Column(Modifier.padding(20.dp)) {
                        Text(
                            text = stringResource(R.string.login_page_failed),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = note ?: stringResource(R.string.login_page_failed_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(12.dp))
                        TextButton(
                            onClick = {
                                loadFailed = false
                                webView?.loadUrl(startUrl)
                            },
                        ) {
                            Text(stringResource(R.string.retry))
                        }
                    }
                }
            }
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
