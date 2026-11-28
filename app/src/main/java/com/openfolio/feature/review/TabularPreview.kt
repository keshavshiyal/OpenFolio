package com.openfolio.feature.review

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openfolio.core.data.local.LocalFileCrypto
import com.openfolio.core.ui.theme.LocalOpenFolioColors
import com.openfolio.core.utils.CsvExtractor
import com.openfolio.core.utils.StructuredFileParser
import com.openfolio.core.utils.XlsxExtractor
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun normalizeRaggedRows(rawRows: List<List<String>>): List<List<String>> {
    if (rawRows.isEmpty()) return emptyList()
    val maxCols = rawRows.maxOfOrNull { it.size } ?: 0
    if (maxCols == 0) return rawRows
    return rawRows.map { row ->
        if (row.size == maxCols) {
            row
        } else {
            row + List(maxCols - row.size) { "" }
        }
    }
}

@Composable
fun TabularDocumentPreview(
    filePath: String,
    fileExtension: String,
    headerRowIndex: Int,
    modifier: Modifier = Modifier,
) {
    val colors = LocalOpenFolioColors.current
    val appContext = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val fileCrypto = remember(appContext) { LocalFileCrypto(appContext) }
    var isLoading by remember(filePath, fileExtension) { mutableStateOf(true) }
    var errorMessage by remember(filePath, fileExtension) { mutableStateOf<String?>(null) }
    var rows by remember(filePath, fileExtension) { mutableStateOf<List<List<String>>>(emptyList()) }

    LaunchedEffect(filePath, fileExtension) {
        isLoading = true
        errorMessage = null
        try {
            val loadedRows = withContext(Dispatchers.IO) {
                val file = File(filePath)
                if (!file.exists() || !file.canRead()) {
                    throw IllegalArgumentException("File does not exist or cannot be read: $filePath")
                }
                val parser = StructuredFileParser(
                    CsvExtractor(fileCrypto),
                    XlsxExtractor(fileCrypto, appContext),
                )
                val raw = parser.readPreviewRows(file, limit = 100)
                normalizeRaggedRows(raw)
            }
            rows = loadedRows
            if (loadedRows.isEmpty()) {
                errorMessage = "Preview unavailable"
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Exception) {
            errorMessage = "Preview unavailable"
        } finally {
            isLoading = false
        }
    }

    when {
        isLoading -> {
            Box(
                modifier = modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(28.dp),
                    color = colors.brand,
                    strokeWidth = 2.dp,
                )
            }
        }
        errorMessage != null -> {
            Box(
                modifier = modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = errorMessage ?: "Preview unavailable",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.muted,
                )
            }
        }
        else -> {
            TabularPreview(
                rows = rows,
                headerRowIndex = headerRowIndex,
                modifier = modifier,
            )
        }
    }
}

@Composable
fun TabularPreview(
    rows: List<List<String>>,
    headerRowIndex: Int,
    modifier: Modifier = Modifier,
) {
    val colors = LocalOpenFolioColors.current
    val horizontalScrollState = rememberScrollState()

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.surface)
            .horizontalScroll(horizontalScrollState),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxHeight(),
        ) {
            itemsIndexed(rows) { rowIndex, row ->
                val isHeaderRow = rowIndex == headerRowIndex - 1
                Row(
                    modifier = Modifier
                        .background(if (isHeaderRow) colors.brandSubtle else colors.surface),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TableCell(
                        text = "${rowIndex + 1}",
                        isHeader = isHeaderRow,
                        isRowNumber = true,
                        width = 44.dp,
                    )
                    row.forEach { cellText ->
                        TableCell(
                            text = cellText,
                            isHeader = isHeaderRow,
                            isRowNumber = false,
                            width = 120.dp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TableCell(
    text: String,
    isHeader: Boolean,
    isRowNumber: Boolean,
    width: Dp,
) {
    val colors = LocalOpenFolioColors.current
    Box(
        modifier = Modifier
            .width(width)
            .height(34.dp)
            .border(width = 1.dp, color = colors.border)
            .background(
                when {
                    isHeader -> colors.brandSubtle
                    isRowNumber -> colors.background
                    else -> colors.surface
                },
            )
            .padding(horizontal = 6.dp),
        contentAlignment = if (isRowNumber) Alignment.Center else Alignment.CenterStart,
    ) {
        Text(
            text = text,
            style = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                fontWeight = if (isHeader) FontWeight.Bold else FontWeight.Normal,
                color = when {
                    isHeader -> colors.content
                    isRowNumber -> colors.muted
                    else -> colors.content
                },
                textAlign = if (isRowNumber) TextAlign.Center else TextAlign.Start,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
