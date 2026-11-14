package com.openfolio.feature.screens

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.openfolio.core.ui.theme.LocalOpenFolioColors
import com.openfolio.feature.navigation.AppDestination

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onImport: () -> Unit,
    onAddManually: () -> Unit,
    onSettings: () -> Unit,
) {
    var showValues by rememberSaveable { mutableStateOf(true) }
    val colors = LocalOpenFolioColors.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("OpenFolio") },
                actions = {
                    IconButton(onClick = { showValues = !showValues }) {
                        Icon(
                            imageVector = if (showValues) {
                                Icons.Filled.Visibility
                            } else {
                                Icons.Filled.VisibilityOff
                            },
                            contentDescription = if (showValues) {
                                "Hide values"
                            } else {
                                "Show values"
                            },
                        )
                    }
                    IconButton(onClick = onSettings) {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = "Settings",
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onImport,
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("Import") },
                containerColor = colors.brand,
                contentColor = colors.onAccent,
            )
        },
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding)) {
            EmptyScreen(
                destination = AppDestination.Home,
                message = "No assets yet. Import a statement or add one manually.",
                actionLabel = "Import",
                onAction = onImport,
                secondaryActionLabel = "Add Manually",
                onSecondaryAction = onAddManually,
                showTitle = false,
            )
        }
    }
}
