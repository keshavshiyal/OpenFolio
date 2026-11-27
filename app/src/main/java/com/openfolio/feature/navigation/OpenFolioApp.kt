package com.openfolio.feature.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.openfolio.core.ui.theme.LocalOpenFolioColors
import com.openfolio.feature.activity.ActivityViewModel
import com.openfolio.feature.assets.AssetDetailViewModel
import com.openfolio.feature.assets.AssetsViewModel
import com.openfolio.feature.home.HomeViewModel
import com.openfolio.feature.inbox.InboxViewModel
import com.openfolio.feature.screens.ActivityScreen
import com.openfolio.feature.screens.AssetDetailScreen
import com.openfolio.feature.screens.AssetsScreen
import com.openfolio.feature.screens.EmptyScreen
import com.openfolio.feature.screens.HomeScreen
import com.openfolio.feature.screens.InboxScreen
import com.openfolio.feature.screens.ReviewScreen
import com.openfolio.feature.screens.SettingsScreen
import com.openfolio.feature.settings.SettingsViewModel
import com.openfolio.feature.review.ReviewViewModel

private const val REVIEW_ROUTE = "review/{documentId}"
private const val ASSET_DETAIL_ROUTE = "asset_detail/{assetId}"

@Composable
fun OpenFolioApp(
    homeViewModel: HomeViewModel,
    assetsViewModel: AssetsViewModel,
    assetDetailViewModel: AssetDetailViewModel,
    activityViewModel: ActivityViewModel,
    inboxViewModel: InboxViewModel,
    reviewViewModel: ReviewViewModel,
    settingsViewModel: SettingsViewModel,
) {
    val navController = rememberNavController()
    val colors = LocalOpenFolioColors.current
    val homeUiState by homeViewModel.uiState.collectAsStateWithLifecycle()
    val ingestionState by homeViewModel.ingestionState.collectAsStateWithLifecycle()
    val existingAssets by homeViewModel.existingAssets.collectAsStateWithLifecycle()
    val inboxDocuments by inboxViewModel.documents.collectAsStateWithLifecycle()
    val inboxActionMessage by inboxViewModel.actionMessage.collectAsStateWithLifecycle()
    val reviewUiState by reviewViewModel.uiState.collectAsStateWithLifecycle()
    val reviewDrafts by reviewViewModel.drafts.collectAsStateWithLifecycle()
    val reviewDraftReviewItems by reviewViewModel.draftReviewItems.collectAsStateWithLifecycle()
    val structuredHeaders by reviewViewModel.structuredHeaders.collectAsStateWithLifecycle()
    val reviewActionState by reviewViewModel.actionState.collectAsStateWithLifecycle()
    val reviewErrorMessage by reviewViewModel.errorMessage.collectAsStateWithLifecycle()
    val reviewBatchBannerMessage by reviewViewModel.batchBannerMessage.collectAsStateWithLifecycle()
    val isPasswordProtected by reviewViewModel.isPasswordProtected.collectAsStateWithLifecycle()
    val passwordError by reviewViewModel.passwordError.collectAsStateWithLifecycle()
    val unlockedPassword by reviewViewModel.unlockedPassword.collectAsStateWithLifecycle()
    val extractedTables by reviewViewModel.extractedTables.collectAsStateWithLifecycle()
    val showTableReview by reviewViewModel.showTableReview.collectAsStateWithLifecycle()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showNavigationChrome = currentRoute != REVIEW_ROUTE &&
        currentRoute?.startsWith("asset_detail") != true

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useNavRail = maxWidth >= 600.dp && showNavigationChrome

        val navigateToDestination: (AppDestination) -> Unit = { destination ->
            if (currentRoute != destination.route) {
                navController.navigate(destination.route) {
                    popUpTo(navController.graph.findStartDestination().id) {
                        inclusive = (destination.route == navController.graph.findStartDestination().route)
                        saveState = false
                    }
                    launchSingleTop = true
                    restoreState = false
                }
            } else {
                navController.popBackStack(destination.route, inclusive = false)
            }
        }

        Scaffold(
            containerColor = colors.background,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            bottomBar = {
                if (showNavigationChrome && !useNavRail) {
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
                                onClick = { navigateToDestination(destination) },
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
                }
            },
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                if (useNavRail) {
                    NavigationRail(
                        containerColor = colors.background,
                        contentColor = colors.content,
                    ) {
                        Spacer(modifier = Modifier.weight(1f))
                        AppDestination.entries.forEach { destination ->
                            NavigationRailItem(
                                selected = currentRoute == destination.route,
                                onClick = { navigateToDestination(destination) },
                                colors = NavigationRailItemDefaults.colors(
                                    selectedIconColor = colors.brand,
                                    selectedTextColor = colors.brand,
                                    unselectedIconColor = colors.muted,
                                    unselectedTextColor = colors.muted,
                                    indicatorColor = colors.brandSubtle,
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
                        Spacer(modifier = Modifier.weight(1f))
                    }
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .fillMaxHeight()
                            .background(colors.border),
                    )
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    NavHost(
                        navController = navController,
                        startDestination = AppDestination.Home.route,
                        modifier = if (currentRoute == REVIEW_ROUTE) {
                            Modifier.fillMaxSize()
                        } else {
                            Modifier
                                .widthIn(max = 840.dp)
                                .fillMaxSize()
                        },
                    ) {
            composable(AppDestination.Home.route) {
                HomeScreen(
                    uiState = homeUiState,
                    ingestionState = ingestionState,
                    onToggleValues = homeViewModel::toggleValuesVisibility,
                    onSelectPeriod = homeViewModel::selectPeriod,
                    onImport = { uri, assetClass -> homeViewModel.ingestFile(uri, assetClass) },
                    onImportSuccess = {
                        navController.navigate(AppDestination.Inbox.route) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = false
                            }
                            launchSingleTop = true
                            restoreState = false
                        }
                    },
                    onIngestionHandled = homeViewModel::clearIngestionState,
                    onSettings = { navController.navigate(AppDestination.Settings.route) },
                    existingAssets = existingAssets,
                    onSaveManualTransaction = homeViewModel::recordManualTransaction,
                    onSaveManualTransactionResult = homeViewModel::recordManualTransactionResult,
                )
            }
            composable(AppDestination.Assets.route) {
                AssetsScreen(
                    viewModel = assetsViewModel,
                    onAssetClick = { assetId ->
                        navController.navigate("asset_detail/$assetId")
                    },
                    onImportClick = {
                        navController.navigate(AppDestination.Inbox.route) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = false
                            }
                            launchSingleTop = true
                            restoreState = false
                        }
                    },
                    existingAssets = existingAssets,
                    onSaveManualTransaction = homeViewModel::recordManualTransaction,
                    onSaveManualTransactionResult = homeViewModel::recordManualTransactionResult,
                )
            }
            composable(
                route = ASSET_DETAIL_ROUTE,
                arguments = listOf(navArgument("assetId") { type = NavType.StringType }),
            ) { entry ->
                val assetId = entry.arguments?.getString("assetId").orEmpty()
                LaunchedEffect(assetId) {
                    assetDetailViewModel.loadAsset(assetId)
                }
                AssetDetailScreen(
                    viewModel = assetDetailViewModel,
                    onBack = { navController.popBackStack() },
                )
            }
            composable(AppDestination.Activity.route) {
                ActivityScreen(
                    viewModel = activityViewModel,
                    onNavigateToInbox = {
                        navController.navigate(AppDestination.Inbox.route) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = false
                            }
                            launchSingleTop = true
                            restoreState = false
                        }
                    },
                    onNavigateToAsset = { assetId ->
                        navController.navigate("asset_detail/$assetId")
                    },
                )
            }
            composable(AppDestination.Inbox.route) {
                InboxScreen(
                    documents = inboxDocuments,
                    onDocumentClick = { documentId ->
                        navController.navigate("review/$documentId")
                    },
                    onProcessWithAiClick = { documentId ->
                        navController.navigate("review/$documentId")
                    },
                    onRetryClick = inboxViewModel::retryOrResume,
                    onRollbackImport = { doc -> inboxViewModel.rollbackImport(doc.id) },
                    actionMessage = inboxActionMessage,
                    onDismissActionMessage = inboxViewModel::clearActionMessage,
                    onImport = homeViewModel::ingestFile,
                    existingAssets = existingAssets,
                    onSaveManualTransaction = homeViewModel::recordManualTransaction,
                    onSaveManualTransactionResult = homeViewModel::recordManualTransactionResult,
                )
            }
            composable(
                route = REVIEW_ROUTE,
                arguments = listOf(navArgument("documentId") { type = NavType.StringType }),
            ) { entry ->
                val documentId = entry.arguments?.getString("documentId").orEmpty()
                LaunchedEffect(documentId) {
                    reviewViewModel.loadDocument(documentId)
                }
                ReviewScreen(
                    uiState = reviewUiState,
                    drafts = reviewDrafts,
                    structuredHeaders = structuredHeaders,
                    actionState = reviewActionState,
                    errorMessage = reviewErrorMessage,
                    draftReviewItems = reviewDraftReviewItems,
                    isPasswordProtected = isPasswordProtected,
                    passwordError = passwordError,
                    unlockedPassword = unlockedPassword,
                    onUnlockPdf = reviewViewModel::unlockPdf,
                    onBack = { navController.popBackStack() },
                    onSkip = { navController.popBackStack() },
                    onProcessWithAi = reviewViewModel::onProcessWithAi,
                    onProcessNextAiBatch = reviewViewModel::onProcessNextAiBatch,
                    onAutoProcessRemainingAi = reviewViewModel::onAutoProcessRemainingAi,
                    onCancelAiProcessing = reviewViewModel::onCancelAiProcessing,
                    batchBannerMessage = reviewBatchBannerMessage,
                    onRetryLocalExtraction = reviewViewModel::retryLocalExtraction,
                    onProcessMappedData = reviewViewModel::processMappedData,
                    onReloadHeaders = reviewViewModel::loadHeadersForHeaderRow,
                    onSaveDraft = { id, a, t, q, p, amt -> reviewViewModel.saveDraft(id, a, t, q, p, amt) },
                    onSaveDraftWithClass = { id, a, t, q, p, amt, cls -> reviewViewModel.saveDraft(id, a, t, q, p, amt, cls) },
                    onSaveDraftWithDetails = { id, a, t, q, p, amt, cls, brk ->
                        reviewViewModel.saveDraft(id, a, t, q, p, amt, cls, brk)
                    },
                    onSaveConfirmed = { reviewViewModel.saveConfirmed() },
                    onSaveComplete = { navController.popBackStack() },
                    onNavigateToInbox = { navController.popBackStack() },
                    onNavigateToSettings = { navController.navigate(AppDestination.Settings.route) },
                    onDismissMissingApiKey = reviewViewModel::dismissMissingApiKeyDialog,
                    onDismissError = reviewViewModel::clearErrorMessage,
                    onToggleDraftSelection = reviewViewModel::toggleDraftSelection,
                    onSkipAllDuplicates = reviewViewModel::skipAllDuplicates,
                    onSelectAllDrafts = reviewViewModel::selectAllDrafts,
                    onConfirmSkipDuplicatesAndSave = reviewViewModel::confirmSkipDuplicatesAndSave,
                    onConfirmSaveAllAnyway = reviewViewModel::confirmSaveAllAnyway,
                    onDismissDuplicatePrompt = reviewViewModel::dismissDuplicatePrompt,
                    onUpdateAllAssetClass = reviewViewModel::updateAllDraftsAssetClass,
                    extractedTables = extractedTables,
                    showTableReview = showTableReview,
                    onExtractTablesLocally = reviewViewModel::extractTablesLocally,
                    onDismissTableReview = reviewViewModel::dismissTableReview,
                    onApproveExtractedTable = reviewViewModel::approveExtractedTable,
                )
            }
            composable(AppDestination.Settings.route) {
                SettingsScreen(viewModel = settingsViewModel)
            }
        }
                }
            }
        }
    }
}
