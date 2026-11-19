package com.openfolio.feature.screens

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.openfolio.core.data.local.entity.AssetEntity
import com.openfolio.core.ui.components.MoneyText
import com.openfolio.core.ui.components.formatIndianRupees
import com.openfolio.core.ui.theme.LocalOpenFolioColors
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Composable
fun SellAssetDialog(
    asset: AssetEntity,
    onDismiss: () -> Unit,
    onConfirmSell: (
        quantity: BigDecimal,
        unitPrice: BigDecimal,
        netAmount: BigDecimal,
        date: LocalDate,
        fees: BigDecimal,
        taxes: BigDecimal,
    ) -> Unit,
) {
    val colors = LocalOpenFolioColors.current
    var isFullSell by remember { mutableStateOf(true) }
    var quantityText by remember { mutableStateOf(asset.totalQuantity.toPlainString()) }
    var unitPriceText by remember { mutableStateOf(asset.averageCost.toPlainString()) }
    var netAmountText by remember {
        mutableStateOf(asset.totalQuantity.multiply(asset.averageCost).setScale(2, RoundingMode.HALF_UP).toPlainString())
    }
    val todayStr = remember { LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE) }
    var tradeDateText by remember { mutableStateOf(todayStr) }
    var feesText by remember { mutableStateOf("0") }
    var taxesText by remember { mutableStateOf("0") }

    val isDateValid = remember(tradeDateText) {
        runCatching { LocalDate.parse(tradeDateText.trim()) }.isSuccess
    }

    val qtyBigDecimal = quantityText.toBigDecimalOrNull()
    val priceBigDecimal = unitPriceText.toBigDecimalOrNull()
    val amountBigDecimal = netAmountText.toBigDecimalOrNull()

    val isQtyValid = qtyBigDecimal != null &&
        qtyBigDecimal > BigDecimal.ZERO &&
        qtyBigDecimal <= asset.totalQuantity

    val isFormValid = isQtyValid &&
        isDateValid &&
        priceBigDecimal != null && priceBigDecimal >= BigDecimal.ZERO &&
        amountBigDecimal != null && amountBigDecimal >= BigDecimal.ZERO

    val estimatedCostBasis = remember(qtyBigDecimal, asset.averageCost) {
        if (qtyBigDecimal != null && qtyBigDecimal > BigDecimal.ZERO) {
            qtyBigDecimal.multiply(asset.averageCost)
        } else BigDecimal.ZERO
    }
    val estimatedPnL = remember(amountBigDecimal, estimatedCostBasis) {
        if (amountBigDecimal != null) {
            amountBigDecimal.subtract(estimatedCostBasis)
        } else BigDecimal.ZERO
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(
                    text = "Sell / Exit Asset",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = colors.content,
                )
                Text(
                    text = "${asset.displayName} • Available: ${asset.totalQuantity.toPlainString()} units",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.muted,
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // Sell Type Chips: Full vs Partial
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = isFullSell,
                        onClick = {
                            isFullSell = true
                            quantityText = asset.totalQuantity.toPlainString()
                            val price = unitPriceText.toBigDecimalOrNull() ?: BigDecimal.ZERO
                            netAmountText = asset.totalQuantity.multiply(price).setScale(2, RoundingMode.HALF_UP).toPlainString()
                        },
                        label = { Text("Sell Full (${asset.totalQuantity.toPlainString()})") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = colors.brand,
                            selectedLabelColor = colors.onAccent,
                        ),
                    )
                    FilterChip(
                        selected = !isFullSell,
                        onClick = {
                            isFullSell = false
                        },
                        label = { Text("Partial Sell") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = colors.brand,
                            selectedLabelColor = colors.onAccent,
                        ),
                    )
                }

                // Quantity
                OutlinedTextField(
                    value = quantityText,
                    onValueChange = { newQty ->
                        quantityText = newQty
                        isFullSell = (newQty.toBigDecimalOrNull()?.compareTo(asset.totalQuantity) == 0)
                        val qty = newQty.toBigDecimalOrNull()
                        val price = unitPriceText.toBigDecimalOrNull()
                        if (qty != null && price != null) {
                            netAmountText = qty.multiply(price).setScale(2, RoundingMode.HALF_UP).toPlainString()
                        }
                    },
                    label = { Text("Quantity to Sell") },
                    isError = qtyBigDecimal != null && (qtyBigDecimal <= BigDecimal.ZERO || qtyBigDecimal > asset.totalQuantity),
                    supportingText = {
                        if (qtyBigDecimal != null && qtyBigDecimal > asset.totalQuantity) {
                            Text("Exceeds total holdings (${asset.totalQuantity.toPlainString()})", color = colors.lossGlyph)
                        } else if (qtyBigDecimal != null && isQtyValid) {
                            val remaining = asset.totalQuantity.subtract(qtyBigDecimal)
                            Text("Remaining: ${remaining.toPlainString()} units", color = colors.muted)
                        }
                    },
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

                // Price and Total Amount
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
                        label = { Text("Sell Price (₹)") },
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
                        label = { Text("Proceeds (₹)") },
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

                // Estimated Realized P&L Preview
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    color = if (estimatedPnL.signum() >= 0) colors.gainGlyph.copy(alpha = 0.1f) else colors.lossGlyph.copy(alpha = 0.1f),
                    border = BorderStroke(1.dp, if (estimatedPnL.signum() >= 0) colors.gainGlyph.copy(alpha = 0.3f) else colors.lossGlyph.copy(alpha = 0.3f)),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Est. Realized P&L",
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.content,
                        )
                        val sign = if (estimatedPnL.signum() >= 0) "+" else ""
                        Text(
                            text = "$sign${formatIndianRupees(estimatedPnL)}",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                fontFeatureSettings = "tnum",
                            ),
                            color = if (estimatedPnL.signum() >= 0) colors.gainGlyph else colors.lossGlyph,
                        )
                    }
                }

                // Date
                OutlinedTextField(
                    value = tradeDateText,
                    onValueChange = { tradeDateText = it },
                    label = { Text("Sale Date (YYYY-MM-DD)") },
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

                // Fees and Taxes
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = feesText,
                        onValueChange = { feesText = it },
                        label = { Text("Brokerage / Fees (₹)") },
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
                        value = taxesText,
                        onValueChange = { taxesText = it },
                        label = { Text("STT / Taxes (₹)") },
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
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val parsedDate = LocalDate.parse(tradeDateText.trim())
                    val cleanQty = qtyBigDecimal ?: BigDecimal.ZERO
                    val cleanPrice = priceBigDecimal ?: BigDecimal.ZERO
                    val cleanAmount = amountBigDecimal ?: cleanQty.multiply(cleanPrice)
                    val cleanFees = feesText.toBigDecimalOrNull() ?: BigDecimal.ZERO
                    val cleanTaxes = taxesText.toBigDecimalOrNull() ?: BigDecimal.ZERO

                    onConfirmSell(
                        cleanQty,
                        cleanPrice,
                        cleanAmount,
                        parsedDate,
                        cleanFees,
                        cleanTaxes,
                    )
                    onDismiss()
                },
                enabled = isFormValid,
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.brand,
                    contentColor = colors.onAccent,
                ),
            ) {
                Text(if (isFullSell) "Confirm Full Exit" else "Confirm Sale")
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
