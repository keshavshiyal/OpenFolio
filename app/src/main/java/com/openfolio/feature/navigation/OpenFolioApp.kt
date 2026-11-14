package com.openfolio.feature.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.openfolio.feature.screens.EmptyScreen

@Composable
fun OpenFolioApp() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    Scaffold(
        bottomBar = {
            NavigationBar {
                AppDestination.entries.forEach { destination ->
                    NavigationBarItem(
                        selected = currentRoute == destination.route,
                        onClick = {
                            navController.navigate(destination.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = {
                            Icon(
                                imageVector = destination.icon,
                                contentDescription = destination.label,
                            )
                        },
                        label = { Text(destination.label) },
                    )
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = AppDestination.Home.route,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(AppDestination.Home.route) {
                EmptyScreen(
                    destination = AppDestination.Home,
                    message = "Add your first asset to start tracking your net worth.",
                    actionLabel = "Browse assets",
                    onAction = { navController.navigate(AppDestination.Assets.route) },
                )
            }
            composable(AppDestination.Assets.route) {
                EmptyScreen(
                    destination = AppDestination.Assets,
                    message = "No assets yet. Import a statement or add one manually.",
                    actionLabel = "Import a statement",
                    onAction = { navController.navigate(AppDestination.Inbox.route) },
                )
            }
            composable(AppDestination.Activity.route) {
                EmptyScreen(
                    destination = AppDestination.Activity,
                    message = "Your confirmed transactions will appear here.",
                    actionLabel = "Review imported files",
                    onAction = { navController.navigate(AppDestination.Inbox.route) },
                )
            }
            composable(AppDestination.Inbox.route) {
                EmptyScreen(
                    destination = AppDestination.Inbox,
                    message = "No documents to review. Import a statement to get started.",
                    actionLabel = "View assets",
                    onAction = { navController.navigate(AppDestination.Assets.route) },
                )
            }
            composable(AppDestination.Settings.route) {
                EmptyScreen(
                    destination = AppDestination.Settings,
                    message = "Manage privacy, backups, and your AI preferences here.",
                    actionLabel = "Go to Home",
                    onAction = { navController.navigate(AppDestination.Home.route) },
                )
            }
        }
    }
}
