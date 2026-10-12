@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class
)

package com.n8rtz.winetracker.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.n8rtz.winetracker.data.local.ConsumptionEventWithDefinition
import com.n8rtz.winetracker.data.local.TastingNoteWithDefinition
import com.n8rtz.winetracker.data.local.WineBottleWithDefinition
import com.n8rtz.winetracker.data.local.displayVintage
import com.n8rtz.winetracker.ui.theme.WineRed
import com.n8rtz.winetracker.viewmodels.TastingNoteViewModel
import com.n8rtz.winetracker.viewmodels.WineViewModel
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

private const val MIN_RATING = 50.0
private const val MAX_RATING = 100.0
private val PREFERRED_WINE_TYPE_ORDER = listOf("Red", "White", "Rosé", "Sparkling", "Dessert", "Other")
private val ParetoGreen = Color(0xFF35A853)
private val TrendLineColor = Color(0xFF74808A)

private data class WineVintageKey(val definitionId: Long, val vintage: String)

private data class ParetoWinePoint(
    val key: WineVintageKey,
    val maker: String,
    val name: String,
    val vintage: String,
    val type: String,
    val price: Double,
    val averageRating: Double,
    val tastingCount: Int
)

private data class LogPriceScale(
    val minimumLogPrice: Double,
    val maximumLogPrice: Double,
    val ticks: List<Double>
) {
    fun fraction(price: Double): Float =
        ((ln(price) - minimumLogPrice) / (maximumLogPrice - minimumLogPrice))
            .coerceIn(0.0, 1.0)
            .toFloat()
}

private data class PriceRatingTrend(val slope: Double, val intercept: Double) {
    fun ratingAt(logPrice: Double): Double = intercept + slope * logPrice
}

