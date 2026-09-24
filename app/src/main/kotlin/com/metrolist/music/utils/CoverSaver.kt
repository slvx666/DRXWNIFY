/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import com.metrolist.music.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * Saves a cover to the gallery (Pictures/Meld), in the largest size its service offers — the
 * artwork shown on screen is often a 300 px thumbnail of a much bigger original.
 */
object CoverSaver {
    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /** Outlives the dialog that started it (a dialog's own scope dies the moment it closes). */
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)

    fun saveInBackground(context: Context, url: String?, name: String) {
        val appContext = context.applicationContext
        scope.launch { save(appContext, url, name) }
    }

    suspend fun save(context: Context, url: String?, name: String) {
        val appContext = context.applicationContext
        val saved = withContext(Dispatchers.IO) {
            runCatching {
                require(!url.isNullOrBlank()) { "no cover" }
                val (bytes, mime) = download(largest(url))
                write(appContext, bytes, mime, name)
            }.onFailure { Timber.w(it, "cover not saved: %s", url) }.isSuccess
        }
        withContext(Dispatchers.Main) {
            Toast.makeText(
                appContext,
                appContext.getString(if (saved) R.string.cover_saved else R.string.cover_not_saved),
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    fun shareInBackground(context: Context, url: String?, name: String) {
        val appContext = context.applicationContext
        scope.launch { share(appContext, url, name) }
    }

    /** Sends the cover (as an image file, not a link) to another app. */
    suspend fun share(context: Context, url: String?, name: String) {
        val uri = withContext(Dispatchers.IO) {
            runCatching {
                require(!url.isNullOrBlank()) { "no cover" }
                val (bytes, mime) = download(largest(url))
                val dir = java.io.File(context.cacheDir, "covers").apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() }
                val file = java.io.File(dir, safeName(name) + if (mime == "image/png") ".png" else ".jpg")
                file.writeBytes(bytes)
                androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.FileProvider", file) to mime
            }.onFailure { Timber.w(it, "cover not shared: %s", url) }.getOrNull()
        }
        withContext(Dispatchers.Main) {
            if (uri == null) {
                Toast.makeText(context, context.getString(R.string.cover_not_saved), Toast.LENGTH_SHORT).show()
                return@withContext
            }
            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = uri.second
                putExtra(android.content.Intent.EXTRA_STREAM, uri.first)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            runCatching {
                context.startActivity(
                    android.content.Intent.createChooser(intent, null).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
    }

    private fun safeName(name: String): String =
        name.replace(Regex("[\\/:*?\"<>|]"), "_").take(120).ifBlank { "cover" }

    /** The biggest variant of [url] that its service serves under a predictable address. */
    fun largest(url: String): String = when {
        // Spotify: 300 px (…1e02…) / 64 px (…4851…) → 640 px (…b273…).
        "i.scdn.co/image/" in url ->
            url.replace("ab67616d00001e02", "ab67616d0000b273").replace("ab67616d00004851", "ab67616d0000b273")
        // Google/YouTube Music artwork carries its size in the url ("=w544-h544…").
        "googleusercontent.com" in url || "ggpht.com" in url ->
            url.replace(Regex("=w\\d+-h\\d+[^/]*$"), "=w1200-h1200-l90-rj")
        // YouTube video thumbnails.
        "i.ytimg.com/vi/" in url ->
            url.replace(Regex("/(hq|mq|sd)?default\\.jpg"), "/maxresdefault.jpg")
        else -> url
    }

    private fun download(url: String): Pair<ByteArray, String> {
        val request = Request.Builder().url(url).build()
        http.newCall(request).execute().use { r ->
            check(r.isSuccessful) { "HTTP ${r.code}" }
            val mime = r.header("Content-Type")?.substringBefore(';')?.trim()
                ?.takeIf { it.startsWith("image/") } ?: "image/jpeg"
            return (r.body?.bytes() ?: error("empty")) to mime
        }
    }

    private fun write(context: Context, bytes: ByteArray, mime: String, name: String) {
        val extension = when (mime) {
            "image/png" -> "png"
            "image/webp" -> "webp"
            else -> "jpg"
        }
        val fileName = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(120).ifBlank { "cover" } + ".$extension"
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Meld")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("no gallery entry")
        resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("cannot write")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        }
    }
}
