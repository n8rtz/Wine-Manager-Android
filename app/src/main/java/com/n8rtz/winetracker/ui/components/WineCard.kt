package com.n8rtz.winetracker.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.ui.graphics.Brush
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
@OptIn(ExperimentalFoundationApi::class)
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
    trailingAction: @Composable (() -> Unit)? = null,
    dateAdded: String? = null,
    drinkingWindow: Pair<Int, Int>? = null,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit
) {
    var internalExpanded by remember { mutableStateOf(false) }
    val expanded = isExpanded ?: internalExpanded
    val context = LocalContext.current
    val imageModel = remember(context, imageLocalPath, imageUrl) {
        resolveWineImageModel(context, imageLocalPath, imageUrl)
    }
    val stripeColor = wineTypeColor(type)
    // The rating no longer rides along in this left-hand metadata line: it is
    // shown as a bare number in the trailing column, between the add-to-rack
    // button and the expand caret (see below).
    val compactMetadata = metadata

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .semantics { contentDescription = "$title, $type wine" },
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
                if (trailingAction != null || details != null || !rating.isNullOrBlank()) {
                    // The whole rack-button / rating / caret stack sits a
                    // little lower on the card than its natural position.
                    Column(
                        modifier = Modifier.offset(y = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        trailingAction?.invoke()
                        run {
                            // Bare number (no star) in a bordered box centred
                            // between the add-to-rack button above and the
                            // expand caret below. The box is filled with the
                            // colour assigned to the rating (see
                            // ratingColor); for a rating range it is an
                            // ombre from the lower bound's colour (left) to
                            // the upper bound's colour (right). Wines with
                            // no rating show "NR" on a plain grey fill.
                            val rated = !rating.isNullOrBlank()
                            val ratingText = rating?.takeIf { it.isNotBlank() } ?: "NR"
                            val ratingValues = if (rated) {
                                ratingText.split("-").mapNotNull { it.trim().toIntOrNull() }
                            } else {
                                emptyList()
                            }
                            val ratingShape = RoundedCornerShape(6.dp)
                            Box(
                                modifier = Modifier
                                    .padding(vertical = 2.dp)
                                    .clip(ratingShape)
                                    .then(
                                        if (ratingValues.size >= 2) {
                                            // Keep each bound's colour solid
                                            // across its own side of the box and
                                            // confine the blend to a narrow
                                            // band in the centre, rather than
                                            // washing the whole box out with a
                                            // full-width transition.
                                            val lowerColor = ratingColor(ratingValues.first())
                                            val upperColor = ratingColor(ratingValues.last())
                                            Modifier.background(
                                                Brush.horizontalGradient(
                                                    0.0f to lowerColor,
                                                    0.42f to lowerColor,
                                                    0.58f to upperColor,
                                                    1.0f to upperColor
                                                )
                                            )
                                        } else if (ratingValues.size == 1) {
                                            Modifier.background(ratingColor(ratingValues.first()))
                                        } else if (!rated) {
                                            Modifier.background(Color(0xFF9E9E9E))
                                        } else {
                                            Modifier.background(MaterialTheme.colorScheme.primary)
                                        }
                                    )
                                    .border(1.5.dp, MaterialTheme.colorScheme.primary, ratingShape)
                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                                    .semantics { contentDescription = if (rated) "Rating $ratingText" else "Not rated" },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = ratingText,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                    maxLines = 1
                                )
                            }
                        }
                        if (details != null) {
                            Box(
                                modifier = Modifier.size(48.dp).offset(y = 4.dp).clip(CircleShape).clickable {
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
