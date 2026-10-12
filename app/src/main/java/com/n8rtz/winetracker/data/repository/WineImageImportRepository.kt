package com.n8rtz.winetracker.data.repository

import androidx.room.withTransaction
import com.n8rtz.winetracker.data.local.WineDatabase
import com.n8rtz.winetracker.data.local.WineImageAsset
import com.n8rtz.winetracker.data.local.WineImageAssetWithDefinition
import com.n8rtz.winetracker.image.PreparedWineImage
import com.n8rtz.winetracker.image.WineImageCandidate
import com.n8rtz.winetracker.image.WineImageFileStore
import com.n8rtz.winetracker.image.WineImageKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

enum class OfflineImageRemovalResult {
    REMOVED,
    ALREADY_CHANGED,
    FILE_DELETE_DEFERRED
}

/** Persistence boundary for reviewed image import and offline-copy management. */
class WineImageImportRepository(
    private val database: WineDatabase,
    private val fileStore: WineImageFileStore
) {
    private val mutationMutex = Mutex()

    private suspend fun <T> withMutationLock(block: suspend () -> T): T {
        mutationMutex.lock()
        try {
            return block()
        } finally {
            mutationMutex.unlock()
        }
    }
    suspend fun getMissingImageDefinitions() = database.wineDao().getAllMissingOfflineImages()

    suspend fun getStoredBytes(): Long = database.wineImageAssetDao().getStoredBytes()

    fun observeStoredImages() = database.wineImageAssetDao().observeStoredWithDefinitions()

    suspend fun saveApprovedImage(
        definitionId: Long,
        candidate: WineImageCandidate,
        kind: WineImageKind,
        prepared: PreparedWineImage
    ): WineImageAsset = withMutationLock {
        val stored = fileStore.install(definitionId, prepared)
        try {
            database.withTransaction {
                val wineDao = database.wineDao()
                val assetDao = database.wineImageAssetDao()
                val definition = wineDao.getDefinitionById(definitionId)
                    ?: throw IllegalStateException("This wine no longer exists.")
                val existingAsset = assetDao.getByDefinitionId(definitionId)
                if (existingAsset?.relativePath != null ||
                    (existingAsset == null && !definition.imageUrl.isNullOrBlank())
                ) {
                    throw IllegalStateException("This wine already has an image; it was left unchanged.")
                }

                val asset = WineImageAsset(
                    definitionId = definitionId,
                    relativePath = stored.relativePath,
                    sourceName = candidate.sourceName,
                    providerAssetId = candidate.productCode,
                    productTitle = candidate.productTitle,
                    sourceUrl = candidate.sourceUrl,
                    sourcePageUrl = candidate.sourcePageUrl,
                    attribution = candidate.attribution,
                    license = null,
                    kind = kind.databaseValue,
                    width = stored.width,
                    height = stored.height,
                    sizeBytes = stored.sizeBytes,
                    sha256 = stored.sha256,
                    importedAt = System.currentTimeMillis()
                )
                // Retain the provider URL for provenance; records with an asset row do not auto-fetch it.
                wineDao.updateDefinition(definition.copy(imageUrl = candidate.sourceUrl))
                assetDao.upsert(asset)
                asset
            }
        } catch (cancelled: CancellationException) {
            cleanupUnreferencedFile(stored.relativePath)
            throw cancelled
        } catch (error: Exception) {
            cleanupUnreferencedFile(stored.relativePath)
            throw error
        }
    }

    suspend fun removeOfflineCopy(
        definitionId: Long,
        expectedRelativePath: String
    ): OfflineImageRemovalResult = withMutationLock {
        val assetDao = database.wineImageAssetDao()
        val current = assetDao.getByDefinitionId(definitionId)
        if (current?.relativePath != expectedRelativePath) {
            return@withMutationLock OfflineImageRemovalResult.ALREADY_CHANGED
        }

        val cleared = assetDao.clearRelativePathIfMatches(definitionId, expectedRelativePath)
        if (cleared == 0) return@withMutationLock OfflineImageRemovalResult.ALREADY_CHANGED

        // A path is usually unique to a definition; still protect against shared references.
        if (assetDao.countReferences(expectedRelativePath) > 0) {
            return@withMutationLock OfflineImageRemovalResult.REMOVED
        }

        runCatching { fileStore.deleteLocalImage(expectedRelativePath) }
        val remains = runCatching { fileStore.hasLocalImage(expectedRelativePath) }.getOrDefault(true)
        if (remains) OfflineImageRemovalResult.FILE_DELETE_DEFERRED
        else OfflineImageRemovalResult.REMOVED
    }

    private suspend fun cleanupUnreferencedFile(relativePath: String) = withContext(NonCancellable) {
        runCatching {
            if (database.wineImageAssetDao().countReferences(relativePath) == 0) {
                fileStore.deleteLocalImage(relativePath)
            }
        }
    }

    suspend fun cleanupOrphanFiles() = withMutationLock {
        val assetDao = database.wineImageAssetDao()
        val assets = assetDao.getAllOnce()
        assets.forEach { asset ->
            val path = asset.relativePath
            if (path != null && !fileStore.hasLocalImage(path)) {
                assetDao.upsert(asset.copy(relativePath = null))
            }
        }
        val referencedPaths = assetDao.getAllOnce().mapNotNull { it.relativePath }.toSet()
        fileStore.cleanupOrphanFiles(referencedPaths)
    }
}
