package com.openfolio.core.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openfolio.core.data.local.entity.AssetClass
import com.openfolio.core.data.local.entity.AssetEntity
import com.openfolio.core.data.local.entity.TransactionType
import com.openfolio.core.ui.theme.LocalOpenFolioColors
import com.openfolio.core.utils.AssetNameNormalizer
import com.openfolio.core.utils.DateParser
import com.openfolio.feature.assets.toDisplayTitle
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch
import org.json.JSONObject

data class ManualTransactionInput(
    val assetName: String,
    val assetClass: AssetClass,
    val type: TransactionType,
    val tradeDate: LocalDate,
    val quantity: BigDecimal,
    val unitPrice: BigDecimal,
    val netAmount: BigDecimal,
    val fees: BigDecimal = BigDecimal.ZERO,
    val taxes: BigDecimal = BigDecimal.ZERO,
    val notes: String? = null,
    val broker: String? = null,
    val tickerOrIsin: String? = null,
    val selectedAssetId: String? = null,
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ManualEntryBottomSheet(
    existingAssets: List<AssetEntity> = emptyList(),
    onDismiss: () -> Unit,
    onSave: suspend (ManualTransactionInput) -> Result<Unit>,
) {
    val colors = LocalOpenFolioColors.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val coroutineScope = rememberCoroutineScope()

    var assetName by remember { mutableStateOf("") }
    var selectedExistingAsset by remember { mutableStateOf<AssetEntity?>(null) }
    var assetDropdownExpanded by remember { mutableStateOf(false) }

    var tickerOrIsinText by remember { mutableStateOf("") }
    var brokerText by remember { mutableStateOf("") }
    var selectedClass by remember { mutableStateOf(AssetClass.STOCK) }
    var classDropdownExpanded by remember { mutableStateOf(false) }

    var selectedType by remember { mutableStateOf(TransactionType.BUY) }
    var typeDropdownExpanded by remember { mutableStateOf(false) }

    val today = remember { LocalDate.now() }
    var tradeDateText by remember { mutableStateOf(today.format(DateTimeFormatter.ISO_LOCAL_DATE)) }
    var showDatePicker by remember { mutableStateOf(false) }

    var quantityText by remember { mutableStateOf("1") }
    var unitPriceText by remember { mutableStateOf("") }
    var netAmountText by remember { mutableStateOf("") }
    var isNetManuallyEdited by remember { mutableStateOf(false) }
    var feesText by remember { mutableStateOf("0") }
    var taxesText by remember { mutableStateOf("0") }
    var notesText by remember { mutableStateOf("") }

    var isSaving by remember { mutableStateOf(false) }
    var submitError by remember { mutableStateOf<String?>(null) }

    val cleanName = assetName.trim().replace(Regex("\\s+"), " ")
    val parsedDate = remember(tradeDateText) { DateParser.parseToLocalDate(tradeDateText) }
    val isFutureDate = parsedDate != null && parsedDate.isAfter(today)
    val isDateValid = parsedDate != null && !isFutureDate

    val qtyBd = quantityText.replace(",", "").trim().toBigDecimalOrNull()
    val priceBd = unitPriceText.replace(",", "").trim().toBigDecimalOrNull()
    val amountBd = netAmountText.replace(",", "").trim().toBigDecimalOrNull()
    val feesBd = feesText.replace(",", "").trim().ifBlank { "0" }.toBigDecimalOrNull()
    val taxesBd = taxesText.replace(",", "").trim().ifBlank { "0" }.toBigDecimalOrNull()

    val isQuantityRequired = selectedType in setOf(
        TransactionType.BUY,
        TransactionType.SELL,
        TransactionType.TRANSFER_IN,
        TransactionType.TRANSFER_OUT,
        TransactionType.BONUS,
        TransactionType.SPLIT,
    )
    val isPriceRequired = selectedType == TransactionType.BUY || selectedType == TransactionType.SELL
    val isCashAmountRequired = selectedType in setOf(
        TransactionType.DIVIDEND,
        TransactionType.INTEREST,
        TransactionType.FEE,
        TransactionType.TAX,
        TransactionType.CHARGE,
    )
    val isSellOrOut = selectedType == TransactionType.SELL || selectedType == TransactionType.TRANSFER_OUT

    val cleanId = tickerOrIsinText.trim().uppercase()
    val isIsinCandidate = cleanId.length == 12 && cleanId.take(2).all { it.isLetter() }
    val isIsinOrTickerValid = cleanId.isBlank() || if (isIsinCandidate) {
        AssetNameNormalizer.extractIsin(cleanId) != null
    } else {
        TICKER_PATTERN.matches(cleanId)
    }

    val filteredAssets = remember(existingAssets, assetName) {
        val q = assetName.trim()
        if (q.isBlank()) {
            existingAssets.sortedBy { it.displayName.lowercase() }
        } else {
            existingAssets.filter { asset ->
                asset.displayName.contains(q, ignoreCase = true) ||
                    asset.identifiersJson.contains(q, ignoreCase = true) ||
                    asset.assetClass.toDisplayTitle().contains(q, ignoreCase = true)
            }.sortedBy { it.displayName.lowercase() }
        }
    }

    val nearMatchSuggestion = remember(existingAssets, cleanName, selectedExistingAsset, cleanId) {
        if (selectedExistingAsset != null || cleanName.length < 2) {
            null
        } else {
            AssetNameNormalizer.findMatchingAsset(
                rawName = cleanName,
                existingAssets = existingAssets,
                identifier = cleanId.takeIf(String::isNotBlank),
            )
        }
    }

    val oversellError: String? = remember(isSellOrOut, selectedExistingAsset, nearMatchSuggestion, qtyBd, cleanName) {
        if (!isSellOrOut) return@remember null
        val target = selectedExistingAsset ?: nearMatchSuggestion
        if (target == null) {
            if (cleanName.isNotBlank()) "Select an existing asset with holdings before recording ${selectedType.name}." else null
        } else if (target.totalQuantity <= BigDecimal.ZERO) {
            "${target.displayName} has 0 units held."
        } else if (qtyBd != null && qtyBd > target.totalQuantity) {
            "Exceeds held quantity (${target.totalQuantity.stripTrailingZeros().toPlainString()} units available)."
        } else {
            null
        }
    }

    val isAssetNameValid = cleanName.length >= 2 && cleanName.any { it.isLetterOrDigit() }
    val isQtyValid = if (isQuantityRequired) {
        qtyBd != null && qtyBd > BigDecimal.ZERO
    } else {
        qtyBd == null || qtyBd >= BigDecimal.ZERO
    }
    val isPriceValid = if (isPriceRequired) {
        (priceBd != null && priceBd > BigDecimal.ZERO) || (amountBd != null && amountBd > BigDecimal.ZERO)
    } else {
        priceBd == null || priceBd >= BigDecimal.ZERO
    }
    val isAmountValid = if (isCashAmountRequired || isPriceRequired) {
        amountBd != null && amountBd > BigDecimal.ZERO
    } else {
        amountBd != null && amountBd >= BigDecimal.ZERO
    }
    val areChargesValid = (feesBd != null && feesBd >= BigDecimal.ZERO) &&
        (taxesBd != null && taxesBd >= BigDecimal.ZERO)

    val isFormValid = !isSaving &&
        isAssetNameValid &&
        isDateValid &&
        isQtyValid &&
        isPriceValid &&
        isAmountValid &&
        areChargesValid &&
        isIsinOrTickerValid &&
        oversellError == null

    fun selectAsset(asset: AssetEntity) {
        selectedExistingAsset = asset
        assetName = asset.displayName
        selectedClass = asset.assetClass
        val ids = extractPreferredIdentifier(asset.identifiersJson)
        if (ids != null) {
            tickerOrIsinText = ids
        }
        assetDropdownExpanded = false
        submitError = null
    }

    fun recomputeNetAmount(
        newQty: String = quantityText,
        newPrice: String = unitPriceText,
        newFees: String = feesText,
        newTaxes: String = taxesText,
        newType: TransactionType = selectedType,
    ) {
        if (isNetManuallyEdited) return
        val q = newQty.replace(",", "").trim().toBigDecimalOrNull() ?: return
        val p = newPrice.replace(",", "").trim().toBigDecimalOrNull() ?: return
        val f = newFees.replace(",", "").trim().ifBlank { "0" }.toBigDecimalOrNull() ?: BigDecimal.ZERO
        val t = newTaxes.replace(",", "").trim().ifBlank { "0" }.toBigDecimalOrNull() ?: BigDecimal.ZERO
        val gross = q.multiply(p)
        val computed = when (newType) {
            TransactionType.SELL, TransactionType.TRANSFER_OUT ->
                gross.subtract(f).subtract(t).max(BigDecimal.ZERO)
            else -> gross.add(f).add(t)
        }
        netAmountText = computed.setScale(2, RoundingMode.HALF_UP).toPlainString()
    }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedContainerColor = colors.surface,
        unfocusedContainerColor = colors.surface,
        disabledContainerColor = colors.background,
        focusedBorderColor = colors.brand,
        unfocusedBorderColor = colors.border,
        focusedTextColor = colors.content,
        unfocusedTextColor = colors.content,
    )

    ModalBottomSheet(
        onDismissRequest = { if (!isSaving) onDismiss() },
        sheetState = sheetState,
        containerColor = colors.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 640.dp)
                .align(Alignment.CenterHorizontally)
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Manual Entry",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = colors.content,
                    )
                    Text(
                        text = "Select an existing asset or type a new one. Saved entries can be undone from Inbox.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.muted,
                    )
                }
                IconButton(onClick = { if (!isSaving) onDismiss() }) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Close",
                        tint = colors.muted,
                    )
                }
            }

            if (submitError != null) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    color = colors.lossGlyph.copy(alpha = 0.08f),
                    border = BorderStroke(1.dp, colors.lossGlyph.copy(alpha = 0.35f)),
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Warning,
                            contentDescription = null,
                            tint = colors.lossGlyph,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = submitError.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.lossGlyph,
                        )
                    }
                }
            }

            // Scrollable form body
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // Asset Name Selector (Existing assets dropdown + free text for new asset)
                ExposedDropdownMenuBox(
                    expanded = assetDropdownExpanded,
                    onExpandedChange = { assetDropdownExpanded = it },
                ) {
                    OutlinedTextField(
                        value = assetName,
                        onValueChange = { typed ->
                            assetName = typed
                            submitError = null
                            if (selectedExistingAsset != null &&
                                !typed.trim().equals(selectedExistingAsset?.displayName, ignoreCase = true)
                            ) {
                                selectedExistingAsset = null
                            }
                            assetDropdownExpanded = existingAssets.isNotEmpty()
                        },
                        label = { Text("Asset Name *") },
                        placeholder = {
                            Text(
                                if (existingAssets.isNotEmpty()) {
                                    "Choose existing asset or type full name"
                                } else {
                                    "Enter full official asset name"
                                },
                            )
                        },
                        trailingIcon = {
                            if (selectedExistingAsset != null || assetName.isNotEmpty()) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(
                                        onClick = {
                                            assetName = ""
                                            selectedExistingAsset = null
                                            tickerOrIsinText = ""
                                            assetDropdownExpanded = existingAssets.isNotEmpty()
                                        },
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Close,
                                            contentDescription = "Clear asset",
                                            tint = colors.muted,
                                            modifier = Modifier.size(18.dp),
                                        )
                                    }
                                    if (existingAssets.isNotEmpty()) {
                                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = assetDropdownExpanded)
                                    }
                                }
                            } else if (existingAssets.isNotEmpty()) {
                                ExposedDropdownMenuDefaults.TrailingIcon(expanded = assetDropdownExpanded)
                            }
                        },
                        isError = assetName.isNotBlank() && !isAssetNameValid,
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(MenuAnchorType.PrimaryEditable),
                        shape = RoundedCornerShape(10.dp),
                        colors = fieldColors,
                    )

                    if (existingAssets.isNotEmpty()) {
                        ExposedDropdownMenu(
                            expanded = assetDropdownExpanded,
                            onDismissRequest = { assetDropdownExpanded = false },
                            modifier = Modifier
                                .background(colors.surface)
                                .heightIn(max = 240.dp),
                        ) {
                            filteredAssets.forEach { asset ->
                                val idBadge = extractPreferredIdentifier(asset.identifiersJson)
                                DropdownMenuItem(
                                    text = {
                                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                            Text(
                                                text = asset.displayName,
                                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                                color = colors.content,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            val qtyText = asset.totalQuantity.stripTrailingZeros().toPlainString()
                                            val suffix = idBadge?.let { " • $it" }.orEmpty()
                                            Text(
                                                text = "${asset.assetClass.toDisplayTitle()} • $qtyText units$suffix",
                                                style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                                                color = colors.muted,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    },
                                    onClick = { selectAsset(asset) },
                                )
                            }
                            if (cleanName.isNotBlank() && filteredAssets.none { it.displayName.equals(cleanName, ignoreCase = true) }) {
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = "+ Create new asset: \"$cleanName\"",
                                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                                            color = colors.brand,
                                        )
                                    },
                                    onClick = {
                                        selectedExistingAsset = null
                                        assetDropdownExpanded = false
                                    },
                                )
                            }
                        }
                    }
                }

                // Asset selection / guidance banner
                when {
                    selectedExistingAsset != null -> {
                        val active = selectedExistingAsset!!
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp),
                            color = colors.brandSubtle,
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.CheckCircle,
                                    contentDescription = null,
                                    tint = colors.brand,
                                    modifier = Modifier.size(16.dp),
                                )
                                Text(
                                    text = "Existing asset • ${active.totalQuantity.stripTrailingZeros().toPlainString()} units held",
                                    style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                                    color = colors.brand,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                    nearMatchSuggestion != null -> {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectAsset(nearMatchSuggestion) },
                            shape = RoundedCornerShape(8.dp),
                            color = colors.brandSubtle,
                            border = BorderStroke(1.dp, colors.brand.copy(alpha = 0.4f)),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Info,
                                    contentDescription = null,
                                    tint = colors.brand,
                                    modifier = Modifier.size(16.dp),
                                )
                                Text(
                                    text = "Did you mean existing asset \"${nearMatchSuggestion.displayName}\"? Tap to select.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = colors.brand,
                                )
                            }
                        }
                    }
                    else -> {
                        Text(
                            text = if (cleanName.isBlank()) {
                                "Pick an existing asset from the dropdown, or type a full official name if adding a new asset."
                            } else {
                                "New asset — please enter the complete, accurate name (avoid abbreviations to prevent duplicates)."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.muted,
                        )
                    }
                }

                // Asset Class & Transaction Type (responsive row/stack)
                BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                    val stackFields = maxWidth < 360.dp
                    val classField: @Composable (Modifier) -> Unit = { mod ->
                        ExposedDropdownMenuBox(
                            expanded = classDropdownExpanded && selectedExistingAsset == null,
                            onExpandedChange = {
                                if (selectedExistingAsset == null) classDropdownExpanded = it
                            },
                            modifier = mod,
                        ) {
                            OutlinedTextField(
                                value = selectedClass.toDisplayTitle(),
                                onValueChange = {},
                                readOnly = true,
                                enabled = selectedExistingAsset == null,
                                label = { Text("Asset Class") },
                                trailingIcon = {
                                    if (selectedExistingAsset == null) {
                                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = classDropdownExpanded)
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                                shape = RoundedCornerShape(10.dp),
                                colors = fieldColors,
                            )
                            ExposedDropdownMenu(
                                expanded = classDropdownExpanded && selectedExistingAsset == null,
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
                    }
                    val typeField: @Composable (Modifier) -> Unit = { mod ->
                        ExposedDropdownMenuBox(
                            expanded = typeDropdownExpanded,
                            onExpandedChange = { typeDropdownExpanded = it },
                            modifier = mod,
                        ) {
                            OutlinedTextField(
                                value = selectedType.name.replace("_", " "),
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("Type *") },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = typeDropdownExpanded) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                                shape = RoundedCornerShape(10.dp),
                                colors = fieldColors,
                            )
                            ExposedDropdownMenu(
                                expanded = typeDropdownExpanded,
                                onDismissRequest = { typeDropdownExpanded = false },
                                modifier = Modifier.background(colors.surface),
                            ) {
                                MANUAL_TRANSACTION_TYPES.forEach { type ->
                                    DropdownMenuItem(
                                        text = { Text(type.name.replace("_", " "), color = colors.content) },
                                        onClick = {
                                            selectedType = type
                                            typeDropdownExpanded = false
                                            submitError = null
                                            recomputeNetAmount(newType = type)
                                        },
                                    )
                                }
                            }
                        }
                    }

                    if (stackFields) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            classField(Modifier.fillMaxWidth())
                            typeField(Modifier.fillMaxWidth())
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            classField(Modifier.weight(1f))
                            typeField(Modifier.weight(1f))
                        }
                    }
                }

                // Trade Date with Calendar picker
                OutlinedTextField(
                    value = tradeDateText,
                    onValueChange = {
                        tradeDateText = it
                        submitError = null
                    },
                    label = { Text("Date (YYYY-MM-DD) *") },
                    isError = !isDateValid,
                    supportingText = when {
                        parsedDate == null -> { { Text("Enter a valid date (YYYY-MM-DD)", color = colors.lossGlyph) } }
                        isFutureDate -> { { Text("Future trade dates are not allowed", color = colors.lossGlyph) } }
                        else -> null
                    },
                    trailingIcon = {
                        IconButton(onClick = { showDatePicker = true }) {
                            Icon(
                                imageVector = Icons.Filled.CalendarMonth,
                                contentDescription = "Pick date",
                                tint = colors.brand,
                            )
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    colors = fieldColors,
                )

                // Quantity & Unit Price
                BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                    val stack = maxWidth < 340.dp
                    val qtyField: @Composable (Modifier) -> Unit = { mod ->
                        OutlinedTextField(
                            value = quantityText,
                            onValueChange = { newQty ->
                                quantityText = newQty
                                submitError = null
                                recomputeNetAmount(newQty = newQty)
                            },
                            label = { Text(if (isQuantityRequired) "Quantity *" else "Quantity") },
                            isError = (quantityText.isNotBlank() && !isQtyValid) || oversellError != null,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            modifier = mod,
                            shape = RoundedCornerShape(10.dp),
                            colors = fieldColors,
                        )
                    }
                    val priceField: @Composable (Modifier) -> Unit = { mod ->
                        OutlinedTextField(
                            value = unitPriceText,
                            onValueChange = { newPrice ->
                                unitPriceText = newPrice
                                submitError = null
                                recomputeNetAmount(newPrice = newPrice)
                            },
                            label = { Text(if (isPriceRequired) "Unit Price (₹)" else "Price (₹)") },
                            isError = unitPriceText.isNotBlank() && priceBd == null,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            modifier = mod,
                            shape = RoundedCornerShape(10.dp),
                            colors = fieldColors,
                        )
                    }

                    if (stack) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            qtyField(Modifier.fillMaxWidth())
                            priceField(Modifier.fillMaxWidth())
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            qtyField(Modifier.weight(1f))
                            priceField(Modifier.weight(1f))
                        }
                    }
                }

                if (oversellError != null) {
                    Text(
                        text = oversellError,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.lossGlyph,
                    )
                }

                // Total / Net Amount
                OutlinedTextField(
                    value = netAmountText,
                    onValueChange = { newAmount ->
                        netAmountText = newAmount
                        isNetManuallyEdited = newAmount.isNotBlank()
                        submitError = null
                        val amt = newAmount.replace(",", "").trim().toBigDecimalOrNull()
                        val qty = qtyBd
                        if (amt != null && qty != null && qty > BigDecimal.ZERO && unitPriceText.isBlank()) {
                            unitPriceText = amt.divide(qty, 4, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
                        }
                    },
                    label = { Text("Total / Net Amount (₹) *") },
                    isError = netAmountText.isNotBlank() && !isAmountValid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    colors = fieldColors,
                )

                // Fees & Taxes
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedTextField(
                        value = feesText,
                        onValueChange = { newFees ->
                            feesText = newFees
                            recomputeNetAmount(newFees = newFees)
                        },
                        label = { Text("Fees (₹)") },
                        isError = feesBd == null || feesBd < BigDecimal.ZERO,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                        colors = fieldColors,
                    )
                    OutlinedTextField(
                        value = taxesText,
                        onValueChange = { newTaxes ->
                            taxesText = newTaxes
                            recomputeNetAmount(newTaxes = newTaxes)
                        },
                        label = { Text("Taxes (₹)") },
                        isError = taxesBd == null || taxesBd < BigDecimal.ZERO,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                        colors = fieldColors,
                    )
                }

                // Ticker / ISIN & Broker
                OutlinedTextField(
                    value = tickerOrIsinText,
                    onValueChange = {
                        tickerOrIsinText = it
                        submitError = null
                    },
                    label = { Text("Ticker or ISIN (Optional)") },
                    placeholder = { Text("e.g., RELIANCE or INE002A01018") },
                    isError = !isIsinOrTickerValid,
                    supportingText = if (!isIsinOrTickerValid) {
                        { Text("Enter a valid 12-char ISIN or symbol (max 20 chars, no spaces)", color = colors.lossGlyph) }
                    } else null,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    colors = fieldColors,
                )

                OutlinedTextField(
                    value = brokerText,
                    onValueChange = { brokerText = it },
                    label = { Text("Broker / Institution (Optional)") },
                    placeholder = { Text("e.g., Zerodha, Groww, HDFC Bank") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    colors = fieldColors,
                )

                OutlinedTextField(
                    value = notesText,
                    onValueChange = { notesText = it },
                    label = { Text("Notes / Reference (Optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    colors = fieldColors,
                )
            }

            // Action Buttons
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    enabled = !isSaving,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, colors.border),
                ) {
                    Text("Cancel", color = colors.muted)
                }
                Button(
                    onClick = {
                        val resolvedDate = parsedDate ?: today
                        val cleanQty = qtyBd ?: if (isQuantityRequired) BigDecimal.ONE else BigDecimal.ZERO
                        val cleanAmount = amountBd ?: BigDecimal.ZERO
                        val cleanPrice = priceBd ?: if (cleanQty > BigDecimal.ZERO && cleanAmount > BigDecimal.ZERO) {
                            cleanAmount.divide(cleanQty, 4, RoundingMode.HALF_UP)
                        } else {
                            BigDecimal.ZERO
                        }
                        val cleanFees = feesBd ?: BigDecimal.ZERO
                        val cleanTaxes = taxesBd ?: BigDecimal.ZERO

                        val input = ManualTransactionInput(
                            assetName = selectedExistingAsset?.displayName ?: cleanName,
                            assetClass = selectedExistingAsset?.assetClass ?: selectedClass,
                            type = selectedType,
                            tradeDate = resolvedDate,
                            quantity = cleanQty,
                            unitPrice = cleanPrice,
                            netAmount = cleanAmount,
                            fees = cleanFees,
                            taxes = cleanTaxes,
                            notes = notesText.trim().takeIf(String::isNotBlank),
                            broker = brokerText.trim().takeIf(String::isNotBlank),
                            tickerOrIsin = cleanId.takeIf(String::isNotBlank),
                            selectedAssetId = selectedExistingAsset?.id,
                        )
                        isSaving = true
                        submitError = null
                        coroutineScope.launch {
                            val result = onSave(input)
                            isSaving = false
                            result.onSuccess {
                                onDismiss()
                            }.onFailure { err ->
                                submitError = err.message ?: "Unable to save manual entry."
                            }
                        }
                    },
                    enabled = isFormValid,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.brand,
                        contentColor = colors.onAccent,
                    ),
                ) {
                    if (isSaving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = colors.onAccent,
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Saving...")
                    } else {
                        Text("Save Entry")
                    }
                }
            }
        }
    }

    if (showDatePicker) {
        val initialMillis = (parsedDate ?: today)
            .atStartOfDay(ZoneOffset.UTC)
            .toInstant()
            .toEpochMilli()
        val todayMillis = today.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = initialMillis,
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean = utcTimeMillis <= todayMillis
            },
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        datePickerState.selectedDateMillis?.let { millis ->
                            val picked = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                            tradeDateText = picked.format(DateTimeFormatter.ISO_LOCAL_DATE)
                            submitError = null
                        }
                        showDatePicker = false
                    },
                ) {
                    Text("OK", color = colors.brand)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text("Cancel", color = colors.muted)
                }
            },
        ) {
            DatePicker(state = datePickerState)
        }
    }
}

