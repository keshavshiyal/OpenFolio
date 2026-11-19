package com.openfolio.feature.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
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
import androidx.compose.ui.unit.dp
import com.openfolio.core.data.local.entity.AssetClass
import com.openfolio.core.ui.theme.LocalOpenFolioColors
import com.openfolio.feature.assets.toDisplayTitle

@Composable
fun ChangeAssetClassDialog(
    currentClass: AssetClass,
    onDismiss: () -> Unit,
    onConfirm: (AssetClass) -> Unit,
) {
    val colors = LocalOpenFolioColors.current
    var selectedClass by remember { mutableStateOf(currentClass) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Change Asset Class",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = colors.content,
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = "Select the correct category for this asset. All transactions will be categorized under this asset class.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.muted,
                )

                AssetClass.entries.forEach { assetClass ->
                    val isSelected = assetClass == selectedClass
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedClass = assetClass },
                        shape = RoundedCornerShape(8.dp),
                        color = if (isSelected) colors.brandSubtle else colors.surface,
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            RadioButton(
                                selected = isSelected,
                                onClick = { selectedClass = assetClass },
                                colors = RadioButtonDefaults.colors(
                                    selectedColor = colors.brand,
                                    unselectedColor = colors.muted,
                                ),
                            )
                            Text(
                                text = assetClass.toDisplayTitle(),
                                style = MaterialTheme.typography.bodyLarge.copy(
                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                ),
                                color = colors.content,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onConfirm(selectedClass)
                    onDismiss()
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.brand,
                    contentColor = colors.onAccent,
                ),
            ) {
                Text("Update Class")
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
