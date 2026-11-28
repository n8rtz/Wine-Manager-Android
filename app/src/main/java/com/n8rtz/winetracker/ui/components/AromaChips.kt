@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.n8rtz.winetracker.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
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
            // Same colour as a selected descriptor on the aroma wheel,
            // for both the fill and the border; standard text colour.
            Surface(
                shape = RoundedCornerShape(50),
                color = Color(AromaWheelTheme.groupSelectedColor(aroma.groupId)),
                contentColor = Color.White,
                border = BorderStroke(1.dp, Color(AromaWheelTheme.groupSelectedColor(aroma.groupId)))
            ) {
                Row(
                    modifier = Modifier.padding(start = 9.dp, end = 13.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    // Coloured dot, as on the aroma wheel screen's chips.
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(Color(AromaWheelTheme.groupColor(aroma.groupId)))
                    )
                    Text(
                        aroma.label,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
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
                // Plain clickable rather than Surface(onClick): the
                // clickable Surface enforces a 48dp minimum interactive
                // height, which made "+N" taller than the other chips and
                // knocked it out of line with its row.
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.clickable(onClick = onOverflowClick)
                ) { overflowLabel() }
            } else {
                Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceVariant) {
                    overflowLabel()
                }
            }
        }
    }
}
