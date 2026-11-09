package com.n8rtz.winetracker.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.n8rtz.winetracker.image.resolveWineImageModel
import java.io.File

/** Shared compact wine row used by Cellar, Tasting Notes, and Rankings. */
@Composable
fun WineCard(
    title: String,
    subtitle: String,
    type: String,
    metadata: String,
    imageUrl: String? = null,
    imageLocalPath: String? = null,
    rating: String? = null,
    isExpanded: Boolean? = null,
    onExpandToggle: (() -> Unit)? = null,
    details: @Composable (() -> Unit)? = null,
    dateAdded: String? = null,
    drinkingWindow: Pair<Int, Int>? = null,
    onClick: () -> Unit
) {
    var internalExpanded by remember { mutableStateOf(false) }
    val expanded = isExpanded ?: internalExpanded
    val context = LocalContext.current
    val imageModel = remember(context, imageLocalPath, imageUrl) {
        resolveWineImageModel(context, imageLocalPath, imageUrl)
    }
    val stripeColor = wineTypeColor(type)
    val compactMetadata = listOfNotNull(
        metadata.takeIf { it.isNotBlank() },
        rating?.let { "★ $it" }
    ).joinToString("  ·  ")

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "$title, $type wine" }
            .clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = stripeColor)
    ) {
        Column(
            Modifier.fillMaxWidth()
                .padding(start = 5.dp)
                .background(MaterialTheme.colorScheme.surface)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth()
                    .padding(start = 12.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (subtitle.isNotBlank()) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (compactMetadata.isNotBlank()) {
                        Spacer(Modifier.height(3.dp))
                        Text(
                            text = compactMetadata,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                if (details != null) {
                    Box(
                        modifier = Modifier.size(48.dp).clip(CircleShape).clickable {
                            if (onExpandToggle != null) onExpandToggle.invoke() else internalExpanded = !internalExpanded
                        },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = if (expanded) "Collapse wine" else "Expand wine",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier.fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.32f))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(9.dp)
                ) {
                    if (imageModel != null) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            AsyncImage(
                                model = ImageRequest.Builder(context)
                                    .data(imageModel)
                                    .crossfade(true)
                                    .memoryCachePolicy(CachePolicy.ENABLED)
                                    .diskCachePolicy(if (imageModel is File) CachePolicy.DISABLED else CachePolicy.ENABLED)
                                    .build(),
                                contentDescription = "Bottle photo",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.size(width = 48.dp, height = 64.dp)
                                    .clip(RoundedCornerShape(7.dp))
                            )
                            if (drinkingWindow != null) {
                                DrinkingWindowScale(drinkingWindow.first, drinkingWindow.second, Modifier.weight(1f))
                            }
                        }
                    } else if (drinkingWindow != null) {
                        DrinkingWindowScale(drinkingWindow.first, drinkingWindow.second)
                    }
                    details?.invoke()
                }
            }
        }
    }
}

private fun wineTypeColor(type: String): Color = when (type.trim().lowercase()) {
    "red" -> Color(0xFF8B2635)
    "white" -> Color(0xFFB88A20)
    "rosé", "rose" -> Color(0xFFC66D7A)
    "sparkling" -> Color(0xFF728C61)
    else -> Color(0xFF897C72)
}

/** Passive reference scale; deliberately not an interactive control. */
@Composable
private fun DrinkingWindowScale(startYear: Int, endYear: Int, modifier: Modifier = Modifier) {
    val year = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
    val fraction = ((year - startYear).toFloat() / (endYear - startYear).coerceAtLeast(1))
        .coerceIn(0f, 1f)
    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Drink", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            BoxWithConstraints(Modifier.weight(1f).height(12.dp), contentAlignment = Alignment.CenterStart) {
                Row(Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(8.dp))) {
                    Box(Modifier.weight(1f).fillMaxHeight().background(Color(0xFFD8B25E)))
                    Box(Modifier.weight(1f).fillMaxHeight().background(Color(0xFF71966B)))
                    Box(Modifier.weight(1f).fillMaxHeight().background(Color(0xFFA87754)))
                }
                Box(
                    Modifier.offset(x = (maxWidth * fraction) - 5.dp).size(10.dp)
                        .background(MaterialTheme.colorScheme.onSurface, RoundedCornerShape(50))
                )
            }
            Spacer(Modifier.width(8.dp))
            Text("$year", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("$startYear", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("$endYear", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
