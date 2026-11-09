package com.n8rtz.winetracker.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure JVM coverage for the off-device setup form validation. */
class BackupConfigRulesTest {

    @Test
    fun gitHubRepositoryMustBeOwnerSlashName() {
        assertTrue(BackupConfigRules.isValidGitHubRepository("n8rtz/wine-backups"))
        assertTrue(BackupConfigRules.isValidGitHubRepository(" n8rtz/Wine.Manager_1 "))
        assertFalse(BackupConfigRules.isValidGitHubRepository("wine-backups"))
        assertFalse(BackupConfigRules.isValidGitHubRepository("n8rtz/wine-backups/extra"))
        assertFalse(BackupConfigRules.isValidGitHubRepository("https://github.com/n8rtz/wine-backups"))
        assertFalse(BackupConfigRules.isValidGitHubRepository("n8rtz/"))
        assertFalse(BackupConfigRules.isValidGitHubRepository(".owner/repo"))
        assertFalse(BackupConfigRules.isValidGitHubRepository("owner/repo-"))
        assertFalse(BackupConfigRules.isValidGitHubRepository("owner with spaces/repo"))
        assertTrue(BackupConfigRules.looksLikeGitHubUrl("https://github.com/n8rtz/wine-backups"))
        assertFalse(BackupConfigRules.looksLikeGitHubUrl("n8rtz/wine-backups"))
    }

    @Test
    fun webDavUrlMustBeAbsoluteAndCredentialFree() {
        assertTrue(BackupConfigRules.isValidWebDavUrl("https://nas.local/dav/WineManager/"))
        assertTrue(BackupConfigRules.isValidWebDavUrl("http://192.168.1.10:8080/share"))
        assertFalse(BackupConfigRules.isValidWebDavUrl("nas.local/share"))
        assertFalse(BackupConfigRules.isValidWebDavUrl("ftp://nas.local/share"))
        assertFalse(BackupConfigRules.isValidWebDavUrl("https://user:pass@nas.local/share"))
        assertFalse(BackupConfigRules.isValidWebDavUrl("https://nas.local/share?x=1"))
        assertFalse(BackupConfigRules.isValidWebDavUrl("https://nas.local/my share"))
        assertTrue(BackupConfigRules.isInsecureWebDavUrl("http://192.168.1.10/share"))
        assertFalse(BackupConfigRules.isInsecureWebDavUrl("https://nas.local/share"))
    }

    @Test
    fun webDavFileUrlsKeepALeadingCollectionSlash() {
        assertEquals(
            "https://nas.local/share/",
            BackupConfigRules.normalizeWebDavUrl("https://nas.local/share")
        )
        assertEquals(
            "https://nas.local/share/WineTracker_2026-11-03_211730.json",
            BackupConfigRules.webDavFileUrl(
                "https://nas.local/share",
                "WineTracker_2026-11-03_211730.json"
            )
        )
    }

    @Test
    fun onlyAppSnapshotsAreAddressedRemotely() {
        assertTrue(BackupConfigRules.isOwnBackup("WineTracker_2026-11-03_211730.json"))
        assertFalse(BackupConfigRules.isOwnBackup("WineTracker_Backup.json"))
        assertFalse(BackupConfigRules.isOwnBackup("notes.json"))
    }
}
