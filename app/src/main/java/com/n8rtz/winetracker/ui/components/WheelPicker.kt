package com.n8rtz.winetracker.ui.components

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * A single scrollable wheel picker (birthday-dial style).
 * Shows a centered highlight row and fading edges.
 *
 * @param items List of display strings.
 * @param selectedIndex Currently selected index.
 * @param onSelectedChange Called when the user scrolls to a new item.
 * @param label Optional header label shown above the wheel.
 * @param itemHeight Height of each row in the wheel.
 * @param visibleCount Number of visible rows (should be odd for centering).
 */
@Composable
fun WheelPicker(
    items: List<String>,
    selectedIndex: Int,
    onSelectedChange: (Int) -> Unit,
    label: String? = null,
    itemHeight: Dp = 48.dp,
    visibleCount: Int = 5,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val halfVisible = visibleCount / 2

    // Pad items with blank entries so the first/last real items can be centered
    val paddedItems = List(halfVisible) { "" } + items + List(halfVisible) { "" }

    // Scroll to initial selection
    LaunchedEffect(selectedIndex, items.size) {
        if (items.isNotEmpty() && selectedIndex in items.indices) {
            // Animate scroll for tap/internal changes to provide visual feedback
            listState.animateScrollToItem(selectedIndex)
        }
    }

    // Detect settled scroll position
    LaunchedEffect(listState.isScrollInProgress, enabled) {
        if (enabled && !listState.isScrollInProgress && items.isNotEmpty()) {
            val centerIndex = listState.firstVisibleItemIndex
            val realIndex = centerIndex.coerceIn(0, items.lastIndex)
            if (realIndex != selectedIndex) {
                onSelectedChange(realIndex)
            }
            // Snap to exact position
            coroutineScope.launch {
                listState.animateScrollToItem(realIndex)
            }
        }
    }

    val totalHeight = itemHeight * visibleCount

    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        if (label != null) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp)
            )
        }

        Box(
            modifier = Modifier
                .height(totalHeight)
                .fillMaxWidth()
                .pointerInput(items, selectedIndex, enabled) {
                    if (enabled) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            val up = waitForUpOrCancellation()
                            if (up != null) {
                                val tapY = up.position.y
                                val halfHeight = size.height / 2f
                                val threshold = itemHeight.toPx() / 2f

                                if ((up.position - up.previousPosition).getDistance() < 24.dp.toPx()) {
                                    if (tapY < halfHeight - threshold) {
                                        onSelectedChange((selectedIndex - 1).coerceAtLeast(0))
                                    } else if (tapY > halfHeight + threshold) {
                                        onSelectedChange((selectedIndex + 1).coerceAtMost(items.lastIndex))
                                    }
                                }
                            }
                        }
                    }
                }
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                userScrollEnabled = enabled
            ) {
                itemsIndexed(paddedItems) { index, item ->
                    val realIndex = index - halfVisible
                    val isCentered = realIndex == selectedIndex
                    Box(
                        modifier = Modifier
                            .height(itemHeight)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = item,
                            style = if (isCentered) {
                                MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold)
                            } else {
                                MaterialTheme.typography.bodyMedium
                            },
                            color = if (isCentered) {
                                Color(0xFF722F37) // Bordeaux Red for selected
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            },
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // Highlight bar behind center row
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .height(itemHeight)
                    .background(
                        Color(0xFF722F37).copy(alpha = 0.08f),
                        RoundedCornerShape(6.dp)
                    )
            )

            // Top fade gradient
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(itemHeight * 1.5f)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.surface,
                                MaterialTheme.colorScheme.surface.copy(alpha = 0f)
                            )
                        )
                    )
            )

            // Bottom fade gradient
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(itemHeight * 1.5f)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.surface.copy(alpha = 0f),
                                MaterialTheme.colorScheme.surface
                            )
                        )
                    )
            )
        }
    }
}

/**
 * A triple-wheel cascading location picker.
 * Country -> Region -> Subregion, each updating the next when changed.
 */
@Composable
fun LocationWheelPicker(
    locationData: Map<String, Map<String, List<String>>>,
    selectedCountry: String?,
    selectedRegion: String?,
    selectedSubregion: String?,
    onLocationChange: (country: String?, region: String?, subregion: String?) -> Unit,
    modifier: Modifier = Modifier
) {
    val allLabel = "All"
    val countries = listOf(allLabel) + locationData.keys.sorted()
    val countryIndex = if (selectedCountry != null) countries.indexOf(selectedCountry).coerceAtLeast(0) else 0

    val regions = if (selectedCountry != null && locationData.containsKey(selectedCountry)) {
        listOf(allLabel) + locationData[selectedCountry]!!.keys.sorted()
    } else {
        listOf(allLabel)
    }
    val regionIndex = if (selectedRegion != null) regions.indexOf(selectedRegion).coerceAtLeast(0) else 0

    val subregions = if (selectedCountry != null && selectedRegion != null) {
        val regionMap = locationData[selectedCountry]
        if (regionMap != null && regionMap.containsKey(selectedRegion)) {
            val subs = regionMap[selectedRegion] ?: emptyList()
            if (subs.isNotEmpty()) listOf(allLabel) + subs.sorted() else listOf(allLabel)
        } else {
            listOf(allLabel)
        }
    } else {
        listOf(allLabel)
    }
    val subregionIndex = if (selectedSubregion != null) subregions.indexOf(selectedSubregion).coerceAtLeast(0) else 0

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        WheelPicker(
            items = countries,
            selectedIndex = countryIndex,
            onSelectedChange = { idx ->
                val newCountry = countries[idx].takeIf { it != allLabel }
                Log.d("WineTracker", "LocationPicker: Country changed to $newCountry")
                onLocationChange(newCountry, null, null)
            },
            label = "Country",
            modifier = Modifier.weight(1f)
        )

        WheelPicker(
            items = regions,
            selectedIndex = regionIndex,
            onSelectedChange = { idx ->
                val newRegion = regions[idx].takeIf { it != allLabel }
                Log.d("WineTracker", "LocationPicker: Region changed to $newRegion")
                onLocationChange(selectedCountry, newRegion, null)
            },
            label = "Region",
            modifier = Modifier.weight(1f)
        )

        WheelPicker(
            items = subregions,
            selectedIndex = subregionIndex,
            onSelectedChange = { idx ->
                val newSubregion = subregions[idx].takeIf { it != allLabel }
                Log.d("WineTracker", "LocationPicker: Subregion changed to $newSubregion")
                onLocationChange(selectedCountry, selectedRegion, newSubregion)
            },
            label = "Subregion",
            modifier = Modifier.weight(1f)
        )
    }
}
