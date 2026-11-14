package com.openfolio.feature.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.ShowChart
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.openfolio.core.ui.components.MoneyText
import com.openfolio.core.ui.components.MoneyTextStyle
import com.openfolio.core.ui.theme.LocalOpenFolioColors
import com.openfolio.feature.home.ActivityIcon
import com.openfolio.feature.home.AllocationColor
import com.openfolio.feature.home.AssetAllocation
import com.openfolio.feature.home.HomeUiState
import com.openfolio.feature.home.IngestionState
import com.openfolio.feature.home.RecentActivity
import java.math.BigDecimal

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    uiState: HomeUiState,
    ingestionState: IngestionState,
    onToggleValues: () -> Unit,
    onSelectPeriod: (String) -> Unit,
    onImport: (Uri) -> Unit,
    onImportSuccess: () -> Unit,
    onIngestionHandled: () -> Unit,
    onSettings: () -> Unit,
) {
    val colors = LocalOpenFolioColors.current
    val snackbarHostState = remember { SnackbarHostState() }
    val documentPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri -> uri?.let(onImport) },
    )

    LaunchedEffect(ingestionState) {
        when (val state = ingestionState) {
            is IngestionState.Error -> {
                snackbarHostState.showSnackbar(state.message)
                onIngestionHandled()
            }
            is IngestionState.Success -> {
                onImportSuccess()
                onIngestionHandled()
            }
            IngestionState.Idle, IngestionState.Loading -> Unit
        }
    }

    Scaffold(
        containerColor = colors.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("OpenFolio") },
                windowInsets = TopAppBarDefaults.windowInsets,
                actions = {
                    IconButton(onClick = onToggleValues) {
                        Icon(
                            imageVector = if (uiState.showValues) {
                                Icons.Filled.Visibility
                            } else {
                                Icons.Filled.VisibilityOff
                            },
                            contentDescription = if (uiState.showValues) {
                                "Hide values"
                            } else {
                                "Show values"
                            },
                        )
                    }
                    IconButton(onClick = onSettings) {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = "Settings",
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    if (ingestionState != IngestionState.Loading) {
                        documentPicker.launch(DOCUMENT_MIME_TYPES)
                    }
                },
                icon = {
                    if (ingestionState == IngestionState.Loading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = colors.onAccent,
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(Icons.Filled.Add, contentDescription = null)
                    }
                },
                text = {
                    Text(if (ingestionState == IngestionState.Loading) "Importing" else "Import")
                },
                containerColor = colors.brand,
                contentColor = colors.onAccent,
                expanded = true,
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(
                start = 20.dp,
                top = 8.dp,
                end = 20.dp,
                bottom = 160.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            item {
                NetWorthSection(uiState)
            }
            item {
                PeriodSelector(
                    selectedPeriod = uiState.selectedPeriod,
                    onSelectPeriod = onSelectPeriod,
                )
            }
            item {
                TrajectoryChart(points = uiState.trajectoryPoints)
            }
            item {
                AssetLiabilityPair(uiState)
            }
            item {
                AllocationSection(uiState.allocations)
            }
            item {
                Text(
                    text = "Recent Activity",
                    style = MaterialTheme.typography.titleLarge,
                    color = colors.content,
                )
            }
            items(uiState.recentActivity) { activity ->
                RecentActivityRow(activity = activity, showValues = uiState.showValues)
            }
        }

    }
}

private val DOCUMENT_MIME_TYPES = arrayOf(
    "application/pdf",
    "image/*",
    "text/csv",
    "text/comma-separated-values",
    "application/csv",
    "application/vnd.ms-excel",
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
)

@Composable
private fun NetWorthSection(uiState: HomeUiState) {
    val colors = LocalOpenFolioColors.current

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = "Net worth",
            style = MaterialTheme.typography.titleMedium,
            color = colors.muted,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val trendStyle = MaterialTheme.typography.labelMedium.copy(
                fontFeatureSettings = "tnum",
            )
            MoneyText(
                amount = uiState.netWorth,
                style = MoneyTextStyle.Display,
                showValues = uiState.showValues,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = "▲",
                    style = trendStyle,
                    color = colors.gainGlyph,
                )
                Text(
                    text = uiState.changePercent,
                    style = trendStyle,
                    color = colors.content,
                )
            }
        }
        Text(
            text = uiState.asOf,
            style = MaterialTheme.typography.bodySmall,
            color = colors.muted,
        )
    }
}

