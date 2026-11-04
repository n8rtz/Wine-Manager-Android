package com.n8rtz.winetracker.backup

import android.content.Context
import android.net.Uri
import android.util.Log
import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import java.io.IOException
import java.io.StringReader
import java.net.URLDecoder

/**
 * Self-hosted WebDAV destination: TrueNAS SCALE's built-in WebDAV share or the Nextcloud app
 * (`https://<host>/remote.php/dav/files/<user>/WineManager/`). Plain HTTP is allowed for a LAN
 * host but is flagged in the setup dialog because it sends the cellar JSON and password in clear.
 */
class WebDavDestination(
    private val context: Context,
    private val credentials: BackupCredentialStore
) : BackupDestination {

    override val id: BackupDestinationId = BackupDestinationId.WEBDAV

    private fun baseUrl(): String {
        val value = credentials.webDavUrl()
        if (!BackupConfigRules.isValidWebDavUrl(value)) {
            throw IOException("WebDAV setup is incomplete: enter an absolute http(s) URL with a host.")
        }
        return BackupConfigRules.normalizeWebDavUrl(value)
    }

    private fun username(): String = credentials.webDavUsername().takeIf { it.isNotBlank() }
        ?: throw IOException("WebDAV setup is incomplete: enter the share username.")

    private fun password(): String = credentials.webDavPassword().takeIf { it.isNotEmpty() }
        ?: throw IOException("WebDAV setup is incomplete: enter the share password.")

    override fun isConfigured(): Boolean =
        BackupConfigRules.isValidWebDavUrl(credentials.webDavUrl()) &&
            credentials.webDavUsername().isNotBlank() &&
            credentials.webDavPassword().isNotEmpty()

    private fun authorized(url: String): Request.Builder = Request.Builder()
        .url(url)
        .header("Authorization", BackupHttp.basicAuthorization(username(), password()))

    private fun fileUrl(fileName: String): String =
        BackupConfigRules.webDavFileUrl(baseUrl(), fileName)

    private suspend fun ensureFolder() {
        val request = authorized(baseUrl()).method("MKCOL", null).build()
        BackupHttp.execute(request).use { response ->
            // 405 means the collection already exists on most WebDAV servers.
            if (!response.isSuccessful && response.code != 405) {
                throw IOException(
                    "Could not create the WebDAV backup folder (HTTP ${response.code}). " +
                        "Check the share path and write permission."
                )
            }
        }
    }

    private suspend fun propfind(url: String, depth: Int): String {
        val request = authorized(url)
            .header("Depth", depth.toString())
            .method("PROPFIND", null)
            .build()
        return BackupHttp.execute(request).use { response ->
            if (response.code == 404) return@use ""
            BackupHttp.requireSuccessful(response, "WebDAV folder listing")
            response.body?.string() ?: throw IOException("WebDAV returned an empty folder listing.")
        }
    }

    /** Reads `<href>` values from a Depth:1 PROPFIND response and keeps only our own snapshots. */
    private fun parseHrefNames(xml: String): List<String> {
        if (xml.isBlank()) return emptyList()
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        parser.setInput(StringReader(xml))
        val names = mutableListOf<String>()
        var inHref = false
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when {
                event == XmlPullParser.START_TAG && parser.name.equals("href", ignoreCase = true) ->
                    inHref = true
                event == XmlPullParser.TEXT && inHref -> {
                    val decoded = runCatching {
                        URLDecoder.decode(parser.text, "UTF-8")
                    }.getOrDefault(parser.text)
                    val segment = decoded.trimEnd('/').substringAfterLast('/')
                    if (BackupSnapshots.isAppBackup(segment)) names.add(segment)
                }
                event == XmlPullParser.END_TAG && parser.name.equals("href", ignoreCase = true) ->
                    inHref = false
            }
            event = parser.next()
        }
        return names.distinct()
    }

    override suspend fun testConnection(): String {
        ensureFolder()
        val listing = propfind(baseUrl(), 0)
        if (listing.isBlank() && !isConfigured()) {
            throw IOException("WebDAV credentials are incomplete.")
        }
        return "WebDAV folder ready at ${baseUrl()}"
    }

    override suspend fun upload(snapshot: BackupSnapshot): String {
        ensureFolder()
        val request = authorized(fileUrl(snapshot.fileName))
            .put(BackupHttp.bytesBody(snapshot.bytes))
            .build()
        BackupHttp.execute(request).use { response ->
            BackupHttp.requireSuccessful(response, "WebDAV upload")
        }
        Log.d("WineTracker", "WebDavDestination: uploaded ${snapshot.fileName}")
        return snapshot.fileName
    }

    override suspend fun verify(name: String, expectedSha256: String): Boolean {
        val request = authorized(fileUrl(name)).get().build()
        return BackupHttp.execute(request).use { response ->
            if (response.code == 404) return@use false
            BackupHttp.requireSuccessful(response, "WebDAV read-back")
            val bytes = response.body?.bytes() ?: return@use false
            val matches = sha256Hex(bytes).equals(expectedSha256, ignoreCase = true)
            if (!matches) Log.w("WineTracker", "WebDavDestination: checksum mismatch for $name")
            matches
        }
    }

    override suspend fun list(): List<String> = parseHrefNames(propfind(baseUrl(), 1))

    override suspend fun download(name: String): Uri {
        val request = authorized(fileUrl(name)).get().build()
        val bytes = BackupHttp.execute(request).use { response ->
            if (response.code == 404) throw IOException("WebDAV backup $name is no longer available.")
            BackupHttp.requireSuccessful(response, "WebDAV download")
            response.body?.bytes()
                ?: throw IOException("WebDAV returned an empty response for $name.")
        }
        return downloadedBackupUri(context, name, bytes)
    }

    override suspend fun delete(name: String) {
        val request = authorized(fileUrl(name)).delete().build()
        BackupHttp.execute(request).use { response ->
            // An already-removed copy is not an error for retention purposes.
            if (response.code == 404) return@use
            BackupHttp.requireSuccessful(response, "WebDAV delete")
        }
        Log.d("WineTracker", "WebDavDestination: pruned $name")
    }
}
