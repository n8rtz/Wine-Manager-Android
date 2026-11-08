package com.n8rtz.winetracker.backup

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import okhttp3.Request
import java.io.IOException

/**
 * GitHub backups: one JSON snapshot per commit in `WineManager/` of a PRIVATE repository.
 *
 * A fine-grained personal access token with Contents: read/write on that single repo is required.
 * A public repository is refused — the cellar JSON is personal data. Note that deleting an old
 * backup only removes it from the current branch; earlier commits still contain it.
 */
class GitHubDestination(
    private val context: Context,
    private val credentials: BackupCredentialStore
) : BackupDestination {

    override val id: BackupDestinationId = BackupDestinationId.GITHUB

    private val gson = Gson()

    override fun isConfigured(): Boolean =
        BackupConfigRules.isValidGitHubRepository(credentials.githubRepository()) &&
            credentials.githubToken().isNotBlank()

    private fun repository(): String {
        val value = credentials.githubRepository()
        if (!BackupConfigRules.isValidGitHubRepository(value)) {
            throw IOException("GitHub setup is incomplete: enter a private repository as owner/name.")
        }
        return value
    }

    private fun token(): String {
        val value = credentials.githubToken()
        if (value.isBlank()) {
            throw IOException("GitHub setup is incomplete: add a fine-grained personal access token.")
        }
        return value
    }

    private fun authorized(url: String): Request.Builder = Request.Builder()
        .url(url)
        .header("Authorization", "Bearer ${token()}")
        .header("Accept", "application/vnd.github+json")
        .header("X-GitHub-Api-Version", "2022-11-28")
        .header("User-Agent", "WineTracker-Android")

    private fun repositoryUrl(): String = "https://api.github.com/repos/${repository()}"

    private fun contentsUrl(fileName: String): String =
        "${repositoryUrl()}/contents/$FOLDER/$fileName"

    private fun folderUrl(): String = "${repositoryUrl()}/contents/$FOLDER"

    private suspend fun repositoryInfo(): JsonObject {
        val request = authorized(repositoryUrl()).get().build()
        return BackupHttp.execute(request).use { response ->
            BackupHttp.requireSuccessful(response, "GitHub repository lookup")
            val body = response.body?.string()
                ?: throw IOException("GitHub returned an empty repository response.")
            gson.fromJson(body, JsonObject::class.java)
                ?: throw IOException("GitHub returned an unreadable repository response.")
        }
    }

    private fun privateBranch(info: JsonObject): String {
        val isPrivate = info.get("private")?.asBoolean
            ?: throw IOException("GitHub did not report whether the repository is private.")
        if (!isPrivate) {
            throw IOException(
                "Refusing to back up to a public repository. Make ${repository()} private " +
                    "before enabling GitHub backups."
            )
        }
        return info.get("default_branch")?.asString?.takeIf { it.isNotBlank() }
            ?: throw IOException("GitHub did not report a default branch for ${repository()}.")
    }

    override suspend fun testConnection(): String {
        val info = repositoryInfo()
        val branch = privateBranch(info)
        return "Private ${repository()} on branch $branch"
    }

    override suspend fun upload(snapshot: BackupSnapshot): String {
        val branch = privateBranch(repositoryInfo())
        val payload = JsonObject().apply {
            addProperty("message", "Wine Manager backup ${snapshot.fileName}")
            addProperty("content", Base64.encodeToString(snapshot.bytes, Base64.NO_WRAP))
            addProperty("branch", branch)
        }
        val request = authorized(contentsUrl(snapshot.fileName))
            .put(BackupHttp.jsonBody(gson.toJson(payload)))
            .build()
        BackupHttp.execute(request).use { response ->
            BackupHttp.requireSuccessful(response, "GitHub upload")
        }
        Log.d("WineTracker", "GitHubDestination: uploaded ${snapshot.fileName}")
        return snapshot.fileName
    }

    private suspend fun readFileObject(name: String): JsonObject {
        val request = authorized(contentsUrl(name)).get().build()
        return BackupHttp.execute(request).use { response ->
            if (response.code == 404) throw IOException("Backup $name was not found on GitHub.")
            BackupHttp.requireSuccessful(response, "GitHub download")
            val body = response.body?.string()
                ?: throw IOException("GitHub returned an empty response for $name.")
            gson.fromJson(body, JsonObject::class.java)
                ?: throw IOException("GitHub returned an unreadable response for $name.")
        }
    }

    private suspend fun readFile(name: String): ByteArray {
        val encoded = readFileObject(name).get("content")?.asString
            ?: throw IOException("GitHub did not return the file content for $name.")
        return Base64.decode(encoded.filter { !it.isWhitespace() }, Base64.NO_WRAP)
    }

    override suspend fun verify(name: String, expectedSha256: String): Boolean {
        val actual = sha256Hex(readFile(name))
        val matches = actual.equals(expectedSha256, ignoreCase = true)
        if (!matches) Log.w("WineTracker", "GitHubDestination: checksum mismatch for $name")
        return matches
    }

    override suspend fun list(): List<String> {
        val request = authorized(folderUrl()).get().build()
        return BackupHttp.execute(request).use { response ->
            if (response.code == 404) return@use emptyList()
            BackupHttp.requireSuccessful(response, "GitHub backup listing")
            val body = response.body?.string()
                ?: throw IOException("GitHub returned an empty backup listing.")
            val entries = gson.fromJson(body, JsonArray::class.java)
                ?: throw IOException("GitHub returned an unreadable backup listing.")
            entries.mapNotNull { element ->
                runCatching { element.asJsonObject.get("name")?.asString }.getOrNull()
            }.filter { BackupSnapshots.isAppBackup(it) }
        }
    }

    override suspend fun download(name: String): Uri =
        downloadedBackupUri(context, name, readFile(name))

    override suspend fun delete(name: String) {
        val sha = readFileObject(name).get("sha")?.asString
            ?: throw IOException("GitHub did not return a blob SHA for $name.")
        val payload = JsonObject().apply {
            addProperty("message", "Remove old Wine Manager backup $name")
            addProperty("sha", sha)
        }
        val request = authorized(contentsUrl(name))
            .method("DELETE", BackupHttp.jsonBody(gson.toJson(payload)))
            .build()
        BackupHttp.execute(request).use { response ->
            BackupHttp.requireSuccessful(response, "GitHub delete")
        }
        Log.d("WineTracker", "GitHubDestination: pruned $name")
    }

    private companion object {
        const val FOLDER = "WineManager"
    }
}
