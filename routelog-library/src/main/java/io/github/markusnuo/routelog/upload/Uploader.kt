package io.github.markusnuo.routelog.upload

import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Lets another thread abort an upload that is in flight (the recording was deleted, the job was stopped). */
class UploadCall {
    @Volatile
    private var cancelled = false

    @Volatile
    private var call: Call? = null

    val isCancelled: Boolean get() = cancelled

    fun cancel() {
        cancelled = true
        call?.cancel()
    }

    /** False if the call was cancelled before it started; it is cancelled then. */
    internal fun attach(call: Call): Boolean {
        this.call = call
        if (cancelled) {
            call.cancel()
            return false
        }
        return true
    }
}

/**
 * One `PUT` of a GPX body with the grant as bearer token (docs/EXTERNAL_RECORDING.md).
 * Redirects are never followed: the token must only ever go to the address the user confirmed or
 * the host app passed.
 *
 * OkHttp, not HttpURLConnection: when a server answers (401, 413, …) before it has read the whole
 * body and closes the connection, OkHttp still reads the answer; Android's HttpURLConnection only
 * reports a broken connection, and the upload would be retried until the grant expires.
 */
class Uploader(client: OkHttpClient? = null) {
    // Built on the first upload, on the upload worker thread: UploadManager creates the uploader and
    // RoutelogCore creates the UploadManager, and RoutelogCore is also created on the main thread at
    // app start, in the boot receiver and in the recording service. Building a client there would
    // slow all of them down.
    private val okHttp: OkHttpClient by lazy { client ?: DEFAULT_CLIENT }

    /** The HTTP status, or null if no HTTP answer came back (network, timeout, TLS, [call] cancelled). */
    fun put(url: String, token: String, body: ByteArray, call: UploadCall = UploadCall()): Int? {
        val target = url.toHttpUrlOrNull() ?: return null
        val request = Request.Builder()
            .url(target)
            .put(body.toRequestBody(CONTENT_TYPE.toMediaType()))
            .header("Authorization", "Bearer $token")
            .build()
        val okCall = okHttp.newCall(request)
        if (!call.attach(okCall)) return null
        return try {
            okCall.execute().use { it.code }
        } catch (e: IOException) {
            null
        }
    }

    companion object {
        const val CONTENT_TYPE = "application/gpx+xml"

        private val DEFAULT_CLIENT by lazy {
            OkHttpClient.Builder()
                .followRedirects(false)
                .followSslRedirects(false)
                // Retrying is the job scheduler's business, with its back-off and the grant's expiry.
                .retryOnConnectionFailure(false)
                .connectTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build()
        }
    }
}
