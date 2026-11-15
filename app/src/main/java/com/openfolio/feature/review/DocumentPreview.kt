package com.openfolio.feature.review

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.openfolio.core.ui.theme.LocalOpenFolioColors

@Composable
fun DocumentPreview(
    filePath: String,
    fileExtension: String,
    headerRowIndex: Int,
    modifier: Modifier = Modifier,
) {
    val colors = LocalOpenFolioColors.current
    if (filePath.isBlank()) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(colors.background),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "Preview unavailable",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.muted,
            )
        }
        return
    }

    val normalizedExt = fileExtension.lowercase().removePrefix(".")

    when (normalizedExt) {
        "pdf" -> {
            PdfPreview(
                filePath = filePath,
                modifier = modifier,
            )
        }
        "jpg", "jpeg", "png", "webp", "bmp" -> {
            ImagePreview(
                filePath = filePath,
                modifier = modifier,
            )
        }
        "csv", "xlsx", "xls" -> {
            TabularDocumentPreview(
                filePath = filePath,
                fileExtension = normalizedExt,
                headerRowIndex = headerRowIndex,
                modifier = modifier,
            )
        }
        else -> {
            Box(
                modifier = modifier
                    .fillMaxSize()
                    .background(colors.background),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Preview unavailable",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.muted,
                )
            }
        }
    }
}
