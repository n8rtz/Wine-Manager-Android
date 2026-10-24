import com.yourapp.wine.kb.*
import java.io.ByteArrayInputStream
import java.io.InputStream

private const val HEADER = "record_type,id,name,type,parent_id,country_code,region_id,producer_id,alias_norm\n"

fun csvImportChecks() {
    val csv = HEADER + "alias,,,,,,,1,chateau margaux\n" +
        "producer,1,\"Château Margaux\",,,FR,2,,\n" +
        "place,2,Bordeaux,region,1,FR,,,\n" + "place,1,France,country,,FR,,,\n" +
        "cuvee,,\"Pavillon \"\"Rouge\"\", Reserve\",,,,,1,\n"
    var closed = false
    val input = object : ByteArrayInputStream(csv.toByteArray()) { override fun close() { closed = true } }
    val snapshot = OpenWinesAdapter.read(input)
    check(snapshot.producers.single().name == "Château Margaux")
    check(snapshot.producers.single().aliases == listOf("chateau margaux"))
    check(snapshot.cuvees.single().name == "Pavillon \"Rouge\", Reserve")
    check(snapshot.places.first().parentId == 1L && !closed)
    println("PASS: streaming CSV / escaped quotes / aliases before producers / caller stream ownership")

    for (bad in listOf(
        "place,2,Bordeaux,region,bad,FR,,,\n", "place,2,Bordeaux,region,1.5,FR,,,\n",
        "producer,0,Vietti,,,IT,13,,\n", "producer,1,Vietti,,,xx,13,,\n",
        "producer,1,\"Vietti\"junk,,,IT,13,,\n", "producer,1,\"Vietti,,,IT,13,,\n")) {
        check(runCatching { OpenWinesAdapter.read((HEADER + bad).byteInputStream()) }.isFailure) { bad }
    }
    check(runCatching { OpenWinesAdapter.read((HEADER + "alias,,,,,,,55,unknown\n").byteInputStream()) }.isFailure)
    println("PASS: malformed IDs/country/quotes and dangling alias rejected")
}

fun boundedImportChecks() {
    val hugeLine = HEADER + "producer,1," + "X".repeat(ImportReaders.MAX_ROW_CHARS + 1) + ",,,FR,2,,\n"
    check(runCatching { OpenWinesAdapter.stream(hugeLine.byteInputStream()) {} }.isFailure)
    check(runCatching { OpenWinesAdapter.stream(byteArrayOf(0xc3.toByte(), 0x28).inputStream()) {} }.isFailure)
    check(runCatching { ImportReaders.utf8("X".repeat(33).byteInputStream(), 32).use { it.readText() } }.isFailure)
    println("PASS: CSV row / raw-byte / malformed UTF-8 memory guards")
    // Exercise only the genuine Reader guard here; Android JsonReader parsing belongs to Robolectric/device tests.
    val envelope = "{\"schema\":\"wine-kb/v1\",\"id\":123}"
    check(ImportReaders.jsonEnvelope(envelope.reader()).readText() == envelope)
    for (invalid in listOf("1".repeat(33), "[[[[[]]]]]", "\"" + "X".repeat(8193) + "\"", "}")) {
        check(runCatching { ImportReaders.jsonEnvelope(invalid.reader()).readText() }.isFailure)
    }
    println("PASS: JSON lexical string/scalar/depth guards (not Android JSON parser execution)")

    // Generate a large interchange lazily; the callback runs while input is still being read.
    val row = "producer,1,Château Margaux,,,FR,2,,\n".toByteArray()
    val header = HEADER.toByteArray()
    val repetitions = 25_000
    val source = object : InputStream() {
        var position = 0
        var rows = 0
        var inHeader = true
        override fun read(): Int {
            val current = if (inHeader) header else row
            if (!inHeader && rows == repetitions) return -1
            val result = current[position++].toInt() and 255
            if (position == current.size) {
                position = 0
                if (inHeader) inHeader = false else rows++
            }
            return result
        }
    }
    var count = 0
    OpenWinesAdapter.stream(source) {
        check(it is KnowledgeRecord.ProducerRow)
        if (++count == 1) check(source.rows < repetitions)
    }
    check(count == repetitions)
    println("PASS: 25000 lazily generated CSV rows reach the sink without a dump-sized snapshot")
}
