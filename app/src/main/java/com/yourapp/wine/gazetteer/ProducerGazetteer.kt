package com.yourapp.wine.gazetteer

import android.content.Context
import android.os.Looper
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Producer/linked-wine index over packaged SQLite assets. [open] is the verified primary
 * LWIN export; [openExpanded] is the Lab v4 benchmark's expanded index (same primary subset
 * plus source-tagged supplemental candidates) and is never used by production or Prod paths. */
internal class ProducerGazetteer private constructor(
    private val db: SQLiteConnection,
    private val wines: SQLiteConnection,
    override val version: String,
    override val primaryVerified: Boolean,
    override val labSupplemental: Boolean = false,
    private val trackSources: Boolean = false,
) : LinkedWineResolver.Index, AutoCloseable {

    override fun lookup(key: String, limit: Int, checkBudget: () -> Unit): List<ProducerResolver.Entity> {
        require(limit in 1..ProducerResolver.MAX_FUZZY_CANDIDATES)
        checkBudget()
        fun read(sql: String, arg: String): List<ProducerResolver.Entity> {
            val rows = mutableListOf<ProducerResolver.Entity>()
            db.prepare(sql).use { statement ->
                statement.bindText(1, arg)
                statement.bindLong(2, limit.toLong())
                while (statement.step()) {
                    checkBudget()
                    rows += ProducerResolver.Entity(
                        statement.getText(0), statement.getText(1), statement.getText(2),
                        if (trackSources) setOf(statement.getText(3)) else emptySet(),
                    )
                }
            }
            return rows
        }
        // Expanded indexes tag every entity with its source so suggestions stay attributable.
        val exactSql = if (trackSources)
            "SELECT p.id,p.name,p.key,COALESCE(s.source,'lwin') FROM producers p LEFT JOIN producer_sources s ON s.id=p.id WHERE p.key=? ORDER BY p.id LIMIT ?"
            else "SELECT id,name,key FROM producers WHERE key=? ORDER BY id LIMIT ?"
        val exact = read(exactSql, key)
        if (exact.isNotEmpty()) return exact
        val query = key.windowed(3).filter { it.none(Char::isWhitespace) }.distinct().take(24)
            .joinToString(" OR ") { "\"$it\"" }
        val fuzzySql = if (trackSources)
            "SELECT p.id,p.name,p.key,COALESCE(s.source,'lwin') FROM names JOIN producers p ON names.id=p.id LEFT JOIN producer_sources s ON s.id=p.id WHERE names MATCH ? ORDER BY bm25(names),p.id LIMIT ?"
            else "SELECT p.id,p.name,p.key FROM names JOIN producers p ON names.id=p.id WHERE names MATCH ? ORDER BY bm25(names),p.id LIMIT ?"
        return if (query.isBlank()) emptyList() else read(fuzzySql, query)
    }

    override fun lookupWines(
        key: String,
        producerIds: Set<String>,
        limit: Int,
        checkBudget: () -> Unit,
    ): List<LinkedWineResolver.Wine> {
        require(producerIds.size in 1..8 && limit in 1..ProducerResolver.MAX_FUZZY_CANDIDATES)
        checkBudget()
        val ids = producerIds.sorted()
        val slots = ids.joinToString(",") { "?" }
        fun query(fuzzy: Boolean, arg: String): List<LinkedWineResolver.Wine> {
            val from = if (fuzzy) "wine_fts JOIN wine_names n ON wine_fts.rowid=n.id" else "wine_names n"
            val predicate = if (fuzzy) "wine_fts MATCH ?" else "n.key=?"
            val order = if (fuzzy) "bm25(wine_fts)," else ""
            val sql = "SELECT n.id,n.name,n.key,p.producer_id,l.lwin7 FROM $from " +
                "JOIN wine_links l ON l.wine_id=n.id JOIN wine_producers p ON p.id=l.producer_id " +
                "WHERE $predicate AND p.producer_id IN ($slots) ORDER BY ${order}n.id,p.producer_id LIMIT ?"
            val rows = mutableListOf<LinkedWineResolver.Wine>()
            wines.prepare(sql).use { statement ->
                statement.bindText(1, arg)
                ids.forEachIndexed { index, id -> statement.bindText(index + 2, id) }
                statement.bindLong(ids.size + 2, limit.toLong())
                while (statement.step()) {
                    checkBudget()
                    rows += LinkedWineResolver.Wine(
                        statement.getLong(0).toString(), statement.getText(1), statement.getText(2),
                        statement.getText(3), statement.getText(4),
                    )
                }
            }
            return rows
        }
        val exact = query(false, key)
        if (exact.isNotEmpty()) return exact
        val terms = key.windowed(3).filter { it.none(Char::isWhitespace) }.distinct().take(24)
            .joinToString(" OR ") { "\"$it\"" }
        return if (terms.isBlank()) emptyList() else query(true, terms)
    }

    override fun close() {
        try { wines.close() } finally { db.close() }
    }

    companion object {
        const val ASSET = "producer-gazetteer-v1"

        private fun sha(file: File, checkBudget: () -> Unit): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(65536)
                while (true) {
                    checkBudget()
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        }

        fun open(
            context: Context,
            checkBudget: () -> Unit = {},
            onStep: (String, Boolean) -> Unit = { _, _ -> },
        ): ProducerGazetteer {
            check(Looper.myLooper() != Looper.getMainLooper())
            checkBudget()
            onStep("asset.manifest", false)
            val text = context.assets.open("$ASSET/manifest.json").bufferedReader().use { it.readText() }
            require(text.length < 65536)
            val manifest = JSONObject(text)
            check(
                manifest.getInt("schema") == 2 && manifest.getBoolean("primary_verified") &&
                    manifest.getString("verification_kind") == "official-export-acquisition-attested",
            ) { "Primary LWIN export required" }
            check(manifest.getJSONObject("provenance").getString("acquisition") == "official_download_user_supplied")
            check(!manifest.has("support") && !manifest.has("support_attribution")) {
                "Unverified supplemental producer sources are not permitted in the packaged database"
            }
            onStep("asset.manifest", true)

            val producerHash = manifest.getString("database_sha256")
            onStep("asset.producer", false)
            val producerDb = load(context, ASSET, "producers.sqlite", producerHash, checkBudget)
            var wineDb: SQLiteConnection? = null
            try {
                producerDb.prepare("SELECT COUNT(*) FROM producers").use {
                    check(it.step() && it.getLong(0) == manifest.getLong("records"))
                }
                producerDb.prepare("SELECT id FROM names WHERE names MATCH '\"win\"' LIMIT 1").use { it.step() }
                onStep("asset.producer", true)

                onStep("asset.wine", false)
                val wineHash = manifest.getString("wine_database_sha256")
                val loadedWines = load(context, ASSET, "wines.sqlite", wineHash, checkBudget)
                wineDb = loadedWines
                loadedWines.prepare("SELECT COUNT(*) FROM wine_names").use {
                    check(it.step() && it.getLong(0) == manifest.getLong("wine_names"))
                }
                loadedWines.prepare("SELECT COUNT(*) FROM wine_links").use {
                    check(it.step() && it.getLong(0) == manifest.getLong("wine_links"))
                }
                loadedWines.prepare("SELECT rowid FROM wine_fts WHERE wine_fts MATCH '\"win\"' LIMIT 1").use { it.step() }
                onStep("asset.wine", true)
                checkBudget()
                return ProducerGazetteer(
                    producerDb,
                    loadedWines,
                    "${manifest.getString("version")}:producer=$producerHash;wine=$wineHash",
                    primaryVerified = true,
                )
            } catch (error: Exception) {
                wineDb?.close()
                producerDb.close()
                throw error
            }
        }

        private fun load(
            context: Context,
            assetFolder: String,
            name: String,
            hash: String,
            checkBudget: () -> Unit,
        ): SQLiteConnection {
            require(name.matches(Regex("[a-z-]+\\.sqlite")) && hash.matches(Regex("[0-9a-f]{64}")))
            checkBudget()
            val directory = File(context.cacheDir, assetFolder).apply { mkdirs() }
            val target = File(directory, "$hash.sqlite")
            if (!target.exists() || sha(target, checkBudget) != hash) {
                val temporary = File.createTempFile("producer-", ".tmp", directory)
                try {
                    context.assets.open("$assetFolder/$name").use { input ->
                        temporary.outputStream().use { output ->
                            val buffer = ByteArray(65536)
                            var total = 0
                            while (true) {
                                checkBudget()
                                val count = input.read(buffer)
                                if (count < 0) break
                                total += count
                                // Self-imposed packaged-asset sanity bound, NOT a platform limit.
                                // Keep in step with ProducerCatalogDatabaseLookup.MAX_DATABASE_BYTES.
                                // The practical ceiling for cold installs is the caller's copy+hash
                                // time budget (checkBudget), not this byte cap. (Owner, 2026-10-26.)
                                require(total <= 64 * 1024 * 1024)
                                output.write(buffer, 0, count)
                            }
                        }
                    }
                    check(sha(temporary, checkBudget) == hash)
                    checkBudget()
                    java.nio.file.Files.move(
                        temporary.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    )
                } finally {
                    temporary.delete()
                }
            }
            checkBudget()
            val connection = BundledSQLiteDriver().open(target.absolutePath)
            try {
                connection.prepare("PRAGMA query_only=ON").use { it.step() }
                return connection
            } catch (error: Exception) {
                connection.close()
                throw error
            }
        }

        const val EXPANDED_ASSET = "producer-gazetteer-expanded-v1"

        /** Lab v4 benchmark only. Loads the expanded producer index (verified LWIN subset plus
         * source-tagged X-Wines/TTB/OSM supplemental candidates) with the primary wine index.
         * Production and Prod pipelines must use [open]; the primary manifest's ban on packaged
         * supplemental sources is unchanged. */
        fun openExpanded(
            context: Context,
            checkBudget: () -> Unit = {},
            onStep: (String, Boolean) -> Unit = { _, _ -> },
        ): ProducerGazetteer {
            check(Looper.myLooper() != Looper.getMainLooper())
            checkBudget()
            onStep("asset.manifest", false)
            val text = context.assets.open("$EXPANDED_ASSET/manifest.json").bufferedReader().use { it.readText() }
            require(text.length < 65536)
            val manifest = JSONObject(text)
            check(
                manifest.getInt("schema") == 1 &&
                    manifest.getString("kind") == "lab-expanded-producer-index" &&
                    manifest.getBoolean("lab_only"),
            ) { "Lab expanded producer index required" }
            onStep("asset.manifest", true)

            val producerHash = manifest.getString("database_sha256")
            onStep("asset.producer", false)
            val producerDb = load(context, EXPANDED_ASSET, "producers-expanded.sqlite", producerHash, checkBudget)
            var wineDb: SQLiteConnection? = null
            try {
                producerDb.prepare("SELECT COUNT(*) FROM producers").use {
                    check(it.step() && it.getLong(0) == manifest.getLong("records"))
                }
                producerDb.prepare("SELECT COUNT(*) FROM producer_sources").use {
                    check(it.step() && it.getLong(0) == manifest.getLong("supplemental_records"))
                }
                producerDb.prepare("SELECT id FROM names WHERE names MATCH '\"win\"' LIMIT 1").use { it.step() }
                onStep("asset.producer", true)
                onStep("asset.support", true)

                onStep("asset.wine", false)
                val wineHash = manifest.getString("wine_database_sha256")
                val loadedWines = load(context, ASSET, "wines.sqlite", wineHash, checkBudget)
                wineDb = loadedWines
                loadedWines.prepare("SELECT COUNT(*) FROM wine_names").use {
                    check(it.step() && it.getLong(0) == manifest.getLong("wine_names"))
                }
                loadedWines.prepare("SELECT COUNT(*) FROM wine_links").use {
                    check(it.step() && it.getLong(0) == manifest.getLong("wine_links"))
                }
                loadedWines.prepare("SELECT rowid FROM wine_fts WHERE wine_fts MATCH '\"win\"' LIMIT 1").use { it.step() }
                onStep("asset.wine", true)
                checkBudget()
                return ProducerGazetteer(
                    producerDb,
                    loadedWines,
                    "${manifest.getString("version")}:producer=$producerHash;wine=$wineHash",
                    primaryVerified = false,
                    labSupplemental = true,
                    trackSources = true,
                )
            } catch (error: Exception) {
                wineDb?.close()
                producerDb.close()
                throw error
            }
        }
    }
}
