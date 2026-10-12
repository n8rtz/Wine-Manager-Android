package com.n8rtz.winetracker.ui.components

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.n8rtz.winetracker.image.resolveWineImageModel
import java.io.File

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
    expansionResetKey: Any? = Unit,
    details: @Composable (() -> Unit)? = null,
    dateAdded: String? = null,
    onClick: () -> Unit
) {
    var internalExpanded by remember(expansionResetKey) { mutableStateOf(false) }
    val expanded = isExpanded ?: internalExpanded
    val context = LocalContext.current
    val imageModel = remember(context, imageLocalPath, imageUrl) {
        resolveWineImageModel(context, imageLocalPath, imageUrl)
    }

    val backgroundColor = when (type.lowercase()) {
        "red" -> Color(0xFFFFF5F5)
        "white" -> Color(0xFFFFFFF0)
        "rosé", "rose" -> Color(0xFFFFF0F5)
        else -> Color(0xFFF8F9FA)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { 
                    if (details != null && isExpanded == null) {
                        internalExpanded = !internalExpanded
                    }
                    onClick()
                }
            ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Box {
            Column {
                Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(width = 60.dp, height = 80.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(backgroundColor),
                        contentAlignment = Alignment.Center
                    ) {
                        if (imageModel != null) {
                            AsyncImage(
                                model = ImageRequest.Builder(context)
                                    .data(imageModel)
                                    .crossfade(true)
                                    .memoryCachePolicy(CachePolicy.ENABLED)
                                    .diskCachePolicy(if (imageModel is File) CachePolicy.DISABLED else CachePolicy.ENABLED)
                                    .build(),
                                contentDescription = null,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Icon(Icons.Default.WineBar, null, tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
                        }
                    }

                    // 🔍 SEARCH: (rest of middle section):
                    Spacer(modifier = Modifier.width(16.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(text = type, style = MaterialTheme.typography.labelSmall)
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = metadata,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    
                    if (details != null) {
                        Icon(
                            if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = "Expand"
                        )
                    }
                }

                AnimatedVisibility(visible = expanded) {
                    Box(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                        details?.invoke()
                    }
                }
            }

            // Rating Badge in Bottom Right
            if (rating != null) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp),
                    shape = RoundedCornerShape(4.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ) {
                    Text(
                        text = rating.toString(),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            }
        }
    }
}
