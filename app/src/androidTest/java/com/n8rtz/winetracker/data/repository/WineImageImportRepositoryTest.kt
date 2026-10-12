package com.n8rtz.winetracker.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.n8rtz.winetracker.data.local.ConsumptionEvent
import com.n8rtz.winetracker.data.local.TastingNote
import com.n8rtz.winetracker.data.local.WineBottle
import com.n8rtz.winetracker.data.local.WineDatabase
import com.n8rtz.winetracker.data.local.WineDefinition
import com.n8rtz.winetracker.data.local.WineImageAsset
import com.n8rtz.winetracker.image.WineImageFileStore
import java.io.File
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WineImageImportRepositoryTest {
    private lateinit var context: Context
    private lateinit var database: WineDatabase
    private lateinit var fileStore: WineImageFileStore
    private lateinit var repository: WineImageImportRepository
    private val createdFiles = mutableListOf<File>()

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, WineDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        fileStore = WineImageFileStore(context)
        repository = WineImageImportRepository(database, fileStore)
    }

    @After
    fun tearDown() {
        createdFiles.forEach { it.delete() }
        database.close()
    }

    @Test
    fun removeOfflineCopyClearsOnlyPathAndPreservesWineHistoryAndProvenance() = runBlocking {
        val definitionId = insertWineWithImageUrl()
        val relativePath = "${WineImageFileStore.IMAGE_DIRECTORY}/$definitionId-remove-test.webp"
        val imageFile = createStoredFile(relativePath)
        val asset = testAsset(definitionId, relativePath)
        database.wineImageAssetDao().upsert(asset)
        database.inventoryDao().insertBottle(
            WineBottle(
                definitionId = definitionId,
                vintage = "2020",
                quantity = 2,
                price = 45.0,
                qtyType = "bottle",
                location = "Rack A"
            )
        )
        database.tastingNoteDao().insertNote(
            TastingNote(
                definitionId = definitionId,
                vintage = "2020",
                dateTasted = 1_700_000_000_000L,
                rating = 4.0,
                notes = "Keep this note"
            )
        )
        val consumptionEvent = ConsumptionEvent(
            definitionId = definitionId,
            vintage = "2020",
            consumedAt = 1_700_000_000_000L,
            quantity = 1,
            price = 45.0
        )
        val consumptionEventId = database.consumptionEventDao().insertEvent(consumptionEvent)

        assertEquals(imageFile.length(), database.wineImageAssetDao().getStoredBytes())
        assertEquals(
            OfflineImageRemovalResult.REMOVED,
            repository.removeOfflineCopy(definitionId, relativePath)
        )

        val after = database.wineImageAssetDao().getByDefinitionId(definitionId)
        assertNotNull(after)
        assertEquals(asset.copy(relativePath = null), after)
        assertFalse(imageFile.exists())
        assertEquals(0L, database.wineImageAssetDao().getStoredBytes())
        assertEquals(
            "https://images.openfoodfacts.org/images/products/1234/front.full.jpg",
            database.wineDao().getDefinitionById(definitionId)?.imageUrl
        )
        assertTrue(database.wineDao().getAllMissingOfflineImages().any { it.definitionId == definitionId })
        assertFalse(database.wineDao().getAllWithoutImages().any { it.definitionId == definitionId })
        assertEquals(2, database.inventoryDao().getAllBottlesOnce().single().quantity)
        assertEquals("Keep this note", database.tastingNoteDao().getAllNotesOnce().single().notes)
        assertEquals(
            consumptionEvent.copy(eventId = consumptionEventId),
            database.consumptionEventDao().getAllEventsOnce().single()
        )
    }

    @Test
    fun removeOfflineCopyDoesNotDeleteAPathStillReferencedByAnotherAsset() = runBlocking {
        val firstDefinitionId = insertWineWithImageUrl()
        val secondDefinitionId = insertWineWithImageUrl(name = "Second Offline Image Test")
        val relativePath = "${WineImageFileStore.IMAGE_DIRECTORY}/shared-offline-test.webp"
        val imageFile = createStoredFile(relativePath)
        database.wineImageAssetDao().upsert(testAsset(firstDefinitionId, relativePath))
        database.wineImageAssetDao().upsert(
            testAsset(secondDefinitionId, relativePath).copy(
                providerAssetId = "second-1234",
                productTitle = "Second Offline Image Test"
            )
        )

        assertEquals(
            OfflineImageRemovalResult.REMOVED,
            repository.removeOfflineCopy(firstDefinitionId, relativePath)
        )
        assertNull(database.wineImageAssetDao().getByDefinitionId(firstDefinitionId)?.relativePath)
        assertEquals(relativePath, database.wineImageAssetDao().getByDefinitionId(secondDefinitionId)?.relativePath)
        assertTrue(imageFile.exists())
        assertEquals(imageFile.length(), database.wineImageAssetDao().getStoredBytes())

        assertEquals(
            OfflineImageRemovalResult.REMOVED,
            repository.removeOfflineCopy(secondDefinitionId, relativePath)
        )
        assertNull(database.wineImageAssetDao().getByDefinitionId(secondDefinitionId)?.relativePath)
        assertFalse(imageFile.exists())
        assertEquals(0L, database.wineImageAssetDao().getStoredBytes())
    }

    @Test
    fun storedImageProjectionIncludesDefinitionForManagementList() = runBlocking {
        val definitionId = insertWineWithImageUrl()
        val relativePath = "${WineImageFileStore.IMAGE_DIRECTORY}/$definitionId-list-test.webp"
        createStoredFile(relativePath)
        database.wineImageAssetDao().upsert(testAsset(definitionId, relativePath))

        val rows = repository.observeStoredImages().first()

        assertEquals(1, rows.size)
        assertEquals(definitionId, rows.single().imageAsset.definitionId)
        assertEquals("Offline Image Test", rows.single().definition.name)
        assertEquals("Test Producer", rows.single().definition.maker)
    }

    @Test
    fun repeatedRemovalIsIdempotent() = runBlocking {
        val definitionId = insertWineWithImageUrl()
        val relativePath = "${WineImageFileStore.IMAGE_DIRECTORY}/$definitionId-repeat-test.webp"
        val imageFile = createStoredFile(relativePath)
        database.wineImageAssetDao().upsert(testAsset(definitionId, relativePath))

        val results = listOf(
            async { repository.removeOfflineCopy(definitionId, relativePath) },
            async { repository.removeOfflineCopy(definitionId, relativePath) }
        ).awaitAll()

        assertEquals(1, results.count { it == OfflineImageRemovalResult.REMOVED })
        assertEquals(1, results.count { it == OfflineImageRemovalResult.ALREADY_CHANGED })
        assertFalse(imageFile.exists())
        assertNull(database.wineImageAssetDao().getByDefinitionId(definitionId)?.relativePath)
    }

    @Test
    fun staleRemovalRequestDoesNotClearNewerPathOrDeleteEitherFile() = runBlocking {
        val definitionId = insertWineWithImageUrl()
        val currentPath = "${WineImageFileStore.IMAGE_DIRECTORY}/$definitionId-current.webp"
        val stalePath = "${WineImageFileStore.IMAGE_DIRECTORY}/$definitionId-stale.webp"
        val currentFile = createStoredFile(currentPath)
        val staleFile = createStoredFile(stalePath)
        database.wineImageAssetDao().upsert(testAsset(definitionId, currentPath))

        assertEquals(
            OfflineImageRemovalResult.ALREADY_CHANGED,
            repository.removeOfflineCopy(definitionId, stalePath)
        )
        assertEquals(currentPath, database.wineImageAssetDao().getByDefinitionId(definitionId)?.relativePath)
        assertTrue(currentFile.exists())
        assertTrue(staleFile.exists())
        assertEquals(currentFile.length(), database.wineImageAssetDao().getStoredBytes())
    }

    private suspend fun insertWineWithImageUrl(name: String = "Offline Image Test"): Long {
        val insertedId = database.wineDao().insertDefinition(
            WineDefinition(
                maker = "Test Producer",
                name = name,
                type = "Red",
                country = "France",
                region = null,
                imageUrl = "https://images.openfoodfacts.org/images/products/1234/front.full.jpg"
            )
        )
        check(insertedId > 0L)
        return insertedId
    }

    private fun createStoredFile(relativePath: String): File {
        val file = File(context.filesDir, relativePath)
        check(file.parentFile?.mkdirs() == true || file.parentFile?.isDirectory == true)
        file.writeBytes(byteArrayOf(1, 2, 3, 4, 5))
        createdFiles += file
        return file
    }

    private fun testAsset(definitionId: Long, relativePath: String) = WineImageAsset(
        definitionId = definitionId,
        relativePath = relativePath,
        sourceName = "Open Food Facts",
        providerAssetId = "1234",
        productTitle = "Offline Image Test",
        sourceUrl = "https://images.openfoodfacts.org/images/products/1234/front.full.jpg",
        sourcePageUrl = "https://world.openfoodfacts.org/product/1234",
        attribution = "Test contributor",
        license = null,
        kind = "BOTTLE",
        width = 800,
        height = 1_200,
        sizeBytes = 5,
        sha256 = "a".repeat(64),
        importedAt = 1_700_000_000_000L
    )
}