@Composable
private fun PeriodSelector(
    selectedPeriod: String,
    onSelectPeriod: (String) -> Unit,
) {
    val colors = LocalOpenFolioColors.current
    val periods = listOf("1M", "6M", "1Y", "ALL")

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        periods.forEach { period ->
            val selected = selectedPeriod == period
            Surface(
                modifier = Modifier
                    .weight(1f)
                    .clickable { onSelectPeriod(period) },
                shape = CircleShape,
                color = if (selected) colors.brand else Color.Transparent,
            ) {
                Text(
                    text = period,
                    modifier = Modifier.padding(vertical = 10.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (selected) colors.onAccent else colors.muted,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun TrajectoryChart(points: List<Float>) {
    val colors = LocalOpenFolioColors.current

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(152.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surface),
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(152.dp)
                .padding(horizontal = 16.dp, vertical = 16.dp),
        ) {
            if (points.size < 2) return@Canvas

            val horizontalStep = size.width / (points.size - 1)
            val topPadding = 8.dp.toPx()
            val bottomPadding = 14.dp.toPx()
            val chartHeight = size.height - topPadding - bottomPadding
            fun point(index: Int): Offset = Offset(
                x = index * horizontalStep,
                y = topPadding + (1f - points[index].coerceIn(0f, 1f)) * chartHeight,
            )

            val linePath = Path().apply {
                moveTo(point(0).x, point(0).y)
                for (index in 1 until points.lastIndex) {
                    val current = point(index)
                    val next = point(index + 1)
                    val midpoint = Offset(
                        x = (current.x + next.x) / 2f,
                        y = (current.y + next.y) / 2f,
                    )
                    quadraticBezierTo(current.x, current.y, midpoint.x, midpoint.y)
                }
                val lastPoint = point(points.lastIndex)
                lineTo(lastPoint.x, lastPoint.y)
            }
            val fillPath = Path().apply {
                addPath(linePath)
                lineTo(size.width, size.height - bottomPadding)
                lineTo(0f, size.height - bottomPadding)
                close()
            }

            drawPath(
                path = fillPath,
                brush = Brush.verticalGradient(
                    colors = listOf(colors.brand.copy(alpha = 0.16f), Color.Transparent),
                    startY = topPadding,
                    endY = size.height - bottomPadding,
                ),
                style = Fill,
            )
            drawPath(
                path = linePath,
                color = colors.brand,
                style = Stroke(
                    width = 3.dp.toPx(),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                ),
            )
            drawLine(
                color = colors.border,
                start = Offset(0f, size.height - bottomPadding),
                end = Offset(size.width, size.height - bottomPadding),
                strokeWidth = 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 4.dp.toPx())),
            )
        }
    }
}

@Composable
private fun AssetLiabilityPair(uiState: HomeUiState) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StatCard(
            modifier = Modifier.weight(1f),
            label = "Assets",
            amount = uiState.assets,
            showValues = uiState.showValues,
        )
        StatCard(
            modifier = Modifier.weight(1f),
            label = "Liabilities",
            amount = uiState.liabilities,
            showValues = uiState.showValues,
        )
    }
}

@Composable
private fun StatCard(
    modifier: Modifier,
    label: String,
    amount: BigDecimal,
    showValues: Boolean,
) {
    val colors = LocalOpenFolioColors.current

    Card(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surface),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = colors.muted)
            MoneyText(
                amount = amount,
                style = MoneyTextStyle.Title,
                showValues = showValues,
            )
        }
    }
}

@Composable
private fun AllocationSection(allocations: List<AssetAllocation>) {
    val colors = LocalOpenFolioColors.current
    val segmentGap = 2.dp
    val segmentShape = RoundedCornerShape(4.dp)

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "Allocation",
            style = MaterialTheme.typography.titleLarge,
            color = colors.content,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(12.dp),
            horizontalArrangement = Arrangement.spacedBy(segmentGap),
        ) {
            allocations.forEach { allocation ->
                Box(
                    modifier = Modifier
                        .weight(allocation.percentage.toFloat())
                        .fillMaxSize()
                        .background(allocationColor(allocation.color), segmentShape),
                )
            }
        }
        allocations.chunked(2).forEach { rowAllocations ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                rowAllocations.forEach { allocation ->
                    AllocationLegendItem(
                        modifier = Modifier.weight(1f),
                        allocation = allocation,
                    )
                }
                if (rowAllocations.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun AllocationLegendItem(
    modifier: Modifier,
    allocation: AssetAllocation,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(allocationColor(allocation.color), RoundedCornerShape(4.dp)),
        )
        Text(
            text = "${allocation.label} ${allocation.percentage}%",
            style = MaterialTheme.typography.bodySmall.copy(
                fontFeatureSettings = "tnum",
            ),
            color = LocalOpenFolioColors.current.content,
        )
    }
}

@Composable
private fun allocationColor(color: AllocationColor): Color {
    val colors = LocalOpenFolioColors.current
    return when (color) {
        AllocationColor.Slot1 -> colors.chartPalette[0]
        AllocationColor.Slot2 -> colors.chartPalette[1]
        AllocationColor.Slot3 -> colors.chartPalette[2]
        AllocationColor.Slot4 -> colors.chartPalette[3]
        AllocationColor.Slot5 -> colors.chartPalette[4]
    }
}

@Composable
private fun RecentActivityRow(
    activity: RecentActivity,
    showValues: Boolean,
) {
    val colors = LocalOpenFolioColors.current
    val icon = when (activity.icon) {
        ActivityIcon.Buy -> Icons.Outlined.ShowChart
        ActivityIcon.Investment -> Icons.Filled.AccountBalanceWallet
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = colors.content,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = activity.title,
                style = MaterialTheme.typography.bodyLarge,
                color = colors.content,
            )
            Text(
                text = activity.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = colors.muted,
            )
        }
        MoneyText(
            amount = activity.amount,
            style = MoneyTextStyle.Body,
            showValues = showValues,
        )
    }
}
