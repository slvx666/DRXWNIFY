/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.resolver.providers

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * VK login with a native form (login + password, then an SMS/app code or a captcha when VK asks
 * for one). The credentials go straight to oauth.vk.com over HTTPS - the same request the Kate
 * Mobile client makes - and are never stored: only the resulting token is kept.
 *
 * It exists because typing into VK's own login page inside the embedded browser is broken on some
 * phones (characters are inserted in reverse order).
 */
object VkDirectAuth {

    sealed interface Result {
        data class Success(val token: String, val userId: String?) : Result

        /**
         * VK wants a confirmation code (SMS or the authenticator app); repeat with [code]. VK does NOT
         * send the SMS by itself: it goes out only after [requestSms] with [validationSid].
         */
        data class NeedCode(val viaApp: Boolean, val phoneMask: String?, val validationSid: String? = null) : Result

        /** VK wants a captcha solved; repeat with [captchaSid] and the text from [imageUrl]. */
        data class NeedCaptcha(val captchaSid: String, val imageUrl: String) : Result

        data class Error(val message: String) : Result
    }

    // VK's official Android client (see VkAudioProvider.OAUTH_CLIENT_ID for why not Kate).
    private const val CLIENT_SECRET = "hHbZxrka2uZ6jB1inYsH"

    /** One id per install, like a real device: VK ties the session to it. */
    private val deviceId: String by lazy {
        (1..16).map { "0123456789abcdef".random() }.joinToString("")
    }

    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .dns(com.metrolist.music.utils.FallbackDns)
            .build()
    }

    /**
     * Asks VK to send the login code by SMS (also instead of the authenticator app). Null when VK
     * accepted, else what it answered.
     */
    suspend fun requestSms(validationSid: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val body = FormBody.Builder()
                .add("sid", validationSid)
                .add("api_id", VkAudioProvider.OAUTH_CLIENT_ID)
                .add("client_id", VkAudioProvider.OAUTH_CLIENT_ID)
                .add("client_secret", CLIENT_SECRET)
                .add("v", VkAudioProvider.API_VERSION)
                .add("lang", "ru")
                .add("device_id", deviceId)
                .build()
            val request = Request.Builder()
                .url("https://api.vk.com/method/auth.validatePhone")
                .header("User-Agent", VkAudioProvider.CLIENT_USER_AGENT)
                .post(body)
                .build()
            http.newCall(request).execute().use { response ->
                val json = runCatching { JSONObject(response.body?.string().orEmpty()) }.getOrNull()
                    ?: return@use "Unexpected response from VK"
                val error = json.optJSONObject("error")
                timber.log.Timber.tag("VkAuth").i("validatePhone: %s", error?.optString("error_msg") ?: "sent")
                error?.optString("error_msg")?.takeIf { it.isNotBlank() }
            }
        }.getOrElse { it.message ?: it.javaClass.simpleName }
    }

    suspend fun login(
        username: String,
        password: String,
        code: String? = null,
        captchaSid: String? = null,
        captchaKey: String? = null,
    ): Result = withContext(Dispatchers.IO) {
        runCatching {
            val body = FormBody.Builder()
                .add("grant_type", "password")
                .add("client_id", VkAudioProvider.OAUTH_CLIENT_ID)
                .add("client_secret", CLIENT_SECRET)
                .add("username", username.trim())
                .add("password", password)
                .add("scope", "all")
                .add("2fa_supported", "1")
                .add("v", VkAudioProvider.API_VERSION)
                .add("lang", "ru")
                .add("device_id", deviceId)
                .apply {
                    if (!code.isNullOrBlank()) add("code", code.trim())
                    if (!captchaSid.isNullOrBlank() && !captchaKey.isNullOrBlank()) {
                        add("captcha_sid", captchaSid)
                        add("captcha_key", captchaKey.trim())
                    }
                }
                .build()
            val request = Request.Builder()
                .url("https://oauth.vk.com/token")
                .header("User-Agent", VkAudioProvider.CLIENT_USER_AGENT)
                .post(body)
                .build()
            http.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                parse(text)
            }
        }.getOrElse { e ->
            Result.Error(
                if (e is java.net.UnknownHostException) {
                    "Нет связи с VK (не удалось найти oauth.vk.com). Проверь интернет или VPN и попробуй ещё раз."
                } else {
                    e.message ?: e.javaClass.simpleName
                },
            )
        }
    }

    internal fun parse(text: String): Result {
        val json = runCatching { JSONObject(text) }.getOrNull()
            ?: return Result.Error("Unexpected response from VK")
        val token = json.optString("access_token")
        // Outcome only — never the token or anything typed.
        timber.log.Timber.tag("VkAuth").i(
            "login answer: %s",
            if (token.isNotBlank()) "token issued" else "${json.optString("error")} ${json.optString("error_description")}",
        )
        if (token.isNotBlank()) {
            return Result.Success(token, json.optString("user_id").takeIf { it.isNotBlank() })
        }
        return when (json.optString("error")) {
            "need_validation" -> Result.NeedCode(
                viaApp = json.optString("validation_type") == "2fa_app",
                phoneMask = json.optString("phone_mask").takeIf { it.isNotBlank() },
                validationSid = json.optString("validation_sid").takeIf { it.isNotBlank() },
            )
            "need_captcha" -> Result.NeedCaptcha(
                captchaSid = json.optString("captcha_sid"),
                imageUrl = json.optString("captcha_img"),
            )
            else -> Result.Error(
                json.optString("error_description").takeIf { it.isNotBlank() }
                    ?: json.optString("error").takeIf { it.isNotBlank() }
                    ?: "VK login failed",
            )
        }
    }
}
