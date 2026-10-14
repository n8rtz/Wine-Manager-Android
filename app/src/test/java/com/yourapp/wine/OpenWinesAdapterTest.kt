package com.yourapp.wine

import com.yourapp.wine.kb.KnowledgeRecord
import com.yourapp.wine.kb.OpenWinesAdapter
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
class OpenWinesAdapterTest {
    @Test fun jsonOrderAndEscapedNamesStreamIntoTypedRows() {
        val json = """{
          "producer_aliases":[{"producer_id":1,"alias_norm":"CHATEAU MARGAUX"}],
          "cuvees":[{"producer_id":1,"name":"Pavillon \"Rouge\""}],
          "producers":[{"id":1,"name":"Ch\u00e2teau Margaux","country_code":"FR","region_id":2}],
          "places":[],"schema":"wine-kb/v1"
        }"""
        val records = mutableListOf<KnowledgeRecord>()
        OpenWinesAdapter.stream(json.byteInputStream(), records::add)
        assertEquals(3, records.size)
        val snapshot = OpenWinesAdapter.read(json.byteInputStream())
        assertEquals("Château Margaux", snapshot.producers.single().name)
        assertEquals(listOf("chateau margaux"), snapshot.producers.single().aliases)
        assertEquals("Pavillon \"Rouge\"", snapshot.cuvees.single().name)
    }

    @Test fun malformedJsonAndFractionalIdsAreNeverSilentlyCoerced() {
        val invalid = listOf(
            """{"schema":"wine-kb/v1","producers":[{"id":1.5,"name":"Vietti","country_code":"IT"}]}""",
            """{"schema":"wine-kb/v1","producers":[{"id":1,"name":"Vietti","country_code":"IT","region_id":"bad"}]}""",
            """{"schema":"wine-kb/v1","places":[],"places":[]}""",
            """{"schema":"wine-kb/v1","producers":[{"id":1,"id":2,"name":"Vietti","country_code":"IT"}]}""",
            """{"schema":"wrong","places":[]}""",
            """{"places":[]}""",
            """{"schema":"wine-kb/v1"} {"schema":"wine-kb/v1"}""",
            """{"schema":"wine-kb/v1","producers":[{"id":1,"name":77,"country_code":"IT"}]}""",
        )
        for (json in invalid) assertTrue(json, runCatching { OpenWinesAdapter.read(json.byteInputStream()) }.isFailure)
    }

    @Test fun oversizedJsonValueIsStoppedBeforeAWholeDumpIsBuffered() {
        val input = """{"schema":"wine-kb/v1","producers":[{"id":1,"name":"""" + "X".repeat(9000) + """","country_code":"FR"}]}"""
        assertTrue(runCatching { OpenWinesAdapter.stream(input.byteInputStream()) {} }.isFailure)
    }

    @Test fun csvHandlesCrLfBomAndStrictNullableIds() {
        val header = "record_type,id,name,type,parent_id,country_code,region_id,producer_id,alias_norm\r\n"
        val valid = "\uFEFF" + header + "producer,1,Vietti,,,IT,,,\r\n"
        assertNull(OpenWinesAdapter.read(valid.byteInputStream()).producers.single().regionId)
        assertTrue(runCatching {
            OpenWinesAdapter.read((header + "place,2,Bordeaux,region,not-a-number,FR,,,\n").byteInputStream())
        }.isFailure)
    }
}
