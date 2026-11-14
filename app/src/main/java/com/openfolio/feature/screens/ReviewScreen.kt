package com.openfolio.feature.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openfolio.core.ui.theme.LocalOpenFolioColors
import com.openfolio.feature.review.ReviewUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewScreen(
    uiState: ReviewUiState,
    onBack: () -> Unit,
    onSkip: () -> Unit,
    onReject: () -> Unit,
    onAccept: () -> Unit,
) {
    val colors = LocalOpenFolioColors.current
    val title = (uiState as? ReviewUiState.Loaded)?.document?.fileName ?: "Review"

    Scaffold(
        containerColor = colors.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleLarge,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    TextButton(onClick = onSkip) {
                        Text("Skip", color = colors.brand)
                    }
                },
                windowInsets = TopAppBarDefaults.windowInsets,
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.4f),
                color = colors.border,
            ) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = when (uiState) {
                            is ReviewUiState.Error -> uiState.message
                            ReviewUiState.NotFound -> "This document is no longer available."
                            else -> "PDF Preview (Coming Soon)"
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        color = colors.muted,
                    )
                }
            }

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.6f),
                color = colors.surface,
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 20.dp, vertical = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Text(
                        text = "Extracted Data (Mock)",
                        style = MaterialTheme.typography.titleLarge,
                        color = colors.content,
                    )
                    ReviewField("Asset", "Reliance Industries", 95)
                    ReviewField("Quantity", "10", 98)
                    ReviewField("Price", "₹ 2,850.00", 85)

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        OutlinedButton(
                            onClick = onReject,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Reject")
                        }
                        Button(
                            onClick = onAccept,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Accept")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ReviewField(
    label: String,
    value: String,
    confidence: Int,
) {
    require(confidence in 0..100) { "Confidence must be between 0 and 100." }
    val colors = LocalOpenFolioColors.current
    val confidenceColor = when {
        confidence >= 90 -> colors.gainGlyph
        confidence >= 70 -> colors.chartPalette[2]
        else -> colors.lossGlyph
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = colors.muted,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontFeatureSettings = "tnum",
                ),
                color = colors.content,
            )
        }
        Surface(
            shape = RoundedCornerShape(50.dp),
            color = confidenceColor.copy(alpha = 0.12f),
        ) {
            Text(
                text = "$confidence%",
                style = MaterialTheme.typography.labelMedium,
                color = confidenceColor,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
}