@Composable
fun ParetoScreen(
    wineViewModel: WineViewModel,
    tastingNoteViewModel: TastingNoteViewModel,
    onBack: () -> Unit
) {
    val tastingNotes by tastingNoteViewModel.tastingNotes.collectAsState(initial = emptyList())
    val activeInventory by wineViewModel.activeInventory.collectAsStateWithLifecycle(initialValue = emptyList())
    val archivedInventory by wineViewModel.archivedInventory.collectAsStateWithLifecycle(initialValue = emptyList())
    val consumptionHistory by wineViewModel.consumptionHistory.collectAsStateWithLifecycle(initialValue = emptyList())

    val points = remember(tastingNotes, activeInventory, archivedInventory, consumptionHistory) {
        buildParetoPoints(tastingNotes, activeInventory, archivedInventory, consumptionHistory)
    }
    val availableTypes = remember(points) { sortWineTypes(points.map { it.type }.distinct()) }
    var visibleTypes by remember { mutableStateOf<Set<String>?>(null) }
    var selectedPointKey by remember { mutableStateOf<WineVintageKey?>(null) }

    val visiblePoints = remember(points, visibleTypes) {
        if (visibleTypes == null) points else points.filter { it.type in visibleTypes!! }
    }
    val frontier = remember(visiblePoints) { paretoFrontier(visiblePoints) }
    val redFrontier = remember(visiblePoints) {
        paretoFrontier(visiblePoints.filter { it.type.equals("Red", ignoreCase = true) })
    }
    val whiteFrontier = remember(visiblePoints) {
        paretoFrontier(visiblePoints.filter { it.type.equals("White", ignoreCase = true) })
    }
    val frontierKeys = remember(frontier) { frontier.map { it.key }.toSet() }
    val hasTrendLine = remember(visiblePoints) { calculateTrend(visiblePoints) != null }
    val selectedPoint = visiblePoints.firstOrNull { it.key == selectedPointKey }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Price vs. Rating", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "Compare each wine’s average tasting score with its bottle price. Higher scores and lower prices indicate stronger value.",
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (availableTypes.isNotEmpty()) {
                Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("Filter by wine type", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            FilterChip(
                                selected = visibleTypes == null,
                                onClick = {
                                    visibleTypes = null
                                    selectedPointKey = null
                                },
                                label = { Text("All") },
                                shape = RoundedCornerShape(8.dp),
                                colors = paretoFilterChipColors()
                            )
                            availableTypes.forEach { type ->
                                val selected = visibleTypes?.contains(type) ?: true
                                FilterChip(
                                    selected = selected,
                                    onClick = {
                                        val currentlyVisible = visibleTypes ?: availableTypes.toSet()
                                        val updated = if (type in currentlyVisible) {
                                            currentlyVisible - type
                                        } else {
                                            currentlyVisible + type
                                        }
                                        visibleTypes = updated.takeUnless { it.containsAll(availableTypes) }
                                        selectedPointKey = null
                                    },
                                    label = { Text(type) },
                                    leadingIcon = { WineTypeSwatch(type, Modifier.size(12.dp)) },
                                    shape = RoundedCornerShape(8.dp),
                                    colors = paretoFilterChipColors()
                                )
                            }
                        }
                    }
                }
            }

            when {
                points.isEmpty() -> {
                    EmptyParetoState(
                        title = "No wines to plot yet",
                        message = "Add a tasting note with an overall rating and a recorded bottle price. Each dot represents one wine and vintage."
                    )
                }
                visiblePoints.isEmpty() -> {
                    EmptyParetoState(
                        title = "No wines match these filters",
                        message = "Turn a wine type back on, or select All to show every type."
                    )
                }
                else -> {
                    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                        Column(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Pareto frontier", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                    Text(
                                        "${visiblePoints.size} rated wine${if (visiblePoints.size == 1) "" else "s"}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Text("Rating", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }

                            Text(
                                "Each dot is one rated wine/vintage, using its average score and median available bottle price. Wines without a known price are omitted.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            ParetoScatterPlot(
                                points = visiblePoints,
                                frontier = frontier,
                                selectedPoint = selectedPoint,
                                selectedOnFrontier = selectedPoint?.let { it.key in frontierKeys } == true,
                                onPointSelected = { selectedPointKey = it }
                            )
                            Text(
                                "Bottle price ($, logarithmic scale)",
                                modifier = Modifier.fillMaxWidth(),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )

                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                ParetoLineLegend(ParetoGreen, dashed = false, label = "Pareto frontier")
                                if (hasTrendLine) {
                                    ParetoLineLegend(TrendLineColor, dashed = true, label = "Price / rating trend")
                                }
                            }
                            Text(
                                "Higher scores are up and lower prices are left. The green frontier marks wines for which no cheaper wine has an equal or higher average score. " +
                                    (if (hasTrendLine) "The dashed line summarizes the overall trend. " else "") +
                                    "Tap a dot for details.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    ParetoFrontierDashboard(
                        redFrontier = redFrontier,
                        whiteFrontier = whiteFrontier
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun EmptyParetoState(title: String, message: String) {
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun ParetoScatterPlot(
    points: List<ParetoWinePoint>,
    frontier: List<ParetoWinePoint>,
    selectedPoint: ParetoWinePoint?,
    selectedOnFrontier: Boolean,
    onPointSelected: (WineVintageKey?) -> Unit
) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val colorScheme = MaterialTheme.colorScheme
    val scale = remember(points) { createLogPriceScale(points) }
    val trend = remember(points) { calculateTrend(points) }
    val axisColor = colorScheme.onSurfaceVariant
    val gridColor = colorScheme.onSurfaceVariant.copy(alpha = 0.16f)
    val axisLabelPaint = remember(axisColor) {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = axisColor.toArgb()
            textAlign = android.graphics.Paint.Align.RIGHT
            typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
        }
    }
    val priceLabelPaint = remember(axisColor) {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = axisColor.toArgb()
            textAlign = android.graphics.Paint.Align.CENTER
            typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
        }
    }
    val leftInsetPx = with(density) { 44.dp.toPx() }
    val rightInsetPx = with(density) { 12.dp.toPx() }
    val topInsetPx = with(density) { 12.dp.toPx() }
    val bottomInsetPx = with(density) { 34.dp.toPx() }
    val touchRadiusPx = with(density) { 24.dp.toPx() }

    BoxWithConstraints(
        modifier = Modifier.fillMaxWidth().height(292.dp)
    ) {
        val chartWidthPx = constraints.maxWidth.toFloat()
        val chartHeightPx = constraints.maxHeight.toFloat()
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(points, scale, selectedPoint?.key) {
                    detectTapGestures { tap ->
                        val canvasWidth = size.width.toFloat()
                        val canvasHeight = size.height.toFloat()
                        val nearest = points.minByOrNull { point ->
                            val position = chartPointOffset(
                                price = point.price,
                                rating = point.averageRating,
                                scale = scale,
                                canvasWidth = canvasWidth,
                                canvasHeight = canvasHeight,
                                leftInset = leftInsetPx,
                                rightInset = rightInsetPx,
                                topInset = topInsetPx,
                                bottomInset = bottomInsetPx
                            )
                            val dx = position.x - tap.x
                            val dy = position.y - tap.y
                            dx * dx + dy * dy
                        }
                        if (nearest == null) {
                            onPointSelected(null)
                        } else {
                            val position = chartPointOffset(
                                price = nearest.price,
                                rating = nearest.averageRating,
                                scale = scale,
                                canvasWidth = canvasWidth,
                                canvasHeight = canvasHeight,
                                leftInset = leftInsetPx,
                                rightInset = rightInsetPx,
                                topInset = topInsetPx,
                                bottomInset = bottomInsetPx
                            )
                            val dx = position.x - tap.x
                            val dy = position.y - tap.y
                            onPointSelected(if (dx * dx + dy * dy <= touchRadiusPx * touchRadiusPx) nearest.key else null)
                        }
                    }
                }
        ) {
            val plotLeft = leftInsetPx
            val plotRight = size.width - rightInsetPx
            val plotTop = topInsetPx
            val plotBottom = size.height - bottomInsetPx
            val plotWidth = (plotRight - plotLeft).coerceAtLeast(1f)
            val plotHeight = (plotBottom - plotTop).coerceAtLeast(1f)
            val labelTextSize = 11.sp.toPx()
            axisLabelPaint.textSize = labelTextSize
            priceLabelPaint.textSize = labelTextSize

            val scoreTicks = (50..100 step 10).toList()
            scoreTicks.forEach { score ->
                val y = plotBottom - ((score - MIN_RATING) / (MAX_RATING - MIN_RATING)).toFloat() * plotHeight
                drawLine(
                    color = gridColor,
                    start = Offset(plotLeft, y),
                    end = Offset(plotRight, y),
                    strokeWidth = 1.dp.toPx()
                )
            }
            scale.ticks.forEach { tick ->
                val x = plotLeft + scale.fraction(tick) * plotWidth
                drawLine(
                    color = gridColor,
                    start = Offset(x, plotTop),
                    end = Offset(x, plotBottom),
                    strokeWidth = 1.dp.toPx()
                )
            }

            if (trend != null) {
                val trendStart = chartPointOffset(
                    price = exp(scale.minimumLogPrice),
                    rating = trend.ratingAt(scale.minimumLogPrice),
                    scale = scale,
                    canvasWidth = size.width,
                    canvasHeight = size.height,
                    leftInset = leftInsetPx,
                    rightInset = rightInsetPx,
                    topInset = topInsetPx,
                    bottomInset = bottomInsetPx
                )
                val trendEnd = chartPointOffset(
                    price = exp(scale.maximumLogPrice),
                    rating = trend.ratingAt(scale.maximumLogPrice),
                    scale = scale,
                    canvasWidth = size.width,
                    canvasHeight = size.height,
                    leftInset = leftInsetPx,
                    rightInset = rightInsetPx,
                    topInset = topInsetPx,
                    bottomInset = bottomInsetPx
                )
                drawLine(
                    color = TrendLineColor.copy(alpha = 0.85f),
                    start = trendStart,
                    end = trendEnd,
                    strokeWidth = 2.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(7.dp.toPx(), 5.dp.toPx()))
                )
            }

            val frontierPath = Path()
            frontier.distinctBy { it.price to it.averageRating }.forEachIndexed { index, point ->
                val position = chartPointOffset(
                    price = point.price,
                    rating = point.averageRating,
                    scale = scale,
                    canvasWidth = size.width,
                    canvasHeight = size.height,
                    leftInset = leftInsetPx,
                    rightInset = rightInsetPx,
                    topInset = topInsetPx,
                    bottomInset = bottomInsetPx
                )
                if (index == 0) frontierPath.moveTo(position.x, position.y) else frontierPath.lineTo(position.x, position.y)
            }
            if (frontier.distinctBy { it.price to it.averageRating }.size > 1) {
                drawPath(
                    path = frontierPath,
                    color = ParetoGreen,
                    style = Stroke(width = 2.5.dp.toPx())
                )
            }

            points.forEach { point ->
                val position = chartPointOffset(
                    price = point.price,
                    rating = point.averageRating,
                    scale = scale,
                    canvasWidth = size.width,
                    canvasHeight = size.height,
                    leftInset = leftInsetPx,
                    rightInset = rightInsetPx,
                    topInset = topInsetPx,
                    bottomInset = bottomInsetPx
                )
                val pointColor = wineTypeColor(point.type)
                val radius = 6.dp.toPx()
                drawCircle(pointColor, radius = radius, center = position)
                drawCircle(
                    color = wineTypeOutline(point.type),
                    radius = radius,
                    center = position,
                    style = Stroke(width = 1.25.dp.toPx())
                )
                if (point.key == selectedPoint?.key) {
                    drawCircle(
                        color = colorScheme.primary,
                        radius = 10.dp.toPx(),
                        center = position,
                        style = Stroke(width = 2.dp.toPx())
                    )
                }
            }

            val nativeCanvas = drawContext.canvas.nativeCanvas
            scoreTicks.forEach { score ->
                val y = plotBottom - ((score - MIN_RATING) / (MAX_RATING - MIN_RATING)).toFloat() * plotHeight
                nativeCanvas.drawText(score.toString(), plotLeft - 8.dp.toPx(), y + labelTextSize * 0.35f, axisLabelPaint)
            }
            scale.ticks.forEach { tick ->
                val x = plotLeft + scale.fraction(tick) * plotWidth
                val label = formatPrice(tick)
                val labelWidth = priceLabelPaint.measureText(label)
                val labelX = (x - labelWidth / 2f).coerceIn(plotLeft, (plotRight - labelWidth).coerceAtLeast(plotLeft))
                nativeCanvas.drawText(label, labelX + labelWidth / 2f, size.height - 5.dp.toPx(), priceLabelPaint)
            }
        }

        selectedPoint?.let { point ->
            val popupWidth = (maxWidth - 16.dp).coerceAtMost(280.dp)
            val popupWidthPx = with(density) { popupWidth.toPx() }
            val edgeMarginPx = with(density) { 8.dp.toPx() }
            val estimatedPopupHeightPx = with(density) { 156.dp.toPx() }
            val pointPosition = chartPointOffset(
                price = point.price,
                rating = point.averageRating,
                scale = scale,
                canvasWidth = chartWidthPx,
                canvasHeight = chartHeightPx,
                leftInset = leftInsetPx,
                rightInset = rightInsetPx,
                topInset = topInsetPx,
                bottomInset = bottomInsetPx
            )
            val maxPopupLeft = (chartWidthPx - popupWidthPx - edgeMarginPx).coerceAtLeast(edgeMarginPx)
            val popupLeft = (pointPosition.x - popupWidthPx / 2f)
                .coerceIn(edgeMarginPx, maxPopupLeft)
            val topAbovePoint = pointPosition.y - estimatedPopupHeightPx - edgeMarginPx
            val desiredPopupTop = if (topAbovePoint >= edgeMarginPx) {
                topAbovePoint
            } else {
                pointPosition.y + edgeMarginPx
            }
            val maxPopupTop = (chartHeightPx - estimatedPopupHeightPx - edgeMarginPx).coerceAtLeast(edgeMarginPx)
            val popupTop = desiredPopupTop.coerceIn(edgeMarginPx, maxPopupTop)

            Popup(
                alignment = Alignment.TopStart,
                offset = IntOffset(popupLeft.roundToInt(), popupTop.roundToInt()),
                onDismissRequest = { onPointSelected(null) },
                properties = PopupProperties(focusable = true, dismissOnClickOutside = true)
            ) {
                SelectedWineSummary(
                    point = point,
                    isOnFrontier = selectedOnFrontier,
                    modifier = Modifier.width(popupWidth)
                )
            }
        }
    }
}

