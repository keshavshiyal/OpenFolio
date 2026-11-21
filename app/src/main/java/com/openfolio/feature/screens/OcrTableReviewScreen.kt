package com.openfolio.feature.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openfolio.core.tableextraction.CsvTableGenerator
import com.openfolio.core.tableextraction.ExtractedTable
import com.openfolio.core.ui.theme.LocalOpenFolioColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OcrTableReviewScreen(
    tables: List<ExtractedTable>,
    onBack: () -> Unit,
    onApproveTable: (approvedTable: ExtractedTable, selectedRowIndices: Set<Int>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalOpenFolioColors.current
    if (tables.isEmpty()) {
        Scaffold(
            modifier = modifier.fillMaxSize(),
            containerColor = colors.background,
            topBar = {
                TopAppBar(
                    title = { Text("Table Extraction Review") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.background),
                )
            },
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "No structured tables detected in this document.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.muted,
                )
            }
        }
        return
    }

    var selectedTableIndex by remember { mutableIntStateOf(0) }
    val safeTableIndex = selectedTableIndex.coerceIn(0, tables.size - 1)
    val activeTable = tables[safeTableIndex]

    // Editable state for the current table's rows and headers
    val editableHeaders = remember(activeTable) {
        mutableStateListOf<String>().apply {
            activeTable.headers.forEach { add(it.replace(CsvTableGenerator.COMMA_TOKEN, ",")) }
        }
    }
    val editableRows = remember(activeTable) {
        mutableStateListOf<MutableList<String>>().apply {
            activeTable.rows.forEach { row ->
                add(row.map { it.replace(CsvTableGenerator.COMMA_TOKEN, ",") }.toMutableList())
            }
        }
    }
    val selectedRows = remember(activeTable) {
        mutableStateListOf<Int>().apply {
            activeTable.rows.forEachIndexed { idx, row ->
                if (isRowLikelyFinancialData(row)) {
                    add(idx)
                }
            }
            if (isEmpty() && activeTable.rows.isNotEmpty()) {
                addAll(0 until activeTable.rows.size)
            }
        }
    }

    // State for inline cell editing dialog
    var cellEditTarget by remember { mutableStateOf<CellCoordinates?>(null) }
    var cellEditText by remember { mutableStateOf("") }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = colors.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Review Extracted Table (${safeTableIndex + 1}/${tables.size})",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = colors.content,
                        )
                        Text(
                            text = "${activeTable.sourceFileName} • Pages: ${activeTable.pageIndices.joinToString(", ")}",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.muted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = colors.content,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.background),
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding()
                .navigationBarsPadding(),
        ) {
            // Table selection chips if multiple tables detected
            if (tables.size > 1) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    tables.forEachIndexed { idx, table ->
                        FilterChip(
                            selected = idx == safeTableIndex,
                            onClick = { selectedTableIndex = idx },
                            label = { Text("Table ${idx + 1} (${table.rows.size} rows)") },
                            leadingIcon = {
                                Icon(Icons.Filled.TableChart, contentDescription = null, modifier = Modifier.size(16.dp))
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = colors.brand,
                                selectedLabelColor = colors.onAccent,
                                selectedLeadingIconColor = colors.onAccent,
                            ),
                        )
                    }
                }
            }

            // Controls Bar
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = colors.surface,
                border = BorderStroke(1.dp, colors.border),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        TextButton(
                            onClick = {
                                if (selectedRows.size == editableRows.size) {
                                    selectedRows.clear()
                                } else {
                                    selectedRows.clear()
                                    selectedRows.addAll(0 until editableRows.size)
                                }
                            },
                        ) {
                            Text(
                                text = if (selectedRows.size == editableRows.size) "Deselect All" else "Select All",
                                color = colors.brand,
                            )
                        }
                    }

                    Text(
                        text = "${selectedRows.size} of ${editableRows.size} rows selected",
                        style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                        color = colors.muted,
                    )
                }
            }

            // 2D Scrollable Table Grid
            val horizontalScrollState = rememberScrollState()

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(colors.background),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .horizontalScroll(horizontalScrollState),
                ) {
                    // Header Row
                    Row(
                        modifier = Modifier
                            .background(colors.surface)
                            .border(BorderStroke(1.dp, colors.border))
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Checkbox column header spacer
                        Box(modifier = Modifier.width(52.dp), contentAlignment = Alignment.Center) {
                            Text("#", style = MaterialTheme.typography.labelSmall, color = colors.muted)
                        }

                        editableHeaders.forEachIndexed { colIdx, headerText ->
                            Box(
                                modifier = Modifier
                                    .widthIn(min = 120.dp, max = 220.dp)
                                    .padding(horizontal = 8.dp)
                                    .clickable {
                                        cellEditTarget = CellCoordinates(row = -1, col = colIdx)
                                        cellEditText = headerText
                                    },
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Text(
                                        text = headerText,
                                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                        color = colors.content,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false),
                                    )
                                    Icon(
                                        imageVector = Icons.Filled.Edit,
                                        contentDescription = "Edit column title",
                                        tint = colors.muted,
                                        modifier = Modifier.size(12.dp),
                                    )
                                }
                            }
                        }
                    }

                    // Data Rows
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(bottom = 16.dp),
                    ) {
                        itemsIndexed(editableRows) { rowIdx, rowData ->
                            val isSelected = rowIdx in selectedRows

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(if (isSelected) colors.background else colors.surface.copy(alpha = 0.5f))
                                    .border(BorderStroke(0.5.dp, colors.border))
                                    .clickable {
                                        if (isSelected) selectedRows.remove(rowIdx) else selectedRows.add(rowIdx)
                                    }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                // Row selection checkbox
                                Box(
                                    modifier = Modifier.width(52.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Checkbox(
                                        checked = isSelected,
                                        onCheckedChange = { checked ->
                                            if (checked) selectedRows.add(rowIdx) else selectedRows.remove(rowIdx)
                                        },
                                        colors = CheckboxDefaults.colors(
                                            checkedColor = colors.brand,
                                            uncheckedColor = colors.muted,
                                        ),
                                    )
                                }

                                // Cells in row
                                rowData.forEachIndexed { colIdx, cellValue ->
                                    Box(
                                        modifier = Modifier
                                            .widthIn(min = 120.dp, max = 220.dp)
                                            .padding(horizontal = 8.dp, vertical = 6.dp)
                                            .clickable {
                                                cellEditTarget = CellCoordinates(row = rowIdx, col = colIdx)
                                                cellEditText = cellValue
                                            },
                                    ) {
                                        Text(
                                            text = cellValue.ifBlank { "—" },
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                fontFeatureSettings = "tnum",
                                                color = if (isSelected) colors.content else colors.muted,
                                            ),
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Bottom Approval Bar
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = colors.surface,
                border = BorderStroke(1.dp, colors.border),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = {
                            val selectedSet = selectedRows.toSet()
                            val cleanedRows = editableRows.filterIndexed { idx, _ -> idx in selectedSet }
                            val approvedTable = activeTable.copy(
                                headers = editableHeaders.toList(),
                                rows = cleanedRows.map { it.toList() },
                            )
                            onApproveTable(approvedTable, selectedSet)
                        },
                        enabled = selectedRows.isNotEmpty(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = colors.brand,
                            contentColor = colors.onAccent,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Approve & Map Extracted Rows (${selectedRows.size})")
                    }
                }
            }
        }
    }

    // Inline Cell Editor Dialog
    cellEditTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { cellEditTarget = null },
            title = {
                Text(
                    text = if (target.row == -1) "Edit Column Header" else "Edit Cell (Row ${target.row + 1})",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = colors.content,
                )
            },
            text = {
                OutlinedTextField(
                    value = cellEditText,
                    onValueChange = { cellEditText = it },
                    label = { Text("Cell Value (Fix OCR Typos)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = colors.surface,
                        unfocusedContainerColor = colors.surface,
                        focusedBorderColor = colors.brand,
                        unfocusedBorderColor = colors.border,
                        focusedTextColor = colors.content,
                        unfocusedTextColor = colors.content,
                    ),
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val trimmed = cellEditText.trim()
                        if (target.row == -1) {
                            if (target.col in editableHeaders.indices) {
                                editableHeaders[target.col] = trimmed
                            }
                        } else {
                            if (target.row in editableRows.indices && target.col in editableRows[target.row].indices) {
                                editableRows[target.row][target.col] = trimmed
                            }
                        }
                        cellEditTarget = null
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.brand,
                        contentColor = colors.onAccent,
                    ),
                ) {
                    Text("Apply Edit")
                }
            },
            dismissButton = {
                TextButton(onClick = { cellEditTarget = null }) {
                    Text("Cancel", color = colors.muted)
                }
            },
            containerColor = colors.surface,
        )
    }
}

private data class CellCoordinates(
    val row: Int,
    val col: Int,
)

internal fun isRowLikelyFinancialData(row: List<String>): Boolean {
    val nonBlankCells = row.map { it.trim() }.filter { it.isNotBlank() }
    if (nonBlankCells.size < 2) return false

    val combined = nonBlankCells.joinToString(" ")
    val lower = combined.lowercase()

    val noisePhrases = listOf(
        "stamp duty",
        "stt paid",
        "address updated",
        "updation of kyc",
        "registration of nominee",
        "deletion of nominee",
        "change / regn of nominee",
        "opening unit balance",
        "closing unit balance",
        "***cancelled***",
    )
    if (noisePhrases.any { lower.contains(it) }) return false
    if (lower.contains("page ") && lower.contains(" of ")) return false
    if (combined.startsWith("***") && combined.endsWith("***")) return false

    val hasNumericField = nonBlankCells.any { cell ->
        val cleanNum = cell
            .replace(",", "")
            .replace("₹", "")
            .replace("(", "")
            .replace(")", "")
            .trim()
        cleanNum.toBigDecimalOrNull() != null
    }

    return hasNumericField
}