@Composable
fun ManualTransactionDialog(
    existingAssets: List<AssetEntity> = emptyList(),
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
    ManualEntryBottomSheet(
        existingAssets = existingAssets,
        onDismiss = onDismiss,
        onSave = { input ->
            onSave(
                input.assetName,
                input.assetClass,
                input.type,
                input.tradeDate,
                input.quantity,
                input.unitPrice,
                input.netAmount,
                input.fees,
                input.taxes,
                input.notes,
                input.broker,
                input.tickerOrIsin,
            )
            Result.success(Unit)
        },
    )
}

private fun extractPreferredIdentifier(identifiersJson: String): String? {
    val obj = runCatching { JSONObject(identifiersJson) }.getOrNull() ?: return null
    val isin = obj.optString("isin").trim().takeIf(String::isNotBlank)
    if (isin != null) return isin
    return obj.optString("ticker").trim().takeIf(String::isNotBlank)
}

private val TICKER_PATTERN = Regex("^[A-Z0-9._-]{1,20}$")
private val MANUAL_TRANSACTION_TYPES = listOf(
    TransactionType.BUY,
    TransactionType.SELL,
    TransactionType.DIVIDEND,
    TransactionType.INTEREST,
    TransactionType.FEE,
    TransactionType.TAX,
    TransactionType.CHARGE,
    TransactionType.TRANSFER_IN,
    TransactionType.TRANSFER_OUT,
    TransactionType.BONUS,
    TransactionType.SPLIT,
)
