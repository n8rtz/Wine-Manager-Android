@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.n8rtz.winetracker.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.n8rtz.winetracker.aromawheel.AromaTaxonomy
import com.n8rtz.winetracker.aromawheel.AromaWheelTheme

/** Read-only descriptor chips, shared by the cellar preview and tasting-note viewer. */
@Composable
fun AromaChips(aromaIds: List<String>, maxVisible: Int = 8, onOverflowClick: (() -> Unit)? = null) {
    val uniqueIds = aromaIds.distinct()
    if (uniqueIds.isEmpty()) return
    val visible = uniqueIds.take(maxVisible.coerceAtLeast(1))
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        visible.forEach { id ->
            val aroma = AromaTaxonomy.describe(id)
            Surface(
                shape = RoundedCornerShape(50),
                color = Color(AromaWheelTheme.groupColor(aroma.groupId)).copy(alpha = 0.16f),
                contentColor = MaterialTheme.colorScheme.onSurface
            ) {
                Text(
                    aroma.label,
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (uniqueIds.size > visible.size) {
            val overflowLabel: @Composable () -> Unit = {
                Text(
                    "+${uniqueIds.size - visible.size}",
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (onOverflowClick != null) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    onClick = onOverflowClick
                ) { overflowLabel() }
            } else {
                Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceVariant) {
                    overflowLabel()
                }
            }
        }
    }
}
