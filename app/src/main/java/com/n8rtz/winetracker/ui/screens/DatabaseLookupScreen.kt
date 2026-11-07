@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.n8rtz.winetracker.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yourapp.wine.kb.DatabaseLookupCategory
import com.yourapp.wine.kb.DatabaseLookupMatch
import com.yourapp.wine.kb.DatabaseNameLookup
import com.yourapp.wine.kb.ProducerCatalogDatabaseLookup
import com.yourapp.wine.kb.SqliteWineKnowledgeBase
import com.yourapp.wine.kb.SupplementedWineKnowledgeBase
import com.yourapp.wine.kb.WineIndexDatabaseLookup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

@Composable
fun DatabaseLookupScreen(onBack: () -> Unit) {
    val context = LocalContext.current.applicationContext
    var category by remember { mutableStateOf(DatabaseLookupCategory.PRODUCER) }
    var country by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var menuExpanded by remember { mutableStateOf(false) }
    var countryMenuExpanded by remember { mutableStateOf(false) }
    var countries by remember { mutableStateOf(emptyList<ProducerCatalogDatabaseLookup.Country>()) }
    var sources by remember { mutableStateOf(emptyList<ProducerCatalogDatabaseLookup.Source>()) }
    var totalProducerCount by remember { mutableStateOf(0) }
    var producerScopeCount by remember { mutableStateOf(0) }
    var catalogReady by remember { mutableStateOf(false) }
    var browseOffset by remember { mutableStateOf(0) }
    var matches by remember { mutableStateOf(emptyList<DatabaseLookupMatch>()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var catalogError by remember { mutableStateOf<String?>(null) }
    var lwinWineNameCount by remember { mutableStateOf<Int?>(null) }
    var scannerWineNameCount by remember { mutableStateOf<Int?>(null) }
    var bundledAppellationCount by remember { mutableStateOf<Int?>(null) }
    var scannerAppellationCount by remember { mutableStateOf<Int?>(null) }
    var searched by remember { mutableStateOf(false) }

    // One Producer lookup searches every packaged producer database: the expanded
    // index is their union (verbatim LWIN subset plus source-tagged X-Wines/TTB/OSM
    // candidates), and each result's context names the database it came from.
    // Owner rule (.agents/instructions.md #17): future packaged databases must join
    // the matching category's search and its "Databases searched" listing below.
    LaunchedEffect(context, category) {
        catalogReady = false
        try {
            withContext(Dispatchers.IO) {
                // Load each database's count independently: a failure in one must not
                // blank the others, but the first failure is still surfaced below.
                var failure: Exception? = null
                fun attempt(block: () -> Unit) {
                    try { block() }
                    catch (error: CancellationException) { throw error }
                    catch (error: Exception) { if (failure == null) failure = error }
                }
                when (category) {
                    DatabaseLookupCategory.PRODUCER -> attempt {
                        ProducerCatalogDatabaseLookup.open(
                            context, ProducerCatalogDatabaseLookup.Catalog.EXPANDED
                        ).use { db ->
                            totalProducerCount = db.totalProducers
                            countries = db.countries()
                            sources = db.sources()
                            producerScopeCount = db.totalProducers
                        }
                    }
                    DatabaseLookupCategory.WINE_NAME -> {
                        attempt {
                            WineIndexDatabaseLookup.open(context).use { lwinWineNameCount = it.totalWines }
                        }
                        attempt {
                            SqliteWineKnowledgeBase.open(context).use { scannerWineNameCount = it.wineNameCount() }
                        }
                    }
                    DatabaseLookupCategory.APPELLATION -> attempt {
                        SqliteWineKnowledgeBase.open(context).use { base ->
                            val appellations = SupplementedWineKnowledgeBase(base).places()
                                .filter { it.type == "appellation" }
                            // Supplement-added places carry virtual (negative) ids;
                            // scanner-KB place rows keep their own ids.
                            bundledAppellationCount = appellations.count { it.id < 0 }
                            scannerAppellationCount = appellations.count { it.id >= 0 }
                        }
                    }
                }
                failure?.let { throw it }
            }
        } catch (failure: CancellationException) {
            // Superseded run (category changed / effect restarted): Compose cancels
            // with "The coroutine scope left the composition". That is control flow,
            // not a database failure — never surface it as an error.
            throw failure
        } catch (failure: Exception) {
            catalogError = "Database unavailable: ${failure.message ?: failure.javaClass.simpleName}"
        } finally {
            if (isActive) catalogReady = true
        }
    }

    LaunchedEffect(category, country, query, browseOffset, catalogReady) {
        if (category == DatabaseLookupCategory.PRODUCER && !catalogReady) return@LaunchedEffect
        val term = query.trim()
        val browsing = category == DatabaseLookupCategory.PRODUCER && term.isEmpty()
        val appendBrowsePage = browsing && browseOffset > 0
        error = null
        if (browsing || term.isNotEmpty()) {
            loading = true
            if (!appendBrowsePage) matches = emptyList()
            if (!browsing) delay(250) // debounce typing; all database access stays off the UI thread
            try {
                if (category == DatabaseLookupCategory.PRODUCER) {
                    val result = withContext(Dispatchers.IO) {
                        ProducerCatalogDatabaseLookup.open(context, ProducerCatalogDatabaseLookup.Catalog.EXPANDED).use { db ->
                            val count = db.producerCount(country)
                            val page = if (term.isBlank())
                                db.browseProducers(country = country, offset = browseOffset, limit = 50)
                                else db.searchProducers(query = term, country = country, limit = 12)
                            count to page
                        }
                    }
                    producerScopeCount = result.first
                    matches = if (appendBrowsePage) matches + result.second else result.second
                } else if (category == DatabaseLookupCategory.WINE_NAME) {
                    matches = withContext(Dispatchers.IO) {
                        val lwinMatches = WineIndexDatabaseLookup.open(context).use { wines ->
                            val ranked = wines.searchWines(term, 12)
                            if (ranked.isEmpty()) ranked else {
                                val links = ranked.associate { match ->
                                    match.identity to (match.identity.removePrefix("lwin-wine:").toLongOrNull()
                                        ?.let(wines::producerIdsForWine).orEmpty())
                                }
                                val names = ProducerCatalogDatabaseLookup.open(
                                    context, ProducerCatalogDatabaseLookup.Catalog.EXPANDED
                                ).use { it.producerNames(links.values.flatten()) }
                                ranked.map { match ->
                                    val producers = links[match.identity].orEmpty()
                                        .mapNotNull(names::get).distinct()
                                    match.copy(context = when {
                                        producers.isEmpty() -> ""
                                        producers.size == 1 -> "Producer: ${producers.first()}"
                                        else -> "Producers: ${producers.take(3).joinToString(", ")}" +
                                            if (producers.size > 3) " +${producers.size - 3} more" else ""
                                    })
                                }
                            }
                        }
                        val scannerMatches = SqliteWineKnowledgeBase.open(context).use { db ->
                            db.searchDiagnostic(DatabaseLookupCategory.WINE_NAME, term, 12)
                        }
                        (lwinMatches + scannerMatches).sortedWith(
                            compareByDescending<DatabaseLookupMatch> { it.similarityPercent }
                                .thenBy { it.editDistance }.thenBy { it.name.lowercase() }
                        ).take(12)
                    }
                } else {
                    // Appellation searches the supplemented origins view — the same
                    // place list the scanner uses — not the raw scanner-KB fixture.
                    matches = withContext(Dispatchers.IO) {
                        SqliteWineKnowledgeBase.open(context).use { base ->
                            DatabaseNameLookup.rank(
                                term,
                                DatabaseNameLookup.appellationCandidates(SupplementedWineKnowledgeBase(base).places()),
                                12
                            )
                        }
                    }
                }
                searched = true
            } catch (failure: CancellationException) {
                // Typing or a database-finished-loading restart superseded this search.
                // Let the cancellation propagate silently; the new run owns the state.
                throw failure
            } catch (failure: Exception) {
                error = "Database lookup failed: ${failure.message ?: failure.javaClass.simpleName}"
                matches = emptyList()
                searched = true
            } finally {
                if (isActive) loading = false
            }
        } else {
            matches = emptyList()
            loading = false
            searched = false
        }
    }

    // Keep the title, but let the header scroll away with the content.
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior(rememberTopAppBarState())

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text("Database Lookup") },
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { insets ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(insets).padding(horizontal = 16.dp),
            contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Search in", style = MaterialTheme.typography.labelLarge)
                    androidx.compose.foundation.layout.Box {
                        OutlinedButton(onClick = { menuExpanded = true }) {
                            Text(category.label)
                        }
                        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                            DatabaseLookupCategory.entries.forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(option.label) },
                                    onClick = {
                                        category = option
                                        country = null
                                        browseOffset = 0
                                        menuExpanded = false
                                    }
                                )
                            }
                        }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Databases searched", style = MaterialTheme.typography.labelLarge)
                        when (category) {
                            DatabaseLookupCategory.PRODUCER -> sources.forEach { source ->
                                Text(
                                    "• ${ProducerCatalogDatabaseLookup.sourceLabel(source.id)}: ${"%,d".format(source.producerCount)} records",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            DatabaseLookupCategory.WINE_NAME -> {
                                Text(
                                    "• LWIN wine names: ${lwinWineNameCount?.let { "%,d".format(it) } ?: "…"} records",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    "• Scanner knowledge base wine names: ${scannerWineNameCount?.let { "%,d".format(it) } ?: "…"} records",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            DatabaseLookupCategory.APPELLATION -> {
                                Text(
                                    "• Bundled origins appellations: ${bundledAppellationCount?.let { "%,d".format(it) } ?: "…"} records",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    "• Scanner knowledge base appellations: ${scannerAppellationCount?.let { "%,d".format(it) } ?: "…"} records",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    if (category == DatabaseLookupCategory.PRODUCER) {
                        androidx.compose.foundation.layout.Box {
                            OutlinedButton(onClick = { countryMenuExpanded = true }) {
                                val selected = countries.firstOrNull { it.name == country }
                                Text(selected?.let { "${it.name.ifBlank { "No country recorded" }} · ${it.producerCount} producers" }
                                    ?: "All countries · ${totalProducerCount} producers")
                            }
                            DropdownMenu(
                                expanded = countryMenuExpanded,
                                onDismissRequest = { countryMenuExpanded = false },
                                modifier = Modifier.heightIn(max = 400.dp)
                            ) {
                                DropdownMenuItem(
                                    text = { Text("All countries · ${totalProducerCount} producers") },
                                    onClick = { country = null; browseOffset = 0; countryMenuExpanded = false }
                                )
                                countries.forEach { option ->
                                    DropdownMenuItem(
                                        text = { Text("${option.name.ifBlank { "No country recorded" }} · ${option.producerCount} producers") },
                                        onClick = { country = option.name; browseOffset = 0; countryMenuExpanded = false }
                                    )
                                }
                            }
                        }
                    }
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it; browseOffset = 0 },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Search", maxLines = 1) },
                        singleLine = true
                    )
                }
            }
            if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            catalogError?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
            error?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
            if (searched && !loading && error == null && catalogError == null && matches.isEmpty()) {
                item { Text("No records in this category.", style = MaterialTheme.typography.bodyLarge) }
            }
            if (matches.isNotEmpty()) {
                item {
                    Text(
                        if (category == DatabaseLookupCategory.PRODUCER && query.isBlank())
                            "Producer catalog · ${matches.size} of $producerScopeCount in scope"
                        else if (category == DatabaseLookupCategory.PRODUCER)
                            "Closest matches · ${matches.size} of $producerScopeCount producers in scope"
                        else "Closest matches",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                items(matches) { match ->
                    LookupMatchCard(match)
                }
            }
            if (category == DatabaseLookupCategory.PRODUCER && query.isBlank() && browseOffset + matches.size < producerScopeCount) {
                item {
                    OutlinedButton(
                        onClick = { browseOffset += 50 },
                        enabled = !loading,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Load next 50 producers") }
                }
            }
        }
    }
}

@Composable
private fun LookupMatchCard(match: DatabaseLookupMatch) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(match.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
            if (match.context.isNotBlank()) {
                Text(match.context, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                "Edit distance ${match.editDistance} · similarity ${match.similarityPercent}%",
                style = MaterialTheme.typography.labelMedium
            )
            if (match.matchedAs != match.name) {
                Text("Matched through: ${match.matchedAs}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
