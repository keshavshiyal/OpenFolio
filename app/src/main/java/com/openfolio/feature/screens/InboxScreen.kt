package com.openfolio.feature.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openfolio.core.data.local.entity.AssetClass
import com.openfolio.core.data.local.entity.DocumentEntity
import com.openfolio.core.data.local.entity.DocumentStatus
import com.openfolio.core.ui.components.AssetTypeSelectionBottomSheet
import com.openfolio.core.ui.theme.LocalOpenFolioColors
import com.openfolio.feature.assets.toDisplayTitle
import java.util.Locale

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
fun InboxScreen(
    documents: List<DocumentEntity>,
    onDocumentClick: (String) -> Unit,
    onProcessWithAiClick: ((String) -> Unit)? = null,
    onImport: ((Uri, AssetClass) -> Unit)? = null,
) {
    val colors = LocalOpenFolioColors.current
    val awaitingReview = documents.count { it.status != DocumentStatus.COMPLETED.name }
    var showAssetTypeSheet by remember { mutableStateOf(false) }
    var pendingAssetClass by remember { mutableStateOf(AssetClass.STOCK) }

    val documentPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri ->
            uri?.let { onImport?.invoke(it, pendingAssetClass) }
        },
    )

    Scaffold(
        containerColor = colors.background,
        floatingActionButton = {
            if (onImport != null) {
                ExtendedFloatingActionButton(
                    onClick = { showAssetTypeSheet = true },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text("Import") },
                    containerColor = colors.brand,
                    contentColor = colors.onAccent,
                )
            }
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(
                start = 20.dp,
                top = 24.dp,
                end = 20.dp,
                bottom = 100.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = "Inbox",
                        style = MaterialTheme.typography.headlineMedium,
                        color = colors.content,
                    )
                    Text(
                        text = "$awaitingReview documents awaiting review",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.muted,
                    )
                }
            }

            if (documents.isEmpty()) {
                item {
                    Text(
                        text = "Imported statements will appear here.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.muted,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                }
            } else {
                items(documents, key = DocumentEntity::id) { document ->
                    DocumentCard(
                        document = document,
                        onClick = { onDocumentClick(document.id) },
                        onProcessWithAi = { (onProcessWithAiClick ?: onDocumentClick)(document.id) },
                    )
                }
            }
        }
    }

    if (showAssetTypeSheet) {
        AssetTypeSelectionBottomSheet(
            onDismissRequest = { showAssetTypeSheet = false },
            onSelectAssetType = { selectedClass ->
                pendingAssetClass = selectedClass
                showAssetTypeSheet = false
                documentPicker.launch(DOCUMENT_MIME_TYPES)
            },
        )
    }
}

@Composable
fun DocumentCard(
    document: DocumentEntity,
    onClick: () -> Unit,
    onProcessWithAi: (() -> Unit)? = null,
) {
    val colors = LocalOpenFolioColors.current
    val shape = RoundedCornerShape(12.dp)

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = shape,
        color = colors.surface,
        border = BorderStroke(1.dp, colors.border),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.material3.Icon(
                imageVector = Icons.Outlined.Description,
                contentDescription = "Document",
                tint = colors.muted,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = document.fileName,
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.content,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = colors.brand.copy(alpha = 0.12f),
                    ) {
                        Text(
                            text = document.getAssetClassEnum().toDisplayTitle(),
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.brand,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                    Text(
                        text = "•  ${formatFileSize(document.fileSize)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.muted,
                    )
                }
                Text(
                    text = "SHA-256: ${document.fileHash.take(8)}...",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = colors.muted,
                )
            }
            Spacer(Modifier.width(10.dp))
            if (document.status == DocumentStatus.OCR_COMPLETE.name) {
                androidx.compose.material3.Button(
                    onClick = { onProcessWithAi?.invoke() ?: onClick() },
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = colors.brand,
                        contentColor = androidx.compose.ui.graphics.Color.White,
                    ),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = "Process with AI",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            } else {
                DocumentStatusBadge(document.status)
            }
        }
    }
}

@Composable
private fun DocumentStatusBadge(status: String) {
    val colors = LocalOpenFolioColors.current
    val label: String
    val foreground: androidx.compose.ui.graphics.Color
    val background: androidx.compose.ui.graphics.Color
    when (status) {
        DocumentStatus.PENDING_OCR.name -> {
            label = "Processing OCR..."
            foreground = colors.muted
            background = colors.background
        }
        DocumentStatus.OCR_FAILED.name -> {
            label = "OCR Failed"
            foreground = colors.lossGlyph
            background = colors.lossGlyph.copy(alpha = 0.12f)
        }
        DocumentStatus.OCR_COMPLETE.name,
        DocumentStatus.READY_FOR_MAPPING.name,
        DocumentStatus.AI_COMPLETE.name -> {
            label = when (status) {
                DocumentStatus.OCR_COMPLETE.name -> "Ready for AI"
                DocumentStatus.READY_FOR_MAPPING.name -> "Map columns"
                else -> "Ready for review"
            }
            foreground = colors.brand
            background = colors.brandSubtle
        }
        DocumentStatus.COMPLETED.name -> {
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                androidx.compose.material3.Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = colors.gainGlyph,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = "Completed",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.gainGlyph,
                )
            }
            return
        }
        else -> {
            label = status.lowercase().replaceFirstChar { it.uppercase() }
            foreground = colors.muted
            background = colors.background
        }
    }
    Surface(
        shape = RoundedCornerShape(50.dp),
        color = background,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = foreground,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

private fun formatFileSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024L * 1024 -> "${bytes / 1024} KB"
    else -> String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0))
}
