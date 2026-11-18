package com.openfolio.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.openfolio.core.data.local.entity.AssetClass
import com.openfolio.core.data.local.entity.TransactionType
import com.openfolio.core.ui.theme.LocalOpenFolioColors
import com.openfolio.core.utils.DateParser
import com.openfolio.feature.assets.toDisplayTitle
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManualTransactionDialog(
    onDismiss: () -> Unit,
    onSave: (
        assetName: String,
        assetClass: AssetClass,
        type: TransactionType,
        tradeDate: LocalDate,
        quantity: BigDecimal,
        unitPrice: BigDecimal,
        netAmount: BigDecimal,
        fees: BigDecimal,
        taxes: BigDecimal,
        notes: String?,
        broker: String?,
        tickerOrIsin: String?,
    ) -> Unit,
) {
    val colors = LocalOpenFolioColors.current
    var assetName by remember { mutableStateOf("") }
    var tickerOrIsinText by remember { mutableStateOf("") }
    var brokerText by remember { mutableStateOf("") }
    var selectedClass by remember { mutableStateOf(AssetClass.STOCK) }
    var classDropdownExpanded by remember { mutableStateOf(false) }

    var selectedType by remember { mutableStateOf(TransactionType.BUY) }
    var typeDropdownExpanded by remember { mutableStateOf(false) }

    val todayStr = remember { LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE) }
    var tradeDateText by remember { mutableStateOf(todayStr) }
    var quantityText by remember { mutableStateOf("1") }
    var unitPriceText by remember { mutableStateOf("0") }
    var netAmountText by remember { mutableStateOf("0") }
    var feesText by remember { mutableStateOf("0") }
    var taxesText by remember { mutableStateOf("0") }
    var notesText by remember { mutableStateOf("") }

    val parsedDate = remember(tradeDateText) {
        DateParser.parseToLocalDate(tradeDateText)
    }
    val isDateValid = parsedDate != null
    val qtyBigDecimal = quantityText.toBigDecimalOrNull()
    val priceBigDecimal = unitPriceText.toBigDecimalOrNull()
    val amountBigDecimal = netAmountText.toBigDecimalOrNull()

    val isFormValid = assetName.isNotBlank() &&
        isDateValid &&
        qtyBigDecimal != null && qtyBigDecimal > BigDecimal.ZERO &&
        priceBigDecimal != null && priceBigDecimal >= BigDecimal.ZERO &&
        amountBigDecimal != null && amountBigDecimal >= BigDecimal.ZERO

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Manual Entry",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = colors.content,
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // Asset Name
                OutlinedTextField(
                    value = assetName,
                    onValueChange = { assetName = it },
                    label = { Text("Asset Name (e.g., Reliance Industries, HDFC)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = colors.surface,
                        unfocusedContainerColor = colors.surface,
                        focusedBorderColor = colors.brand,
                        unfocusedBorderColor = colors.border,
                        focusedTextColor = colors.content,
                        unfocusedTextColor = colors.content,
                    ),
                )

                // Ticker / ISIN (Optional)
                OutlinedTextField(
                    value = tickerOrIsinText,
                    onValueChange = { tickerOrIsinText = it },
                    label = { Text("Ticker or ISIN (Optional, e.g., RELIANCE, INE002A01018)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = colors.surface,
                        unfocusedContainerColor = colors.surface,
                        focusedBorderColor = colors.brand,
                        unfocusedBorderColor = colors.border,
                        focusedTextColor = colors.content,
                        unfocusedTextColor = colors.content,
                    ),
                )

                // Broker / Institution (Optional)
                OutlinedTextField(
                    value = brokerText,
                    onValueChange = { brokerText = it },
                    label = { Text("Broker / Institution (e.g., Zerodha, Groww, HDFC)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = colors.surface,
                        unfocusedContainerColor = colors.surface,
                        focusedBorderColor = colors.brand,
                        unfocusedBorderColor = colors.border,
                        focusedTextColor = colors.content,
                        unfocusedTextColor = colors.content,
                    ),
                )

                // Asset Class Dropdown
                ExposedDropdownMenuBox(
                    expanded = classDropdownExpanded,
                    onExpandedChange = { classDropdownExpanded = it },
                ) {
                    OutlinedTextField(
                        value = selectedClass.toDisplayTitle(),
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Asset Class") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = classDropdownExpanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                        shape = RoundedCornerShape(8.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = colors.surface,
                            unfocusedContainerColor = colors.surface,
                            focusedBorderColor = colors.brand,
                            unfocusedBorderColor = colors.border,
                            focusedTextColor = colors.content,
                            unfocusedTextColor = colors.content,
                        ),
                    )
                    ExposedDropdownMenu(
                        expanded = classDropdownExpanded,
                        onDismissRequest = { classDropdownExpanded = false },
                        modifier = Modifier.background(colors.surface),
                    ) {
                        AssetClass.entries.forEach { assetClass ->
                            DropdownMenuItem(
                                text = { Text(assetClass.toDisplayTitle(), color = colors.content) },
                                onClick = {
                                    selectedClass = assetClass
                                    classDropdownExpanded = false
                                },
                            )
                        }
                    }
                }

                // Transaction Type Dropdown
                ExposedDropdownMenuBox(
                    expanded = typeDropdownExpanded,
                    onExpandedChange = { typeDropdownExpanded = it },
                ) {
                    OutlinedTextField(
                        value = selectedType.name,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Transaction Type") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = typeDropdownExpanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                        shape = RoundedCornerShape(8.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = colors.surface,
                            unfocusedContainerColor = colors.surface,
                            focusedBorderColor = colors.brand,
                            unfocusedBorderColor = colors.border,
                            focusedTextColor = colors.content,
                            unfocusedTextColor = colors.content,
                        ),
                    )
                    ExposedDropdownMenu(
                        expanded = typeDropdownExpanded,
                        onDismissRequest = { typeDropdownExpanded = false },
                        modifier = Modifier.background(colors.surface),
                    ) {
                        listOf(
                            TransactionType.BUY,
                            TransactionType.SELL,
                            TransactionType.DIVIDEND,
                            TransactionType.INTEREST,
                            TransactionType.FEE,
                            TransactionType.TAX,
                        ).forEach { type ->
                            DropdownMenuItem(
                                text = { Text(type.name, color = colors.content) },
                                onClick = {
                                    selectedType = type
                                    typeDropdownExpanded = false
                                },
                            )
                        }
                    }
                }

                // Trade Date
                OutlinedTextField(
                    value = tradeDateText,
                    onValueChange = { tradeDateText = it },
                    label = { Text("Date (YYYY-MM-DD)") },
                    isError = !isDateValid,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = colors.surface,
                        unfocusedContainerColor = colors.surface,
                        focusedBorderColor = colors.brand,
                        unfocusedBorderColor = colors.border,
                        focusedTextColor = colors.content,
                        unfocusedTextColor = colors.content,
                    ),
                )

                // Quantity
                OutlinedTextField(
                    value = quantityText,
                    onValueChange = { newQty ->
                        quantityText = newQty
                        val qty = newQty.toBigDecimalOrNull()
                        val price = unitPriceText.toBigDecimalOrNull()
                        if (qty != null && price != null) {
                            netAmountText = qty.multiply(price).setScale(2, RoundingMode.HALF_UP).toPlainString()
                        }
                    },
                    label = { Text("Quantity") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = colors.surface,
                        unfocusedContainerColor = colors.surface,
                        focusedBorderColor = colors.brand,
                        unfocusedBorderColor = colors.border,
                        focusedTextColor = colors.content,
                        unfocusedTextColor = colors.content,
                    ),
                )

                // Unit Price & Total Amount in Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = unitPriceText,
                        onValueChange = { newPrice ->
                            unitPriceText = newPrice
                            val qty = quantityText.toBigDecimalOrNull()
                            val price = newPrice.toBigDecimalOrNull()
                            if (qty != null && price != null) {
                                netAmountText = qty.multiply(price).setScale(2, RoundingMode.HALF_UP).toPlainString()
                            }
                        },
                        label = { Text("Price (₹)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = colors.surface,
                            unfocusedContainerColor = colors.surface,
                            focusedBorderColor = colors.brand,
                            unfocusedBorderColor = colors.border,
                            focusedTextColor = colors.content,
                            unfocusedTextColor = colors.content,
                        ),
                    )

                    OutlinedTextField(
                        value = netAmountText,
                        onValueChange = { newAmount ->
                            netAmountText = newAmount
                            val amt = newAmount.toBigDecimalOrNull()
                            val qty = quantityText.toBigDecimalOrNull()
                            if (amt != null && qty != null && qty > BigDecimal.ZERO) {
                                unitPriceText = amt.divide(qty, 4, RoundingMode.HALF_UP).toPlainString()
                            }
                        },
                        label = { Text("Total (₹)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = colors.surface,
                            unfocusedContainerColor = colors.surface,
                            focusedBorderColor = colors.brand,
                            unfocusedBorderColor = colors.border,
                            focusedTextColor = colors.content,
                            unfocusedTextColor = colors.content,
                        ),
                    )
                }

                // Notes
                OutlinedTextField(
                    value = notesText,
                    onValueChange = { notesText = it },
                    label = { Text("Notes / Broker / Order Ref (Optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = colors.surface,
                        unfocusedContainerColor = colors.surface,
                        focusedBorderColor = colors.brand,
                        unfocusedBorderColor = colors.border,
                        focusedTextColor = colors.content,
                        unfocusedTextColor = colors.content,
                    ),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val resolvedDate = parsedDate ?: LocalDate.now()
                    val cleanQty = qtyBigDecimal ?: BigDecimal.ONE
                    val cleanPrice = priceBigDecimal ?: BigDecimal.ZERO
                    val cleanAmount = amountBigDecimal ?: cleanQty.multiply(cleanPrice)
                    val cleanFees = feesText.toBigDecimalOrNull() ?: BigDecimal.ZERO
                    val cleanTaxes = taxesText.toBigDecimalOrNull() ?: BigDecimal.ZERO

                    onSave(
                        assetName.trim(),
                        selectedClass,
                        selectedType,
                        resolvedDate,
                        cleanQty,
                        cleanPrice,
                        cleanAmount,
                        cleanFees,
                        cleanTaxes,
                        notesText.trim().takeIf(String::isNotBlank),
                        brokerText.trim().takeIf(String::isNotBlank),
                        tickerOrIsinText.trim().takeIf(String::isNotBlank),
                    )
                    onDismiss()
                },
                enabled = isFormValid,
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.brand,
                    contentColor = colors.onAccent,
                ),
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = colors.muted)
            }
        },
        containerColor = colors.surface,
    )
}
