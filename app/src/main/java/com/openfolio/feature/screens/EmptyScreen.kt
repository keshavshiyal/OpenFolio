package com.openfolio.feature.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.openfolio.core.ui.theme.LocalOpenFolioColors
import com.openfolio.feature.navigation.AppDestination

@Composable
fun EmptyScreen(
    destination: AppDestination,
    message: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    secondaryActionLabel: String? = null,
    onSecondaryAction: (() -> Unit)? = null,
    showTitle: Boolean = true,
) {
    val colors = LocalOpenFolioColors.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (showTitle) {
            Text(
                text = destination.title,
                style = MaterialTheme.typography.headlineMedium,
                color = colors.content,
            )
            Spacer(modifier = Modifier.height(12.dp))
        }
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = colors.content,
            textAlign = TextAlign.Center,
        )
        if (actionLabel != null && onAction != null) {
            Spacer(modifier = Modifier.height(24.dp))
            Button(onClick = onAction) {
                Text(text = actionLabel)
            }
        }
        if (secondaryActionLabel != null && onSecondaryAction != null) {
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(onClick = onSecondaryAction) {
                Text(text = secondaryActionLabel)
            }
        }
    }
}
