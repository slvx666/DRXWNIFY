/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import okhttp3.Interceptor
import okhttp3.Response
import timber.log.Timber
import java.io.IOException

/**
 * Spotify's pictures (covers, artist photos) come as i.scdn.co links, a Fastly address that is
 * throttled in Russia without a VPN: the request doesn't fail, it hangs, and the picture stays a grey
 * circle (only ones already cached showed). Spotify serves the same pictures from its Akamai
 * address too, which answers there; that is asked first, the original link if it doesn't serve.
 * Only the request changes: what is cached stays under the original link.
 */
object SpotifyImageCdn : Interceptor {
    private const val SPOTIFY_HOST = "i.scdn.co"
    private const val AKAMAI_HOST = "image-cdn-ak.spotifycdn.com"

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.url.host != SPOTIFY_HOST) return chain.proceed(request)
        val mirrored = request.newBuilder().url(request.url.newBuilder().host(AKAMAI_HOST).build()).build()
        try {
            val response = chain.proceed(mirrored)
            if (response.isSuccessful) return response
            response.close()
            Timber.w("SpotifyImageCdn: %s answered %d, trying %s", AKAMAI_HOST, response.code, SPOTIFY_HOST)
        } catch (e: IOException) {
            // Not wanted any more (scrolled away): nothing to try again.
            if (chain.call().isCanceled()) throw e
            Timber.w(e, "SpotifyImageCdn: %s failed, trying %s", AKAMAI_HOST, SPOTIFY_HOST)
        }
        return chain.proceed(request)
    }
}