@Composable
private fun WineTypeSwatch(type: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(wineTypeColor(type))
            .border(1.dp, wineTypeOutline(type), CircleShape)
    )
}

@Composable
private fun ParetoLineLegend(color: Color, dashed: Boolean, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Canvas(modifier = Modifier.width(26.dp).height(12.dp)) {
            drawLine(
                color = color,
                start = Offset(0f, size.height / 2f),
                end = Offset(size.width, size.height / 2f),
                strokeWidth = 2.dp.toPx(),
                pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 3.dp.toPx())) else null
            )
        }
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SelectedWineSummary(
    point: ParetoWinePoint,
    isOnFrontier: Boolean,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                WineTypeSwatch(point.type, Modifier.size(12.dp))
                Text(point.type, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                Text(
                    if (isOnFrontier) "Overall frontier" else "Trade-off",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isOnFrontier) ParetoGreen else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                "${point.maker} — ${point.name}",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text("Vintage ${point.vintage}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${formatRating(point.averageRating)} / 100", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                Text("${formatPrice(point.price)} / bottle", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            }
            Text(
                "Based on ${point.tastingCount} tasting${if (point.tastingCount == 1) "" else "s"}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ParetoFrontierDashboard(
    redFrontier: List<ParetoWinePoint>,
    whiteFrontier: List<ParetoWinePoint>
) {
    val redLeaders = remember(redFrontier) { rankFrontierLeaders(redFrontier) }
    val whiteLeaders = remember(whiteFrontier) { rankFrontierLeaders(whiteFrontier) }

    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Red & White frontier leaders", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "Top average-score-per-dollar picks from each type’s Pareto frontier.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.Top
            ) {
                FrontierCategoryCard(
                    type = "Red",
                    frontier = redFrontier,
                    leaders = redLeaders,
                    modifier = Modifier.weight(1f)
                )
                FrontierCategoryCard(
                    type = "White",
                    frontier = whiteFrontier,
                    leaders = whiteLeaders,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun FrontierCategoryCard(
    type: String,
    frontier: List<ParetoWinePoint>,
    leaders: List<ParetoWinePoint>,
    modifier: Modifier = Modifier
) {
    val containerColor = if (type == "Red") {
        WineRed.copy(alpha = 0.08f)
    } else {
        MaterialTheme.colorScheme.surfaceContainer
    }

    Card(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(11.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                WineTypeSwatch(type, Modifier.size(12.dp))
                Text(type, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Text(
                    "${frontier.size} frontier",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (leaders.isEmpty()) {
                Text(
                    "No priced, rated wines with the current filters.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                leaders.forEachIndexed { index, point ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            "${index + 1}. ${point.name} · ${point.vintage}",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            point.maker,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(
                                "${formatRating(point.averageRating)} / 100",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(formatPrice(point.price), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                        }
                    }
                    if (index < leaders.lastIndex) {
                        Spacer(Modifier.height(1.dp))
                    }
                }
                if (frontier.size > leaders.size) {
                    Text(
                        "+${frontier.size - leaders.size} more on frontier",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

private fun rankFrontierLeaders(frontier: List<ParetoWinePoint>): List<ParetoWinePoint> =
    frontier.sortedWith(
        compareByDescending<ParetoWinePoint> { it.averageRating / it.price }
            .thenByDescending { it.averageRating }
            .thenBy { it.price }
            .thenBy { it.name.lowercase(Locale.ROOT) }
    ).take(3)

@Composable
private fun paretoFilterChipColors() = FilterChipDefaults.filterChipColors(
    selectedContainerColor = WineRed,
    selectedLabelColor = Color.White
)

private fun buildParetoPoints(
    tastingNotes: List<TastingNoteWithDefinition>,
    activeInventory: List<WineBottleWithDefinition>,
    archivedInventory: List<WineBottleWithDefinition>,
    consumptionHistory: List<ConsumptionEventWithDefinition>
): List<ParetoWinePoint> {
    val activeAndArchivedPrices = (activeInventory + archivedInventory)
        .groupBy { WineVintageKey(it.bottle.definitionId, displayVintage(it.bottle.vintage)) }
        .mapValues { (_, bottles) -> bottles.map { it.bottle.price } }
    val consumedPrices = consumptionHistory
        .groupBy { WineVintageKey(it.event.definitionId, displayVintage(it.event.vintage)) }
        .mapValues { (_, events) -> events.map { it.event.price } }

    return tastingNotes
        .filter { it.note.rating > 0.0 && it.note.rating.isFinite() }
        .groupBy { WineVintageKey(it.note.definitionId, displayVintage(it.note.vintage)) }
        .mapNotNull { (key, notes) ->
            val definition = notes.first().definition
            val ratings = notes.map { it.note.rating }.filter { it > 0.0 && it.isFinite() }
            val price = medianPositive(notes.map { it.note.price })
                ?: medianPositive(consumedPrices[key].orEmpty())
                ?: medianPositive(notes.map { it.bottle?.price })
                ?: medianPositive(activeAndArchivedPrices[key].orEmpty())
                ?: return@mapNotNull null

            ParetoWinePoint(
                key = key,
                maker = definition.maker,
                name = definition.name,
                vintage = key.vintage,
                type = displayWineType(definition.type),
                price = price,
                averageRating = ratings.average().coerceIn(MIN_RATING, MAX_RATING),
                tastingCount = ratings.size
            )
        }
        .sortedBy { it.price }
}

private fun medianPositive(values: Iterable<Double?>): Double? {
    val sorted = values.filterNotNull().filter { it > 0.0 && it.isFinite() }.sorted()
    if (sorted.isEmpty()) return null
    val middle = sorted.size / 2
    return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2.0
}

private fun displayWineType(type: String): String = when (type.trim().lowercase(Locale.ROOT)) {
    "red" -> "Red"
    "white" -> "White"
    "rose", "rosé" -> "Rosé"
    "sparkling" -> "Sparkling"
    "dessert" -> "Dessert"
    "" -> "Other"
    else -> type.trim()
}

private fun sortWineTypes(types: List<String>): List<String> = types.sortedWith(
    compareBy<String> { type ->
        PREFERRED_WINE_TYPE_ORDER.indexOf(type).let { if (it < 0) Int.MAX_VALUE else it }
    }.thenBy { it.lowercase(Locale.ROOT) }
)

private fun wineTypeColor(type: String): Color = when (type) {
    "Red" -> WineRed
    "White" -> Color(0xFFF6F1E5)
    "Rosé" -> Color(0xFFD8758D)
    "Sparkling" -> Color(0xFFD6AE46)
    "Dessert" -> Color(0xFF8B6AAE)
    else -> Color(0xFF62818D)
}

private fun wineTypeOutline(type: String): Color = when (type) {
    "White", "Sparkling" -> Color(0xFF8A806D)
    else -> Color.White.copy(alpha = 0.92f)
}

private fun paretoFrontier(points: List<ParetoWinePoint>): List<ParetoWinePoint> {
    val efficient = mutableListOf<ParetoWinePoint>()
    var bestRatingAtLowerPrice = Double.NEGATIVE_INFINITY

    points.groupBy { it.price }.toSortedMap().forEach { (_, samePrice) ->
        val bestRatingAtPrice = samePrice.maxOf { it.averageRating }
        if (bestRatingAtPrice > bestRatingAtLowerPrice + 1e-6) {
            efficient += samePrice.filter { abs(it.averageRating - bestRatingAtPrice) < 1e-6 }
            bestRatingAtLowerPrice = bestRatingAtPrice
        }
    }
    return efficient.sortedBy { it.price }
}

private fun calculateTrend(points: List<ParetoWinePoint>): PriceRatingTrend? {
    if (points.size < 2) return null
    val logPrices = points.map { ln(it.price) }
    val ratings = points.map { it.averageRating }
    val meanLogPrice = logPrices.average()
    val meanRating = ratings.average()
    val variance = logPrices.sumOf { (it - meanLogPrice) * (it - meanLogPrice) }
    if (variance <= 1e-10) return null
    val covariance = logPrices.indices.sumOf { index ->
        (logPrices[index] - meanLogPrice) * (ratings[index] - meanRating)
    }
    val slope = covariance / variance
    return PriceRatingTrend(slope = slope, intercept = meanRating - slope * meanLogPrice)
}

private fun createLogPriceScale(points: List<ParetoWinePoint>): LogPriceScale {
    val minimum = points.minOf { it.price }
    val maximum = points.maxOf { it.price }
    val minimumLog = ln(minimum)
    val maximumLog = ln(maximum)
    val span = maximumLog - minimumLog
    val padding = if (span < 0.08) 0.35 else span * 0.12
    val lowerBound = minimumLog - padding
    val upperBound = maximumLog + padding
    return LogPriceScale(
        minimumLogPrice = lowerBound,
        maximumLogPrice = upperBound,
        ticks = createPriceTicks(lowerBound, upperBound)
    )
}

private fun createPriceTicks(minimumLog: Double, maximumLog: Double): List<Double> {
    val minimum = exp(minimumLog)
    val maximum = exp(maximumLog)
    val firstPower = floor(kotlin.math.log10(minimum)).toInt()
    val lastPower = ceil(kotlin.math.log10(maximum)).toInt()
    val candidates = (firstPower..lastPower)
        .flatMap { power -> listOf(1.0, 2.0, 5.0).map { it * 10.0.pow(power.toDouble()) } }
        .filter { it > minimum && it < maximum }
        .distinct()
        .sorted()
    if (candidates.isEmpty()) return listOf(sqrt(minimum * maximum))
    if (candidates.size <= 6) return candidates
    val step = ceil(candidates.size / 6.0).toInt()
    return candidates.filterIndexed { index, _ -> index % step == 0 }.take(6)
}

private fun chartPointOffset(
    price: Double,
    rating: Double,
    scale: LogPriceScale,
    canvasWidth: Float,
    canvasHeight: Float,
    leftInset: Float,
    rightInset: Float,
    topInset: Float,
    bottomInset: Float
): Offset {
    val plotWidth = (canvasWidth - leftInset - rightInset).coerceAtLeast(1f)
    val plotHeight = (canvasHeight - topInset - bottomInset).coerceAtLeast(1f)
    val x = leftInset + scale.fraction(price) * plotWidth
    val ratingFraction = ((rating - MIN_RATING) / (MAX_RATING - MIN_RATING)).coerceIn(0.0, 1.0).toFloat()
    val y = canvasHeight - bottomInset - ratingFraction * plotHeight
    return Offset(x, y)
}

private fun formatPrice(price: Double): String = when {
    price >= 1000.0 -> "$${String.format(Locale.US, "%.1f", price / 1000.0)}k"
    price >= 100.0 -> "$${price.roundToInt()}"
    price >= 10.0 -> "$${price.roundToInt()}"
    price >= 1.0 -> "$${String.format(Locale.US, "%.1f", price)}"
    else -> "$${String.format(Locale.US, "%.2f", price)}"
}

private fun formatRating(rating: Double): String = String.format(Locale.US, "%.1f", rating)
