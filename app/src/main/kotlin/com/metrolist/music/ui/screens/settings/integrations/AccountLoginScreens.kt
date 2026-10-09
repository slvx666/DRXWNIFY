/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.settings.integrations

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.autofill.ContentType
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
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.TextRange
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
        // The check runs on an IO thread, so the toast has to be handed back to the main one.
        val access = VkAudioProvider.checkAudioAccess(token)
        if (access is VkAudioAccess.Failed) {
            withContext(Dispatchers.Main) {
                Toast.makeText(
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
    // Most VK accounts sign in with a phone number, so that is the default way in; email/login
    // is one tap away.
    var byPhone by rememberSaveable { mutableStateOf(true) }
    var phone by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue("")) }
    var email by rememberSaveable { mutableStateOf("") }
    val login = if (byPhone) PhoneNumberInput.forLogin(phone.text) else email.trim()
    val loginReady = if (byPhone) PhoneNumberInput.isComplete(phone.text) else login.isNotBlank()
    // Deliberately not saveable: the password never goes into saved instance state.
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf("") }
    var captchaKey by remember { mutableStateOf("") }
    var needCode by remember { mutableStateOf<VkDirectAuth.Result.NeedCode?>(null) }
    var showPasteToken by remember { mutableStateOf(false) }
    var captcha by remember { mutableStateOf<VkDirectAuth.Result.NeedCaptcha?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var smsInfo by remember { mutableStateOf<String?>(null) }
    var smsSentAt by remember { mutableStateOf(0L) }
    val autofill = androidx.compose.ui.platform.LocalAutofillManager.current
    val loginPrefs = remember { context.getSharedPreferences("vk_login", android.content.Context.MODE_PRIVATE) }

    // The last login (never the password) is filled in again; the password comes from the phone's
    // password manager, which is also offered to save both after a successful login.
    LaunchedEffect(Unit) {
        val saved = loginPrefs.getString("login", null).orEmpty()
        if (saved.isNotEmpty() && phone.text.isEmpty() && email.isEmpty()) {
            if (loginPrefs.getBoolean("byPhone", true)) {
                val formatted = PhoneNumberInput.format(saved)
                phone = TextFieldValue(formatted, selection = TextRange(formatted.length))
            } else {
                byPhone = false
                email = saved
            }
        }
    }

    fun sendSms(sid: String) {
        smsSentAt = System.currentTimeMillis()
        scope.launch {
            val problem = VkDirectAuth.requestSms(sid)
            smsInfo = if (problem == null) context.getString(R.string.vk_sms_sent) else context.getString(R.string.vk_sms_failed, problem)
        }
    }

    fun submit() {
        if (busy || !loginReady || password.isEmpty()) return
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
                    loginPrefs.edit().putString("login", if (byPhone) phone.text else email.trim()).putBoolean("byPhone", byPhone).apply()
                    // Hands login + password to the password manager ("Save password?").
                    runCatching { autofill?.commit() }
                    password = ""
                    Toast.makeText(context, context.getString(R.string.login_connected), Toast.LENGTH_SHORT).show()
                    navController.navigateUp()
                }
                is VkDirectAuth.Result.NeedCode -> {
                    val first = needCode == null
                    needCode = result
                    captcha = null
                    // VK only sends the SMS when asked to; without this the code never arrived.
                    if (first && !result.viaApp) result.validationSid?.let(::sendSms)
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
            com.metrolist.music.utils.RemoteConfig.config.collectAsState().value.vkShared?.let { shared ->
                Text(
                    text = stringResource(R.string.vk_shared_in_use, shared.dailyTracks),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(8.dp))
            }
            Text(
                text = stringResource(R.string.vk_password_note) + "\n" + stringResource(R.string.vk_privacy_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = byPhone,
                    onClick = { byPhone = true },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    enabled = !busy,
                ) { Text(stringResource(R.string.vk_login_by_phone)) }
                SegmentedButton(
                    selected = !byPhone,
                    onClick = { byPhone = false },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    enabled = !busy,
                ) { Text(stringResource(R.string.vk_login_by_email)) }
            }
            Spacer(Modifier.height(8.dp))
            if (byPhone) {
                OutlinedTextField(
                    value = phone,
                    onValueChange = { typed ->
                        // Reformatted on every keystroke; the cursor stays at the end, which is
                        // where a phone number is typed and corrected.
                        val formatted = PhoneNumberInput.format(typed.text)
                        phone = TextFieldValue(formatted, selection = TextRange(formatted.length))
                    },
                    label = { Text(stringResource(R.string.vk_login_phone)) },
                    placeholder = { Text("+7 (999) 123-45-67") },
                    singleLine = true,
                    enabled = !busy,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Next),
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentType = ContentType.Username + ContentType.PhoneNumber },
                )
            } else {
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text(stringResource(R.string.vk_login_email)) },
                    singleLine = true,
                    enabled = !busy,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentType = ContentType.Username + ContentType.EmailAddress },
                )
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text(stringResource(R.string.password)) },
                singleLine = true,
                enabled = !busy,
                trailingIcon = {
                    IconButton(onClick = { passwordVisible = !passwordVisible }, onLongClick = {}) {
                        Icon(
                            painter = painterResource(if (passwordVisible) R.drawable.visibility_off else R.drawable.visibility),
                            contentDescription = stringResource(
                                if (passwordVisible) R.string.password_hide else R.string.password_show,
                            ),
                        )
                    }
                },
                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = if (needCode == null && captcha == null) ImeAction.Done else ImeAction.Next,
                ),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentType = ContentType.Password },
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
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentType = ContentType.SmsOtpCode },
                )
                smsInfo?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                request.validationSid?.let { sid ->
                    // A minute between requests, as VK itself allows.
                    var now by remember { mutableStateOf(System.currentTimeMillis()) }
                    LaunchedEffect(smsSentAt) {
                        while (System.currentTimeMillis() - smsSentAt < SMS_RESEND_MS) {
                            now = System.currentTimeMillis()
                            kotlinx.coroutines.delay(1_000L)
                        }
                        now = System.currentTimeMillis()
                    }
                    val wait = ((SMS_RESEND_MS - (now - smsSentAt)) / 1000).coerceAtLeast(0)
                    TextButton(enabled = !busy && wait == 0L, onClick = { sendSms(sid) }) {
                        Text(
                            when {
                                wait > 0 -> stringResource(R.string.vk_sms_resend_in, wait)
                                request.viaApp && smsSentAt == 0L -> stringResource(R.string.vk_sms_instead)
                                else -> stringResource(R.string.vk_sms_resend)
                            },
                        )
                    }
                }
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
                enabled = !busy && loginReady && password.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Text(stringResource(R.string.action_login))
                }
            }
            Spacer(Modifier.height(4.dp))
            // A token made elsewhere (by a tool that performs VK's full music-client handshake) is
            // the one sure way in while the in-app login cannot produce a music-capable token.
            TextButton(onClick = { showPasteToken = true }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.login_paste_token))
            }
            Text(
                text = stringResource(R.string.vk_paste_token_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (showPasteToken) {
        TextFieldDialog(
            title = { Text(stringResource(R.string.login_paste_token)) },
            placeholder = { Text(stringResource(R.string.login_token_hint)) },
            onDone = { value ->
                // Accepts a bare token or a whole redirect url with one in it.
                val pasted = value.trim().substringAfter("access_token=").substringBefore('&').trim()
                if (pasted.isNotEmpty()) {
                    scope.launch {
                        saveToken(pasted, null)
                        navController.navigateUp()
                    }
                }
            },
            onDismiss = { showPasteToken = false },
        )
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

private const val SMS_RESEND_MS = 60_000L
