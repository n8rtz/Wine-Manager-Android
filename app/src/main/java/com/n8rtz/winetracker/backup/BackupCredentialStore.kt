package com.n8rtz.winetracker.backup

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Keystore-backed storage for GitHub / WebDAV credentials and the Drive folder grant.
 *
 * Every value is sealed with an AES-256-GCM key that never leaves the Android Keystore, so the
 * values cannot be read after a transfer to another phone: a restored install must reconnect.
 * The SharedPreferences file is explicitly excluded from Auto Backup and device transfer, and none
 * of these values ever enter BackupData or the JSON export.
 */
class BackupCredentialStore(context: Context) {

    private val preferences =
        context.applicationContext.getSharedPreferences(PREFERENCES_FILE, Context.MODE_PRIVATE)

    fun saveGitHub(repository: String, token: String) {
        put(KEY_GITHUB_REPO, repository.trim())
        put(KEY_GITHUB_TOKEN, token.trim())
    }

    fun githubRepository(): String = get(KEY_GITHUB_REPO)

    fun githubToken(): String = get(KEY_GITHUB_TOKEN)

    fun saveWebDav(url: String, username: String, password: String) {
        put(KEY_WEBDAV_URL, BackupConfigRules.normalizeWebDavUrl(url))
        put(KEY_WEBDAV_USER, username.trim())
        put(KEY_WEBDAV_PASSWORD, password)
    }

    fun webDavUrl(): String = get(KEY_WEBDAV_URL)

    fun webDavUsername(): String = get(KEY_WEBDAV_USER)

    fun webDavPassword(): String = get(KEY_WEBDAV_PASSWORD)

    /** A blank picker result must not clear the saved folder; clear(GOOGLE_DRIVE) disconnects it. */
    fun saveDriveTreeUri(treeUri: String) {
        if (treeUri.isBlank()) return
        put(KEY_DRIVE_TREE_URI, treeUri)
    }

    fun driveTreeUri(): String = get(KEY_DRIVE_TREE_URI)

    fun clear(id: BackupDestinationId) {
        val keys = when (id) {
            BackupDestinationId.GITHUB -> listOf(KEY_GITHUB_REPO, KEY_GITHUB_TOKEN)
            BackupDestinationId.WEBDAV ->
                listOf(KEY_WEBDAV_URL, KEY_WEBDAV_USER, KEY_WEBDAV_PASSWORD)
            BackupDestinationId.GOOGLE_DRIVE -> listOf(KEY_DRIVE_TREE_URI)
            BackupDestinationId.LOCAL -> emptyList()
        }
        preferences.edit().also { editor -> keys.forEach(editor::remove) }.apply()
        Log.d("WineTracker", "BackupCredentialStore: cleared ${id.storageKey} setup")
    }

    /** Throws a visible, actionable error rather than silently returning a missing secret. */
    private fun put(key: String, value: String) {
        if (value.isBlank()) {
            preferences.edit().remove(key).apply()
            return
        }
        preferences.edit().putString(key, encrypt(value)).apply()
    }

    private fun get(key: String): String {
        val stored = preferences.getString(key, null) ?: return ""
        if (stored.isBlank()) return ""
        return try {
            decrypt(stored)
        } catch (error: Exception) {
            Log.e("WineTracker", "BackupCredentialStore: cannot unlock $key", error)
            throw IllegalStateException(
                "Backup credentials cannot be unlocked on this install. Reconnect this destination.",
                error
            )
        }
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val combined = ByteArray(iv.size + ciphertext.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(ciphertext, 0, combined, iv.size, ciphertext.size)
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    private fun decrypt(stored: String): String {
        val combined = Base64.decode(stored, Base64.NO_WRAP)
        require(combined.size > IV_LENGTH_BYTES) { "Stored backup credential is truncated." }
        val iv = combined.copyOfRange(0, IV_LENGTH_BYTES)
        val ciphertext = combined.copyOfRange(IV_LENGTH_BYTES, combined.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }

    companion object {
        /** SharedPreferences file name; keep in sync with the Auto Backup XML exclusions. */
        const val PREFERENCES_FILE = "backup_credentials"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "wine_tracker_backup_credentials"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_LENGTH_BYTES = 12
        private const val GCM_TAG_BITS = 128
        private const val KEY_GITHUB_REPO = "github_repo"
        private const val KEY_GITHUB_TOKEN = "github_token"
        private const val KEY_WEBDAV_URL = "webdav_url"
        private const val KEY_WEBDAV_USER = "webdav_user"
        private const val KEY_WEBDAV_PASSWORD = "webdav_password"
        private const val KEY_DRIVE_TREE_URI = "drive_tree_uri"
    }
}
