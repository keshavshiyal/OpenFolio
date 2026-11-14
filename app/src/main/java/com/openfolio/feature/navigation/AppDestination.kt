package com.openfolio.feature.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector

enum class AppDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
    val title: String,
) {
    Home("home", "Home", Icons.Filled.Home, "Home"),
    Assets("assets", "Assets", Icons.Filled.AccountBalanceWallet, "Assets"),
    Activity("activity", "Activity", Icons.Filled.History, "Activity"),
    Inbox("inbox", "Inbox", Icons.Filled.Inbox, "Inbox"),
    Settings("settings", "Settings", Icons.Filled.Settings, "Settings"),
}
