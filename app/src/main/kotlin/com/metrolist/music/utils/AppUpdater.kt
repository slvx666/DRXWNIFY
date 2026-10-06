/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import com.metrolist.music.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Updates the app from the author's GitHub releases: checks the latest release, downloads its APK
 * and hands it to the system installer. When that isn't possible (no APK attached, no network, the
 * repository isn't public) it sends the user to the releases page instead.
 *
 * An update installs over the current app only when both are signed with the same key, i.e. the
 * release APK must come from the same build setup as the installed one.
 */
object AppUpdater {
    const val REPO = "slvx666/DRXWNIFY"
    const val RELEASES_URL = "https://github.com/$REPO/releases"

    sealed interface State {
        data object Idle : State
        data object Checking : State
        data class UpToDate(val latest: String) : State
        data class Available(val version: String, val apkUrl: String?, val apkName: String?) : State
        data class Downloading(val version: String, val progress: Float) : State
        data class Failed(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    val currentVersion: String get() = BuildConfig.VERSION_NAME

    suspend fun check() {
        if (_state.value is State.Checking || _state.value is State.Downloading) return
        _state.value = State.Checking
        _state.value = withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder()
                    .url("https://api.github.com/repos/$REPO/releases/latest")
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "Drxwnify/${BuildConfig.VERSION_NAME}")
                    .build()
                http.newCall(request).execute().use { response ->
                    if (response.code == 404) {
                        return@runCatching State.Failed("no public release found")
                    }
                    if (!response.isSuccessful) error("GitHub HTTP ${response.code}")
                    val json = JSONObject(response.body?.string().orEmpty())
                    val version = json.optString("tag_name").removePrefix("v").ifBlank { json.optString("name") }
                    val assets = json.optJSONArray("assets")
                    var apkUrl: String? = null
                    var apkName: String? = null
                    if (assets != null) {
                        for (i in 0 until assets.length()) {
                            val asset = assets.getJSONObject(i)
                            val name = asset.optString("name")
                            if (name.endsWith(".apk", ignoreCase = true)) {
                                apkUrl = asset.optString("browser_download_url")
                                apkName = name
                                break
                            }
                        }
                    }
                    if (version.isNotBlank() && Updater.isUpdateAvailable(currentVersion, version)) {
                        State.Available(version, apkUrl, apkName)
                    } else {
                        State.UpToDate(version.ifBlank { currentVersion })
                    }
                }
            }.getOrElse { State.Failed(it.message ?: it.javaClass.simpleName) }
        }
    }

    /**
     * Downloads the APK of [available] and opens the system installer. Returns false when the user
     * must be sent to the releases page instead (no APK in the release, download failed).
     */
    suspend fun downloadAndInstall(context: Context, available: State.Available): Boolean {
        val url = available.apkUrl ?: return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            // The system asks once to allow installing apps from this app; the next tap installs.
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri())
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
            return true
        }
        _state.value = State.Downloading(available.version, 0f)
        val file = withContext(Dispatchers.IO) {
            runCatching {
                val dir = File(context.cacheDir, "updates").apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() }
                val target = File(dir, available.apkName ?: "update.apk")
                val request = Request.Builder().url(url).header("User-Agent", "Drxwnify/${BuildConfig.VERSION_NAME}").build()
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) error("HTTP ${response.code}")
                    val body = response.body ?: error("empty body")
                    val total = body.contentLength().takeIf { it > 0 }
                    body.byteStream().use { input ->
                        target.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var read: Int
                            var done = 0L
                            while (input.read(buffer).also { read = it } >= 0) {
                                output.write(buffer, 0, read)
                                done += read
                                if (total != null) _state.value = State.Downloading(available.version, done.toFloat() / total)
                            }
                        }
                    }
                }
                target
            }.onFailure { Timber.w(it, "Update download failed") }.getOrNull()
        }
        if (file == null) {
            _state.value = available
            return false
        }
        _state.value = available
        return runCatching {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.FileProvider", file)
            context.startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            true
        }.getOrElse { e ->
            Timber.w(e, "Could not open the installer")
            false
        }
    }

    fun openReleases(context: Context) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, RELEASES_URL.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
