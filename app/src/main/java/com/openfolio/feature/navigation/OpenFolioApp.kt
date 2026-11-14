package com.openfolio.feature.navigation

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.openfolio.core.ui.theme.LocalOpenFolioColors
import com.openfolio.feature.home.HomeViewModel
import com.openfolio.feature.screens.EmptyScreen
import com.openfolio.feature.screens.HomeScreen

@Composable
fun OpenFolioApp(homeViewModel: HomeViewModel) {
    val navController = rememberNavController()
    val colors = LocalOpenFolioColors.current
    val homeUiState by homeViewModel.uiState.collectAsStateWithLifecycle()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    Scaffold(
        containerColor = colors.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            NavigationBar(
                modifier = Modifier.drawBehind {
                    val strokeWidth = 1.dp.toPx()
                    drawLine(
                        color = colors.border,
                        start = Offset(0f, 0f),
                        end = Offset(size.width, 0f),
                        strokeWidth = strokeWidth,
                    )
                },
                containerColor = colors.background,
                contentColor = colors.content,
            ) {
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
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = colors.brand,
                            selectedTextColor = colors.brand,
                            unselectedIconColor = colors.muted,
                            unselectedTextColor = colors.muted,
                            indicatorColor = Color.Transparent,
                        ),
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
                HomeScreen(
                    uiState = homeUiState,
                    onToggleValues = homeViewModel::toggleValuesVisibility,
                    onSelectPeriod = homeViewModel::selectPeriod,
                    onImport = { navController.navigate(AppDestination.Inbox.route) },
                    onSettings = { navController.navigate(AppDestination.Settings.route) },
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
                    message = "Settings coming soon in Phase 6",
                )
            }
        }
    }
}
