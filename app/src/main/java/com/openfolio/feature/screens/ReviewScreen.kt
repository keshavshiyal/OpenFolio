package com.openfolio.feature.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openfolio.core.data.local.entity.DraftTransactionEntity
import com.openfolio.core.ui.theme.LocalOpenFolioColors
import com.openfolio.feature.review.ReviewActionState
import com.openfolio.feature.review.ReviewUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewScreen(
    uiState: ReviewUiState,
    drafts: List<DraftTransactionEntity>,
    actionState: ReviewActionState,
    onBack: () -> Unit,
    onSkip: () -> Unit,
    onAcceptAllHighConfidence: () -> Unit,
    onSetDraftAccepted: (String, Boolean) -> Unit,
    onSaveConfirmed: () -> Unit,
) {
    val colors = LocalOpenFolioColors.current
    val document = (uiState as? ReviewUiState.Loaded)?.document
    var editingDraft by remember { mutableStateOf<DraftTransactionEntity?>(null) }

    Scaffold(
        containerColor = colors.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Review (${drafts.size} items)",
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
                    document?.let {
                        Text(
                            text = it.fileName,
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.muted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
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
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "${drafts.size} Extracted Transactions",
                        style = MaterialTheme.typography.titleLarge,
                        color = colors.content,
                    )
                    Button(
                        onClick = onAcceptAllHighConfidence,
                        enabled = actionState != ReviewActionState.Working && drafts.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Accept All High Confidence (>90%)")
                    }

                    if (drafts.isEmpty()) {
                        Text(
                            text = when (uiState) {
                                ReviewUiState.Loading -> "Loading transactions…"
                                is ReviewUiState.Error -> uiState.message
                                ReviewUiState.NotFound -> "No document found."
                                else -> "No transactions found."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.muted,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(vertical = 2.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(drafts, key = DraftTransactionEntity::id) { draft ->
                                DraftTransactionRow(
                                    draft = draft,
                                    onClick = { editingDraft = draft },
                                    onToggleAccepted = {
                                        onSetDraftAccepted(draft.id, !draft.isAccepted)
                                    },
                                )
                            }
                        }
                    }

                    (actionState as? ReviewActionState.Error)?.let { error ->
                        Text(
                            text = error.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.lossGlyph,
                        )
                    }
                    Button(
                        onClick = onSaveConfirmed,
                        enabled = drafts.any(DraftTransactionEntity::isAccepted) &&
                            actionState != ReviewActionState.Working,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            if (actionState == ReviewActionState.Working) {
                                "Saving…"
                            } else {
                                "Save Confirmed"
                            },
                        )
                    }
                }
            }
        }
    }

    editingDraft?.let { draft ->
        ModalBottomSheet(
            onDismissRequest = { editingDraft = null },
            containerColor = colors.surface,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = "Transaction details",
                    style = MaterialTheme.typography.titleLarge,
                    color = colors.content,
                )
                ReviewDetailField("Asset", draft.assetName)
                ReviewDetailField("Type", draft.transactionType)
                ReviewDetailField("Quantity", draft.quantity)
                ReviewDetailField("Price", draft.price)
                ReviewDetailField("Net amount", draft.netAmount)
                ReviewDetailField("Confidence", "${draft.confidence}%")
                OutlinedButton(
                    onClick = {
                        onSetDraftAccepted(draft.id, !draft.isAccepted)
                        editingDraft = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (draft.isAccepted) "Remove from confirmed" else "Accept transaction")
                }
            }
        }
    }
}

@Composable
private fun DraftTransactionRow(
    draft: DraftTransactionEntity,
    onClick: () -> Unit,
    onToggleAccepted: () -> Unit,
) {
    val colors = LocalOpenFolioColors.current
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = if (draft.isAccepted) colors.brandSubtle else colors.background,
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, top = 10.dp, end = 6.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = draft.assetName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.content,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${draft.transactionType} · Qty ${draft.quantity} · ₹ ${draft.price}",
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFeatureSettings = "tnum",
                    ),
                    color = colors.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            ConfidenceBadge(draft.confidence)
            IconButton(onClick = onClick) {
                Icon(
                    imageVector = Icons.Filled.Edit,
                    contentDescription = "Edit transaction details",
                    tint = colors.muted,
                    modifier = Modifier.size(20.dp),
                )
            }
            IconButton(onClick = onToggleAccepted) {
                if (draft.isAccepted) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = "Accepted",
                        tint = colors.brand,
                    )
                } else {
                    Surface(
                        modifier = Modifier.size(20.dp),
                        shape = RoundedCornerShape(50.dp),
                        color = colors.border,
                    ) {}
                }
            }
        }
    }
}

@Composable
private fun ConfidenceBadge(confidence: Int) {
    val colors = LocalOpenFolioColors.current
    val badgeColor = when {
        confidence >= 90 -> colors.gainGlyph
        confidence >= 70 -> colors.chartPalette[2]
        else -> colors.lossGlyph
    }
    Surface(
        shape = RoundedCornerShape(50.dp),
        color = badgeColor.copy(alpha = 0.12f),
    ) {
        Text(
            text = "$confidence%",
            style = MaterialTheme.typography.labelSmall,
            color = badgeColor,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
        )
    }
}

@Composable
private fun ReviewDetailField(label: String, value: String) {
    val colors = LocalOpenFolioColors.current
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = colors.muted)
        Text(
            value,
            style = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum"),
            color = colors.content,
        )
    }
}
