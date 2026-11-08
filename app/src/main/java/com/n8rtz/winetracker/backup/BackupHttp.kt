package com.n8rtz.winetracker.backup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Shared HTTP plumbing for the GitHub and WebDAV destinations. */
internal object BackupHttp {

    private const val JSON_MEDIA_TYPE = "application/json; charset=utf-8"

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    fun jsonBody(value: String): RequestBody = value.toRequestBody(JSON_MEDIA_TYPE.toMediaType())

    fun bytesBody(value: ByteArray): RequestBody =
        value.toRequestBody("application/json".toMediaType())

    suspend fun execute(request: Request): Response = withContext(Dispatchers.IO) {
        client.newCall(request).execute()
    }

    /** Turns an HTTP error into an actionable backup failure instead of a silent empty result. */
    fun requireSuccessful(response: Response, operation: String): Response {
        if (!response.isSuccessful) {
            val detail = runCatching { response.body?.string()?.take(300) }.getOrNull()
            val suffix = detail?.let { " Response: $it" }.orEmpty()
            throw IOException("$operation failed (HTTP ${response.code}).$suffix")
        }
        return response
    }

    fun basicAuthorization(username: String, password: String): String {
        val raw = "$username:$password".toByteArray(Charsets.UTF_8)
        return "Basic " + android.util.Base64.encodeToString(raw, android.util.Base64.NO_WRAP)
    }
}
