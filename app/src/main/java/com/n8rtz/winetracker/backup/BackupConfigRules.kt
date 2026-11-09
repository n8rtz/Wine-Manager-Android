package com.n8rtz.winetracker.backup

import java.net.URI

/** Pure validation for the off-device setup forms. No Android or network dependency. */
object BackupConfigRules {

    private val REPOSITORY_PATTERN = Regex("^[A-Za-z0-9._-]+/[A-Za-z0-9._-]+$")

    /** GitHub requires `owner/repo`; a URL, a bare name or extra path segments are rejected. */
    fun isValidGitHubRepository(value: String): Boolean {
        val trimmed = value.trim()
        if (!REPOSITORY_PATTERN.matches(trimmed)) return false
        val parts = trimmed.split('/')
        return parts.all { part ->
            part.length in 1..100 && !part.startsWith('.') && !part.startsWith('-') &&
                !part.endsWith('.') && !part.endsWith('-')
        }
    }

    fun looksLikeGitHubUrl(value: String): Boolean =
        value.trim().startsWith("http://", ignoreCase = true) ||
            value.trim().startsWith("https://", ignoreCase = true) ||
            value.trim().startsWith("git@", ignoreCase = true)

    /** WebDAV target must be an absolute http(s) URL with a host and no embedded credentials. */
    fun isValidWebDavUrl(value: String): Boolean {
        val trimmed = value.trim()
        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme != "http" && scheme != "https") return false
        if (uri.host.isNullOrBlank() || uri.userInfo != null) return false
        if (uri.query != null || uri.fragment != null) return false
        return !trimmed.contains(' ')
    }

    /** Plain http sends the cellar JSON and the password unencrypted across the local network. */
    fun isInsecureWebDavUrl(value: String): Boolean =
        value.trim().startsWith("http://", ignoreCase = true)

    /** Ensures the WebDAV collection ends in a slash so file names can be appended directly. */
    fun normalizeWebDavUrl(value: String): String {
        val trimmed = value.trim()
        val withSlash = if (trimmed.endsWith('/')) trimmed else "$trimmed/"
        return runCatching {
            val uri = URI(withSlash)
            if (uri.path.isNullOrBlank()) {
                "${uri.scheme}://${uri.authority}/"
            } else {
                withSlash
            }
        }.getOrDefault(withSlash)
    }

    fun webDavFileUrl(baseUrl: String, fileName: String): String =
        normalizeWebDavUrl(baseUrl) + fileName

    /** True when [fileName] is one of our own snapshots, so pruning can never touch other files. */
    fun isOwnBackup(fileName: String): Boolean = BackupSnapshots.isAppBackup(fileName)
}
