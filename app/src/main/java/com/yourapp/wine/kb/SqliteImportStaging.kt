package com.yourapp.wine.kb

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.yourapp.wine.merge.TextNorm
import java.io.File
import java.io.InputStream

/** File-backed staging keeps an 80MiB interchange out of mobile heap and leaves the live DB untouched on parse failure. */
internal class SqliteImportStaging private constructor(val file: File) : AutoCloseable {
    override fun close() {
        file.delete()
        File(file.path + "-journal").delete()
    }

    companion object {
        fun read(directory: File, input: InputStream): SqliteImportStaging {
            require(directory.isDirectory || directory.mkdirs()) { "Cannot create KB import directory" }
            val stage = SqliteImportStaging(File.createTempFile("wine-kb-import-", ".sqlite", directory))
            try {
                BundledSQLiteDriver().open(stage.file.absolutePath).use { db ->
                    fun exec(sql: String) { db.prepare(sql).use { it.step() } }
                    // Only the disposable staging DB may disable journaling; the live DB remains transactional.
                    exec("PRAGMA journal_mode=OFF")
                    exec("PRAGMA cache_size=-2048")
                    KnowledgeImportSql.staging.forEach(::exec)
                    exec("BEGIN")
                    var records = 0
                    OpenWinesAdapter.stream(input) { row ->
                        insert(db, row)
                        if (++records % 256 == 0) checkSize(db)
                    }
                    db.prepare(KnowledgeImportSql.MISSING_ALIAS_PRODUCER).use {
                        require(!it.step()) { "Alias must reference a producer in the import" }
                    }
                    checkSize(db)
                    exec("COMMIT")
                }
                return stage
            } catch (error: Throwable) {
                stage.close()
                throw error
            }
        }

        private fun checkSize(db: SQLiteConnection) {
            val pages = db.prepare("PRAGMA page_count").use { it.step(); it.getLong(0) }
            val pageSize = db.prepare("PRAGMA page_size").use { it.step(); it.getLong(0) }
            require(pages * pageSize < OpenWinesAdapter.MAX_BYTES) { "Staged KB exceeds the 80 MiB budget" }
        }

        private fun insert(db: SQLiteConnection, row: KnowledgeRecord) {
            when (row) {
                is KnowledgeRecord.PlaceRow -> db.prepare("INSERT INTO place VALUES(?,?,?,?,?,?)").use { s ->
                    val p = row.value
                    s.bindLong(1, p.id); s.bindText(2, p.name); s.bindText(3, p.nameNorm); s.bindText(4, p.type)
                    p.parentId?.let { s.bindLong(5, it) } ?: s.bindNull(5)
                    s.bindText(6, p.countryCode); s.step()
                }
                is KnowledgeRecord.ProducerRow -> db.prepare("INSERT INTO producer VALUES(?,?,?,?,?)").use { s ->
                    val p = row.value
                    s.bindLong(1, p.id); s.bindText(2, p.name); s.bindText(3, p.nameNorm); s.bindText(4, p.countryCode)
                    p.regionId?.let { s.bindLong(5, it) } ?: s.bindNull(5); s.step()
                }
                is KnowledgeRecord.AliasRow -> db.prepare("INSERT OR IGNORE INTO producer_alias VALUES(?,?)").use { s ->
                    s.bindLong(1, row.producerId); s.bindText(2, row.aliasNorm); s.step()
                }
                is KnowledgeRecord.CuveeRow -> db.prepare("INSERT INTO cuvee VALUES(?,?,?) ON CONFLICT(producer_id,name_norm) DO UPDATE SET name=excluded.name").use { s ->
                    s.bindLong(1, row.value.producerId); s.bindText(2, row.value.name)
                    s.bindText(3, TextNorm.normalize(row.value.name)); s.step()
                }
            }
        }
    }
}
