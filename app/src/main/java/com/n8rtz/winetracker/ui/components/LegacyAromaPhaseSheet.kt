@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.n8rtz.winetracker.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.n8rtz.winetracker.aromawheel.AromaTaxonomy
import com.n8rtz.winetracker.data.local.TastingPhase

@Composable
fun LegacyAromaPhaseSheet(
    aromaIds: List<String>,
    onApply: (Map<String, Set<TastingPhase>>, Set<String>) -> Unit,
    onDismiss: () -> Unit
) {
    val assignments = remember { mutableStateMapOf<String, Set<TastingPhase>>() }
    val removed = remember { mutableStateListOf<String>() }
    val remaining = aromaIds.filterNot { it in removed }
    val allAssigned = remaining.all { assignments[it].orEmpty().isNotEmpty() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Phases") },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                items(aromaIds) { id ->
                    if (id in removed) return@items
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            AromaTaxonomy.describe(id).label,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            TastingPhase.values().forEach { phase ->
                                FilterChip(
                                    selected = phase in assignments[id].orEmpty(),
                                    onClick = {
                                        val current = assignments[id].orEmpty()
                                        assignments[id] = if (phase in current) {
                                            current - phase
                                        } else {
                                            current + phase
                                        }
                                    },
                                    label = { Text(phase.label) }
                                )
                            }
                            TextButton(onClick = { removed.add(id) }) { Text("Remove") }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = allAssigned,
                onClick = {
                    val resolved = remaining.associateWith { assignments.getValue(it) }
                    onApply(resolved, removed.toSet())
                }
            ) { Text("Apply") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
