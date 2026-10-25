@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.n8rtz.winetracker.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import com.yourapp.wine.kb.SqliteWineKnowledgeBase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
fun DatabaseLookupScreen(onBack: () -> Unit) {
    val context = LocalContext.current.applicationContext
    var category by remember { mutableStateOf(DatabaseLookupCategory.PRODUCER) }
    var query by remember { mutableStateOf("") }
    var menuExpanded by remember { mutableStateOf(false) }
    var matches by remember { mutableStateOf(emptyList<DatabaseLookupMatch>()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var searched by remember { mutableStateOf(false) }

    LaunchedEffect(category, query) {
        val term = query.trim()
        error = null
        if (term.isEmpty()) {
            matches = emptyList()
            loading = false
            searched = false
        } else {
            loading = true
            matches = emptyList()
            delay(250) // debounce typing; all database access stays off the UI thread
            try {
                matches = withContext(Dispatchers.IO) {
                    SqliteWineKnowledgeBase.open(context).use { db -> db.searchDiagnostic(category, term, 12) }
                }
                searched = true
            } catch (failure: Exception) {
                error = "Database lookup failed: ${failure.message ?: failure.javaClass.simpleName}"
                matches = emptyList()
                searched = true
            } finally {
                loading = false
            }
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
                    "Search the local scanner knowledge base—not your personal cellar. Wine-name entries are cuvées currently stored in that database.",
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
                                        menuExpanded = false
                                    }
                                )
                            }
                        }
                    }
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Search ${category.label.lowercase()}") },
                        placeholder = { Text("Type a name") },
                        singleLine = true
                    )
                    Text(
                        "Fuzzy search updates as you type. The closest 12 records are shown; distance 0 means an exact normalized match. Similarity is a ranking aid, not a confidence score.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            error?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
            if (searched && !loading && error == null && matches.isEmpty()) {
                item { Text("No records in this category.", style = MaterialTheme.typography.bodyLarge) }
            }
            if (matches.isNotEmpty()) {
                item {
                    Text("Closest matches", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
                items(matches) { match ->
                    LookupMatchCard(match)
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
