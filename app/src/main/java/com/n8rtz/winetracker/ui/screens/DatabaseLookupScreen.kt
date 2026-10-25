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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yourapp.wine.kb.DatabaseLookupCategory
import com.yourapp.wine.kb.DatabaseLookupMatch
import com.yourapp.wine.kb.ProducerCatalogDatabaseLookup
import com.yourapp.wine.kb.SqliteWineKnowledgeBase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
    var totalProducerCount by remember { mutableStateOf(0) }
    var producerScopeCount by remember { mutableStateOf(0) }
    var catalogReady by remember { mutableStateOf(false) }
    var browseOffset by remember { mutableStateOf(0) }
    var matches by remember { mutableStateOf(emptyList<DatabaseLookupMatch>()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var catalogError by remember { mutableStateOf<String?>(null) }
    var searched by remember { mutableStateOf(false) }

    LaunchedEffect(context) {
        try {
            val catalog = withContext(Dispatchers.IO) {
                ProducerCatalogDatabaseLookup.open(context).use { db -> db.totalProducers to db.countries() }
            }
            totalProducerCount = catalog.first
            countries = catalog.second
            producerScopeCount = catalog.first
        } catch (failure: Exception) {
            catalogError = "Producer catalog unavailable: ${failure.message ?: failure.javaClass.simpleName}"
        } finally {
            catalogReady = true
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
                        ProducerCatalogDatabaseLookup.open(context).use { db ->
                            val count = db.producerCount(country)
                            val page = if (term.isBlank()) db.browseProducers(country,browseOffset,50)
                                else db.searchProducers(term,country,12)
                            count to page
                        }
                    }
                    producerScopeCount = result.first
                    matches = if (appendBrowsePage) matches + result.second else result.second
                } else {
                    matches = withContext(Dispatchers.IO) {
                        SqliteWineKnowledgeBase.open(context).use { db -> db.searchDiagnostic(category, term, 12) }
                    }
                }
                searched = true
            } catch (failure: Exception) {
                error = "Database lookup failed: ${failure.message ?: failure.javaClass.simpleName}"
                matches = emptyList()
                searched = true
            } finally {
                loading = false
            }
        } else {
            matches = emptyList()
            loading = false
            searched = false
        }
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Database Lookup") }, navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
        })
    }) { insets ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(insets).padding(horizontal = 16.dp),
            contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    "Producer lookup searches all ${if (totalProducerCount == 0) "33,160" else totalProducerCount} LWIN producer records. Wine-name and appellation lookups use the scanner knowledge base—not your personal cellar.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
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
                                        browseOffset = 0
                                        menuExpanded = false
                                    }
                                )
                            }
                        }
                    }
                    if (category == DatabaseLookupCategory.PRODUCER) {
                        androidx.compose.foundation.layout.Box {
                            OutlinedButton(onClick = { countryMenuExpanded = true }) {
                                val selected = countries.firstOrNull { it.name == country }
                                Text(selected?.let { "${it.name} · ${it.producerCount} producers" }
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
                                        text = { Text("${option.name} · ${option.producerCount} producers") },
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
                        label = { Text("Search ${category.label.lowercase()}") },
                        placeholder = { Text("Type a name") },
                        singleLine = true
                    )
                    Text(
                        if (category == DatabaseLookupCategory.PRODUCER)
                            "The country selector filters the complete producer catalog (${producerScopeCount} records in this scope). Search ranks every producer in that scope and shows the closest 12; distance 0 means an exact normalized match. Similarity is a ranking aid, not a confidence score."
                        else "Fuzzy search updates as you type. The closest 12 records are shown; distance 0 means an exact normalized match. Similarity is a ranking aid, not a confidence score.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
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
