/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.settings.integrations

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.autofill.ContentType
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.style.TextOverflow
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.ui.theme.AppThemePreset
import com.metrolist.music.ui.theme.LocalAppThemePreset
import kotlin.math.roundToInt
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
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
    // Read in onToken, used once the login went through (a re-login keeps everything as it was).
    var yandexRelogin by remember { mutableStateOf(false) }
    var yandexMainBefore by remember { mutableStateOf<MetadataSource?>(null) }
    OAuthTokenLoginScreen(
        navController = navController,
        title = stringResource(R.string.yandex_login),
        startUrl = YandexMusic.OAUTH_URL,
        note = stringResource(R.string.yandex_several_accounts_hint),
        vpnWarning = stringResource(R.string.yandex_vpn_on),
        vpnFailure = stringResource(R.string.yandex_vpn_turn_off),
        pageScript = yandexPageScript(),
        confirmPath = "/authorize/allow",
        onConnected = { nav -> nav.afterAccountLinked(MetadataSource.YANDEX, yandexRelogin, yandexMainBefore) },
        onToken = { params ->
            val token = params["access_token"] ?: return@OAuthTokenLoginScreen "no token"
            yandexRelogin = context.dataStore.get(YandexAccessTokenKey, "").isNotEmpty()
            yandexMainBefore = com.metrolist.music.catalog.Catalog.source
            YandexMusic.accessToken = token
            YandexMusic.uid = null
            YandexMusic.account().fold(
                onSuccess = { account ->
                    context.dataStore.edit { prefs ->
                        prefs[YandexAccessTokenKey] = token
                        prefs[YandexUidKey] = account.uid
                        prefs[YandexUsernameKey] = account.displayName ?: account.login ?: account.uid
                    }
                    null
                },
                onFailure = { e ->
                    YandexMusic.accessToken = context.dataStore.get(YandexAccessTokenKey, "").ifBlank { null }
                    Timber.w(e, "Yandex login: token rejected")
                    if (isVpnActive(context)) {
                        context.getString(R.string.yandex_vpn_login_failed)
                    } else {
                        e.message ?: e.javaClass.simpleName
                    }
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

    var vkRelogin by remember { mutableStateOf(false) }
    var vkMainBefore by remember { mutableStateOf<MetadataSource?>(null) }

    suspend fun saveToken(token: String, userId: String?) {
        vkRelogin = context.dataStore.get(VkAccessTokenKey, "").isNotEmpty()
        vkMainBefore = com.metrolist.music.catalog.Catalog.source
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
            onConnected = { nav -> nav.afterAccountLinked(MetadataSource.VK, vkRelogin, vkMainBefore) },
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

    var captchaPage by remember { mutableStateOf<VkDirectAuth.Result.NeedCaptcha?>(null) }

    fun submit(captchaToken: String? = null) {
        if (busy || !loginReady || password.isEmpty()) return
        busy = true
        error = null
        scope.launch {
            val answered = captcha
            val result = VkDirectAuth.login(
                username = login,
                password = password,
                code = code.takeIf { needCode != null },
                captchaSid = answered?.captchaSid?.takeIf { answered.redirectUri == null },
                captchaKey = captchaKey.takeIf { answered != null && answered.redirectUri == null },
                captchaSuccessToken = captchaToken,
                captchaFor = answered.takeIf { captchaToken != null },
            )
            when (result) {
                is VkDirectAuth.Result.Success -> {
                    saveToken(result.token, result.userId)
                    loginPrefs.edit().putString("login", if (byPhone) phone.text else email.trim()).putBoolean("byPhone", byPhone).apply()
                    // Hands login + password to the password manager ("Save password?").
                    runCatching { autofill?.commit() }
                    password = ""
                    Toast.makeText(context, context.getString(R.string.login_connected), Toast.LENGTH_SHORT).show()
                    navController.afterAccountLinked(MetadataSource.VK, vkRelogin, vkMainBefore)
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
                    // The new captcha is a page to pass, not a picture to read: it opens by itself.
                    if (result.redirectUri != null) {
                        if (captchaToken != null) error = context.getString(R.string.vk_captcha_again)
                        captchaPage = result
                    }
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
                    TextButton(enabled = !busy && wait == 0L, onClick = { sendSms(sid) }, shape = com.metrolist.music.ui.theme.tuiButtonShape()) {
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

            captchaPage?.redirectUri?.let { page ->
                VkCaptchaDialog(
                    url = page,
                    onToken = { token ->
                        captchaPage = null
                        submit(token)
                    },
                    onDismiss = { captchaPage = null },
                )
            }

            captcha?.takeIf { it.redirectUri != null }?.let { request ->
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = { captchaPage = request },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                    shape = com.metrolist.music.ui.theme.tuiButtonShape(),
                ) { Text(stringResource(R.string.vk_captcha_open)) }
            }

            captcha?.takeIf { it.redirectUri == null }?.let { request ->
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
                onClick = { submit() },
                enabled = !busy && loginReady && password.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
                shape = com.metrolist.music.ui.theme.tuiButtonShape(),
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
            TextButton(onClick = { showPasteToken = true }, modifier = Modifier.fillMaxWidth(), shape = com.metrolist.music.ui.theme.tuiButtonShape()) {
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
                        navController.afterAccountLinked(MetadataSource.VK, vkRelogin, vkMainBefore)
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
 *
 * For a provider that turns VPN users away, [vpnWarning] replaces [note] while a VPN is on and
 * [vpnFailure] explains a page that would not load. [pageScript] restyles the provider's page (run
 * on every page it loads, so it has to be idempotent).
 * [confirmPath] is where the provider's "allow" form posts: from that tap on, a "connecting" screen
 * covers the page.
 */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun OAuthTokenLoginScreen(
    navController: NavController,
    title: String,
    startUrl: String,
    note: String? = null,
    vpnWarning: String? = null,
    vpnFailure: String? = null,
    pageScript: String? = null,
    confirmPath: String? = null,
    /** After a successful login: back by default; the account's setup for a first link. */
    onConnected: (NavController) -> Unit = { it.navigateUp() },
    onToken: suspend (Map<String, String>) -> String?,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var processing by remember { mutableStateOf(false) }
    var showPasteDialog by remember { mutableStateOf(false) }
    var loadFailed by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    val handled = remember { AtomicBoolean(false) }
    val pageBackground = MaterialTheme.colorScheme.background.toArgb()
    // Run on every page: the provider's restyle, and a note to the console when the confirm form is
    // sent, which the chrome client below turns into the "connecting" screen.
    val script = remember(pageScript, confirmPath) {
        buildString {
            pageScript?.let(::append)
            confirmPath?.let { path ->
                append("if(!window.__drxConfirm){window.__drxConfirm=1;document.addEventListener('submit',function(e){")
                append("var a=(e.target&&e.target.action)||'';if(a.indexOf(").append(org.json.JSONObject.quote(path))
                append(")>=0)console.log('").append(CONFIRM_SIGNAL).append("');},true);}")
            }
        }.ifEmpty { null }
    }

    // Followed live: turning the VPN off clears the warning and reloads a page that failed through it.
    var vpnOn by remember { mutableStateOf(isVpnActive(context)) }
    if (vpnWarning != null || vpnFailure != null) {
        DisposableEffect(Unit) {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    vpnOn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                }

                override fun onLost(network: Network) {
                    vpnOn = isVpnActive(context)
                }
            }
            val registered = runCatching {
                cm?.registerDefaultNetworkCallback(callback, Handler(Looper.getMainLooper()))
            }.isSuccess
            onDispose { if (registered) runCatching { cm?.unregisterNetworkCallback(callback) } }
        }
        LaunchedEffect(vpnOn) {
            if (!vpnOn && loadFailed) {
                loadFailed = false
                webView?.loadUrl(startUrl)
            }
        }
    }

    // From the tap on the provider's confirm button until the account is saved. Yandex takes a few
    // seconds to answer and then shows a blank page of its own: with nothing on screen meanwhile the
    // button got tapped twice. A confirm that never leads to a token gives the page back.
    var confirming by remember { mutableStateOf(false) }
    LaunchedEffect(confirming) {
        if (confirming) {
            kotlinx.coroutines.delay(CONFIRM_TIMEOUT_MS)
            if (!handled.get()) confirming = false
        }
    }

    fun finish(params: Map<String, String>) {
        if (!handled.compareAndSet(false, true)) return
        processing = true
        scope.launch(Dispatchers.IO) {
            val error = runCatching { onToken(params) }.getOrElse { it.message ?: "error" }
            withContext(Dispatchers.Main) {
                if (error == null) {
                    // The cover stays up while the screen leaves: under it is Yandex's blank page.
                    Toast.makeText(context, context.getString(R.string.login_connected), Toast.LENGTH_SHORT).show()
                    onConnected(navController)
                } else {
                    processing = false
                    confirming = false
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
            title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
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
                // Start over with no remembered account: the page otherwise keeps offering the one
                // signed in last (one phone number can have several accounts).
                IconButton(
                    onClick = {
                        CookieManager.getInstance().removeAllCookies {
                            webView?.clearCache(true)
                            webView?.loadUrl(startUrl)
                        }
                        android.webkit.WebStorage.getInstance().deleteAllData()
                    },
                    onLongClick = {},
                ) {
                    Icon(painterResource(R.drawable.sync), contentDescription = stringResource(R.string.login_switch_account))
                }
                IconButton(onClick = { showPasteDialog = true }, onLongClick = {}) {
                    Icon(painterResource(R.drawable.content_paste), contentDescription = stringResource(R.string.login_paste_token))
                }
            },
        )
        val shownNote = if (vpnOn && vpnWarning != null) vpnWarning else note
        if (shownNote != null) {
            Text(
                text = shownNote,
                style = MaterialTheme.typography.bodySmall,
                color = if (shownNote == vpnWarning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        // Ends above the mini player: the page's own bottom sheets open at the bottom edge.
        Box(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Bottom)),
            contentAlignment = Alignment.Center,
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    // Debug builds only: lets chrome://inspect look into the login page.
                    WebView.setWebContentsDebuggingEnabled(com.metrolist.music.BuildConfig.DEBUG)
                    CookieManager.getInstance().setAcceptCookie(true)
                    WebView(ctx).also { webView = it }.apply {
                        // AndroidView adds the view as wrap_content, and a wrap_content WebView lays the
                        // page out with a zero-height viewport: 100vh came out as 0, so Yandex's bottom
                        // sheets ("send the code again", "the account isn't here", "More") were drawn
                        // above the top edge and only flashed by.
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                        setBackgroundColor(pageBackground)
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.javaScriptCanOpenWindowsAutomatically = true
                        // Yandex signs in across its domains (passport, sso, oauth): without third-party
                        // cookies the account chooser's "the one I need isn't here" step failed with a
                        // flash of "contact support".
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        // Some login pages cut flows short for an embedded browser ("; wv" in the agent).
                        settings.userAgentString = settings.userAgentString.replace("; wv", "")
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
                            var mainFrameHost: String? = null

                            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                val url = request?.url ?: return false
                                tokenParams(url.toString())?.let {
                                    finish(it)
                                    return true
                                }
                                if (url.scheme == "http" || url.scheme == "https") return false
                                // An app link (intent://, yandexauth://, market://…): the WebView can't open
                                // it and showed an error page. Use the page's web fallback, else hand it
                                // to the app it is for.
                                if (url.scheme == "intent") {
                                    val intent = runCatching {
                                        android.content.Intent.parseUri(url.toString(), android.content.Intent.URI_INTENT_SCHEME)
                                    }.getOrNull()
                                    intent?.getStringExtra("browser_fallback_url")?.let {
                                        view?.loadUrl(it)
                                        return true
                                    }
                                    return true
                                }
                                runCatching {
                                    context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, url))
                                }
                                return true
                            }

                            fun failed(reason: String?) {
                                loading = false
                                loadError = reason
                                vpnOn = isVpnActive(context)
                                confirming = false
                                loadFailed = true
                            }

                            override fun onReceivedError(
                                view: WebView?,
                                request: WebResourceRequest?,
                                error: android.webkit.WebResourceError?,
                            ) {
                                // Only the login page itself matters; a failing tracker or font is
                                // not worth a warning.
                                if (request?.isForMainFrame == true) failed(error?.description?.toString())
                            }

                            // A VPN exit the service has blocked often answers with an error status
                            // (403/451/5xx) instead of failing the connection.
                            override fun onReceivedHttpError(
                                view: WebView?,
                                request: WebResourceRequest?,
                                errorResponse: WebResourceResponse?,
                            ) {
                                val status = errorResponse?.statusCode ?: return
                                if (request?.isForMainFrame == true && status >= 400) failed("HTTP $status")
                            }

                            // Never proceeds past a bad certificate. This fires for every resource, and
                            // some of Yandex's side resources carry certificates Android doesn't trust;
                            // only the page itself failing is worth the card.
                            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                                handler?.cancel()
                                Timber.w("Login page: SSL error %s for %s", error?.primaryError, error?.url)
                                val host = error?.url?.toUri()?.host
                                if (host != null && host == mainFrameHost) failed("SSL ${error.primaryError}")
                            }

                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                mainFrameHost = url?.toUri()?.host
                                loading = true
                                loadFailed = false
                                loadError = null
                                if (confirmPath != null && url?.contains(confirmPath) == true) confirming = true
                                tokenParams(url)?.let {
                                    view?.stopLoading()
                                    finish(it)
                                }
                            }

                            override fun onPageCommitVisible(view: WebView?, url: String?) {
                                script?.let { view?.evaluateJavascript(it, null) }
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                loading = false
                                script?.let { view?.evaluateJavascript(it, null) }
                                tokenParams(url)?.let(::finish)
                            }
                        }
                        if (confirmPath != null) {
                            webChromeClient = object : android.webkit.WebChromeClient() {
                                override fun onConsoleMessage(message: android.webkit.ConsoleMessage?): Boolean {
                                    if (message?.message() == CONFIRM_SIGNAL) confirming = true
                                    return false
                                }
                            }
                        }
                        loadUrl(startUrl)
                        requestFocus()
                    }
                },
            )
            // Overlay, so the page never gets re-laid out mid-typing when loading toggles.
            if (loading) {
                LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
            }
            // Covers the page (and takes its taps) while the account is being connected.
            if (confirming || processing) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(16.dp))
                        Text(
                            text = stringResource(R.string.login_connecting),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
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
                            text = when {
                                vpnOn && vpnFailure != null -> vpnFailure
                                vpnFailure != null -> stringResource(R.string.login_page_failed_hint) + "\n" +
                                    stringResource(R.string.yandex_vpn_hint)
                                else -> note ?: stringResource(R.string.login_page_failed_hint)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (vpnOn && vpnFailure != null) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                        loadError?.let {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = stringResource(R.string.login_page_error_detail, it),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        TextButton(
                            onClick = {
                                loadFailed = false
                                webView?.loadUrl(startUrl)
                            },
                            shape = com.metrolist.music.ui.theme.tuiButtonShape(),
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

/** Logged by the page script when the provider's confirm form is sent. */
private const val CONFIRM_SIGNAL = "__drx_confirm__"

/** How long the "connecting" screen waits for a token before handing the page back. */
private const val CONFIRM_TIMEOUT_MS = 20_000L

/** Whether this app's traffic currently goes through a VPN. */
private fun isVpnActive(context: Context): Boolean {
    val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
    return runCatching {
        cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
    }.getOrDefault(false)
}

private fun Color.css(alpha: Float = this.alpha): String =
    "rgba(${(red * 255).roundToInt()},${(green * 255).roundToInt()},${(blue * 255).roundToInt()},$alpha)"

/** Script that puts [css] into the page as one style element (replaced, not added, on a re-run). */
private fun styleScript(css: String): String =
    "(function(){var s=document.getElementById('drx-theme');if(!s){s=document.createElement('style');" +
        "s.id='drx-theme';(document.head||document.documentElement).appendChild(s);}" +
        "s.textContent=${org.json.JSONObject.quote(css)};})();"

/**
 * Yandex ID's pages in the app's colours. With a dark app theme the page is first switched to Yandex's
 * own dark mode (`data-color`), which brings its light-on-dark icons; then [yandexPageCss] sets the
 * app's palette over either mode.
 */
@Composable
private fun yandexPageScript(): String {
    val css = yandexPageCss()
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    return remember(css, dark) {
        styleScript(css) + YANDEX_LOGIN_HINT_SCRIPT + if (dark) {
            "document.querySelectorAll('[data-theme][data-color=light]').forEach(function(e){e.setAttribute('data-color','dark')});"
        } else {
            ""
        }
    }
}

/**
 * Someone whose account isn't among those found by phone taps "Sign in another way" and lands back on
 * the phone page, where the way in by login hides behind a plain "More". From that tap on, "More"
 * glows and, in its sheet, "Sign in with login" stands out, until that is tapped. Keyed on Yandex's
 * own (Russian) labels and the button's test id; if those change, the page just looks as it does.
 */
private const val YANDEX_LOGIN_HINT_SCRIPT =
    "if(!window.__drxHint){window.__drxHint=1;(function(){var K='drx-login-hint';" +
        "function on(){try{return sessionStorage.getItem(K)==='1'}catch(e){return false}}" +
        "function set(v){try{if(v)sessionStorage.setItem(K,'1');else sessionStorage.removeItem(K)}catch(e){}}" +
        // Yandex's labels use non-breaking spaces.
        "function text(el){return (el&&el.innerText||'').replace(/\\s+/g,' ').trim()}" +
        "function mark(){var h=on();" +
        "var b=document.querySelector('[data-testid=\"split-add-user-more-button\"]');if(b)b.classList.toggle('drx-pulse',h);" +
        "document.querySelectorAll('[role=menuitem]').forEach(function(m){" +
        "m.classList.toggle('drx-pick',h&&text(m).indexOf('Войти по логину')===0)})}" +
        "var queued=false;function later(){if(queued)return;queued=true;requestAnimationFrame(function(){queued=false;mark()})}" +
        "document.addEventListener('pointerup',function(e){var el=e.target&&e.target.closest?" +
        "e.target.closest('button,a,[role=button],[role=menuitem]'):null;var t=text(el);" +
        "if(t.indexOf('Войти другим способом')===0)set(true);else if(t.indexOf('Войти по логину')===0)set(false);later()},true);" +
        "new MutationObserver(later).observe(document.documentElement,{childList:true,subtree:true});mark()})()}"

/**
 * Yandex ID's pages are built on `--id-color-*` design tokens (set per light/dark on
 * `[data-theme][data-color]`), so overriding those recolours every step, including the bottom sheets,
 * without depending on its generated class names. The "Yandex ID" logo and the promo footer go: the
 * top bar already says where the user is.
 */
@Composable
private fun yandexPageCss(): String {
    val cs = MaterialTheme.colorScheme
    val preset = LocalAppThemePreset.current
    return remember(cs, preset) {
        val tokens = mapOf(
            "surface-elevated-0" to cs.background.css(),
            "surface-elevated-100" to cs.surfaceContainerHigh.css(),
            "surface-submerged" to cs.surfaceContainerHigh.css(),
            "surface-overlay" to cs.scrim.css(0.6f),
            "surface-overlay-soft" to cs.onSurface.css(0.08f),
            "text-primary" to cs.onSurface.css(),
            "text-secondary" to cs.onSurfaceVariant.css(),
            "text-tertiary" to cs.outline.css(),
            "brand-bg-base" to cs.primary.css(),
            "brand-bg-hovered" to cs.primary.css(0.9f),
            "brand-bg-pressed" to cs.primary.css(0.8f),
            "brand-border" to cs.primary.css(),
            "brand-text-on" to cs.onPrimary.css(),
            "default-bg-base" to cs.onSurface.css(0.08f),
            "default-bg-hovered" to cs.onSurface.css(0.11f),
            "default-bg-pressed" to cs.onSurface.css(0.14f),
            "default-border" to cs.outline.css(),
            "line-normal" to cs.outlineVariant.css(),
            "link-normal" to cs.primary.css(),
            "link-normal-hovered" to cs.primary.css(0.8f),
            "muted-bg" to cs.onSurface.css(0.06f),
            "muted-border" to cs.outlineVariant.css(),
            "muted-text" to cs.onSurfaceVariant.css(0.6f),
            "status-negative" to cs.error.css(),
            "status-negative-soft" to cs.errorContainer.css(),
            "status-negative-soft-hovered" to cs.errorContainer.css(),
            "status-negative-soft-pressed" to cs.errorContainer.css(),
        )
        buildString {
            append("[data-theme][data-color]{")
            tokens.forEach { (name, value) -> append("--id-color-").append(name).append(':').append(value).append(" !important;") }
            if (preset == AppThemePreset.LINUX) append("--id-tray-border-radius:4px 4px 0 0 !important;")
            append("}")
            // Only pages built on those tokens: elsewhere the text would keep its own dark colour.
            append("html:has(>body[data-theme]),body[data-theme]{background:").append(cs.background.css()).append(" !important}")
            append(".auth-header-logo-wrapper{visibility:hidden !important}")
            append(".AuthPromo,.AuthFooter{display:none !important}")
            // The hint marks (see YANDEX_LOGIN_HINT_SCRIPT): a glowing "More", a picked "Sign in with login".
            append("@keyframes drx-glow{0%{box-shadow:0 0 0 0 ").append(cs.primary.css(0.7f))
            append("}70%{box-shadow:0 0 0 14px ").append(cs.primary.css(0f)).append("}100%{box-shadow:0 0 0 0 ")
            append(cs.primary.css(0f)).append("}}")
            append(".drx-pulse{background:").append(cs.primary.css()).append(" !important;border-color:")
            append(cs.primary.css()).append(" !important;animation:drx-glow 1.4s ease-out infinite}")
            append(".drx-pulse,.drx-pulse *{color:").append(cs.onPrimary.css()).append(" !important}")
            append(".drx-pick{background:").append(cs.primary.css(0.16f)).append(" !important;border-radius:12px}")
            append(".drx-pick,.drx-pick *{color:").append(cs.primary.css()).append(" !important;font-weight:600 !important}")
            // The "allow access" page (oauth.yandex.ru) has its own fixed colours rather than the tokens.
            append(".Authorize-card{background:").append(cs.surfaceContainer.css()).append(" !important}")
            append(".AuthorizeApp-title,.AuthorizeClient,.AuthorizeClient-title,.AuthorizeScopesInputs-title,")
            append(".AuthorizeApp-unverifiedTitle,.AuthorizeApp-unverifiedDescription{color:").append(cs.onSurface.css()).append(" !important}")
            append(".AuthorizeAccountLink{color:").append(cs.primary.css()).append(" !important}")
            append(".AuthorizeScopesInputs-icon_point{background:").append(cs.onSurfaceVariant.css()).append(" !important}")
            append(".Button2_view_contrast-action::before,.Button2_view_action::before{background-color:")
            append(cs.primary.css()).append(" !important}")
            append(".Button2_view_contrast-action .Button2-Text,.Button2_view_action .Button2-Text{color:")
            append(cs.onPrimary.css()).append(" !important}")
            when (preset) {
                AppThemePreset.LINUX -> append(
                    "*{font-family:monospace !important}" +
                        "button,input,[role=button],[role=menuitem],[role=menu]{border-radius:2px !important}",
                )
                AppThemePreset.GRUVBOX -> append("h1,h2,h3{font-family:monospace !important}")
                else -> Unit
            }
        }
    }
}
