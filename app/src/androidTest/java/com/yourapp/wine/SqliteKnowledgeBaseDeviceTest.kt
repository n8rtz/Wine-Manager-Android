package com.yourapp.wine

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yourapp.wine.kb.SqliteWineKnowledgeBase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream

@RunWith(AndroidJUnit4::class)
class SqliteKnowledgeBaseDeviceTest {
    @Test fun fuzzyShortlistAndCuveeTiesUseRealSqlite() = runBlocking {
        withContext(Dispatchers.Default) {
            val context=InstrumentationRegistry.getInstrumentation().targetContext
            SqliteWineKnowledgeBase.open(context).use { kb ->
                val data="""{"schema":"wine-kb/v1","producers":[{"id":990071,"name":"Blackstone Winery","country_code":"US","region_id":19}],"producer_aliases":[{"producer_id":990071,"alias_norm":"blackstone"}],"cuvees":[{"producer_id":990071,"name":"Sample Reserva"},{"producer_id":990071,"name":"Sample Reserxb"}]}"""
                kb.importOpenWines(data.byteInputStream())
                assertTrue(kb.findProducerFuzzy("LACKSTONE").any { it.producer.id==990071L && it.editDistance==1 })
                assertEquals(2,kb.findCuvees(990071,"Sample Reserxa").size)
            }
        }
    }

    @Test fun realFts5AliasesAndTransactionalImport() = runBlocking {
        withContext(Dispatchers.Default) {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            SqliteWineKnowledgeBase.open(context).use { kb ->
                val exact = kb.findProducer("Chateau Margaux").first()
                assertEquals("Château Margaux", exact.producer.name)
                assertEquals(0, exact.editDistance)
                assertEquals(1, kb.findProducer("Chateau Margauy").first().editDistance)
                val before = kb.places()
                val bad = """{"schema":"wine-kb/v1","places":[{"id":99999,"name":"Invalid","type":"region","parent_id":1,"country_code":"IT"}]}"""
                assertTrue(runCatching { kb.importOpenWines(bad.byteInputStream()) }.isFailure)
                assertEquals(before, kb.places())
                assertEquals("Château Margaux", kb.findProducer("Chateau Margaux").first().producer.name)
            }
        }
    }

    @Test fun csvAndJsonStagingStayAtomicAndDeletePrivateTemporaryFiles() = runBlocking {
        withContext(Dispatchers.Default) {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            fun stagingFiles() = context.cacheDir.listFiles().orEmpty().filter { it.name.startsWith("wine-kb-import-") }
                .map { it.name }.toSet()
            val existingFiles = stagingFiles()
            SqliteWineKnowledgeBase.open(context).use { kb ->
                val csv = "record_type,id,name,type,parent_id,country_code,region_id,producer_id,alias_norm\n" +
                    "alias,,,,,,,1,chateau margaux\n" + "producer,1,Château Margaux,,,FR,2,,\n" +
                    "place,4,Margaux,appellation,2,FR,,,\n" + "place,2,Bordeaux,region,1,FR,,,\n" +
                    "place,1,France,country,,FR,,,\n" + "cuvee,,Pavillon Rouge,,,,,1,\n"
                var closed = false
                val input = object : ByteArrayInputStream(csv.toByteArray()) { override fun close() { closed = true } }
                kb.importOpenWines(input)
                assertFalse("Caller owns its stream", closed)
                assertEquals("Pavillon Rouge", kb.findCuvee(1, "Pavillon Rouge")?.cuvee?.name)
                assertEquals(existingFiles, stagingFiles())
                val before = kb.places()
                val invalid = listOf(
                    // A late schema failure happens after the staged row, but before any live DB mutation.
                    """{"producers":[{"id":1,"name":"Should not replace","country_code":"FR","region_id":2}],"schema":"wrong"}""",
                    """{"schema":"wine-kb/v1","places":[{"id":1,"name":"France","type":"country","country_code":"FR"},{"id":1,"name":"France","type":"country","country_code":"FR"}]}""",
                    """{"schema":"wine-kb/v1","producer_aliases":[{"producer_id":1,"alias_norm":"chateau margaux"}]}""",
                    """{"schema":"wine-kb/v1","cuvees":[{"producer_id":999999,"name":"Invalid reference"}]}""",
                )
                for (json in invalid) {
                    assertTrue(json, runCatching { kb.importOpenWines(json.byteInputStream()) }.isFailure)
                    assertEquals(before, kb.places())
                    assertEquals("Château Margaux", kb.findProducer("Chateau Margaux").first().producer.name)
                    assertEquals(existingFiles, stagingFiles())
                }
                val reordered = """{"producer_aliases":[{"producer_id":1,"alias_norm":"chateau margaux"}],"producers":[{"id":1,"name":"Château Margaux","country_code":"FR","region_id":2}],"schema":"wine-kb/v1"}"""
                kb.importOpenWines(reordered.byteInputStream())
                assertEquals("Château Margaux", kb.findProducer("Chateau Margaux").first().producer.name)
                assertEquals(existingFiles, stagingFiles())
            }
        }
    }
}
