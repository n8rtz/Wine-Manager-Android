import com.yourapp.wine.phonecorpus.CsvUploadParts
import java.io.File
import java.nio.file.Files

fun main() {
    val temp=Files.createTempDirectory("csv-parts-check").toFile();var checks=0
    fun verify(value:Boolean) { checks++;check(value) { "CSV split check $checks failed" } }
    fun checkRoundTrip(name:String,header:String,rows:List<String>,limit:Long,expectedParts:Int?=null) {
        val source=File(temp,"$name.csv").apply { writeText(header+rows.joinToString("")) }
        val original=source.readBytes();val hb=header.toByteArray(Charsets.UTF_8)
        val dir=File(temp,name)
        val parts=CsvUploadParts.split(source,dir,limit)
        if(expectedParts!=null) verify(parts.size==expectedParts)
        verify(parts.all { it.length()<=limit })
        val joined=java.io.ByteArrayOutputStream().apply { write(hb);parts.forEach { part ->
            val bytes=part.readBytes();verify(bytes.take(hb.size).toByteArray().contentEquals(hb));write(bytes,hb.size,bytes.size-hb.size)
        } }.toByteArray()
        verify(joined.contentEquals(original));verify(source.readBytes().contentEquals(original))
    }
    try {
        checkRoundTrip("small","\"a\",\"b\"\r\n",listOf("\"é\",\"x\"\r\n"),100,1)
        checkRoundTrip("quoted","\"a\",\"b\"\r\n",List(20) { "\"é,$it\",\"line 1\r\nline \"\"2\"\"\"\r\n" },100)
        checkRoundTrip("no-final-newline","a,b\n",listOf("1,2\n","3,4\n","5,6"),12,2)
        checkRoundTrip("exact-limit","a\n",listOf("1234567\n"),10,1)
        checkRoundTrip("over-limit","a\n",listOf("1234567\n","x\n"),10,2)
        checkRoundTrip("bom","\uFEFFa,b\r\n",List(10) { "\"ß\",\"文\"\r\n" },45)
        // Real decimal-byte limit: ~35 MB becomes exactly two independently headered files.
        checkRoundTrip("35MB","a,b\r\n",List(35_000) { "\""+"x".repeat(995)+"\",1\r\n" },CsvUploadParts.LIMIT_BYTES,2)
        // More than two parts when necessary, never an over-limit tail file.
        checkRoundTrip("many","a\n",List(100) { "123456789\n" },42)
        val bad=File(temp,"oversize.csv").apply { writeText("a\nshort\n"+"x".repeat(100)+"\n") }
        val badDir=File(temp,"bad")
        verify(runCatching { CsvUploadParts.split(bad,badDir,50) }.isFailure)
        verify(badDir.listFiles().orEmpty().isEmpty());verify(bad.exists())
        val broken=File(temp,"broken.csv").apply { writeText("a\n\""+"x".repeat(100)) }
        verify(runCatching { CsvUploadParts.split(broken,File(temp,"broken"),60) }.isFailure)
        println("PASS: $checks actual Kotlin CSV split checks (UTF-8, quoted newlines, escapes, headers, byte caps, 35 MB, errors, round-trip preservation).")
    } finally { temp.deleteRecursively() }
}
