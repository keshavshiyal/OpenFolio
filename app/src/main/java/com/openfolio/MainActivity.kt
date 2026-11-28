package com.openfolio

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.openfolio.core.data.local.DatabasePassphraseProvider
import com.openfolio.core.data.local.DatabasePassphraseProvider.DatabaseKeyState
import com.openfolio.core.ui.theme.LocalOpenFolioColors
import com.openfolio.core.ui.theme.OpenFolioTheme
import com.openfolio.feature.activity.ActivityViewModel
import com.openfolio.feature.assets.AssetDetailViewModel
import com.openfolio.feature.assets.AssetsViewModel
import com.openfolio.feature.home.HomeViewModel
import com.openfolio.feature.inbox.InboxViewModel
import com.openfolio.feature.navigation.OpenFolioApp
import com.openfolio.feature.review.ReviewViewModel
import com.openfolio.feature.settings.SettingsViewModel
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var databasePassphraseProvider: DatabasePassphraseProvider

    private val homeViewModel: HomeViewModel by viewModels()
    private val assetsViewModel: AssetsViewModel by viewModels()
    private val assetDetailViewModel: AssetDetailViewModel by viewModels()
    private val activityViewModel: ActivityViewModel by viewModels()
    private val inboxViewModel: InboxViewModel by viewModels()
    private val reviewViewModel: ReviewViewModel by viewModels()
    private val settingsViewModel: SettingsViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            OpenFolioTheme(dynamicColor = false) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    var keyState by remember {
                        mutableStateOf(databasePassphraseProvider.resolveDatabaseKeyState())
                    }

                    when (val state = keyState) {
                        is DatabaseKeyState.Ready -> {
                            OpenFolioApp(
                                homeViewModel = homeViewModel,
                                assetsViewModel = assetsViewModel,
                                assetDetailViewModel = assetDetailViewModel,
                                activityViewModel = activityViewModel,
                                inboxViewModel = inboxViewModel,
                                reviewViewModel = reviewViewModel,
                                settingsViewModel = settingsViewModel,
                            )
                        }
                        is DatabaseKeyState.Corrupted -> {
                            DatabaseRecoveryScreen(
                                reason = state.reason,
                                onRetryUnlock = {
                                    val refreshed = databasePassphraseProvider.resolveDatabaseKeyState(forceRefresh = true)
                                    if (refreshed is DatabaseKeyState.Ready) {
                                        restartProcessCleanly()
                                    } else {
                                        keyState = refreshed
                                    }
                                },
                                onResetStorage = {
                                    if (databasePassphraseProvider.resetCorruptedDatabaseAndKey()) {
                                        restartProcessCleanly()
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    private fun restartProcessCleanly() {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        if (launchIntent != null) {
            startActivity(launchIntent)
        }
        finishAffinity()
        Runtime.getRuntime().exit(0)
    }
}

@Composable
private fun DatabaseRecoveryScreen(
    reason: String,
    onRetryUnlock: () -> Unit,
    onResetStorage: () -> Unit,
) {
    val colors = LocalOpenFolioColors.current
    var showConfirmReset by remember { mutableStateOf(false) }

    if (showConfirmReset) {
        AlertDialog(
            onDismissRequest = { showConfirmReset = false },
            containerColor = colors.surface,
            title = {
                Text(
                    text = "Reset Encrypted Database?",
                    style = MaterialTheme.typography.titleLarge,
                    color = colors.content,
                )
            },
            text = {
                Text(
                    text = "This will permanently delete the unreadable encrypted local database so OpenFolio can start fresh. You can restore your portfolio afterward from an .ofbackup archive in Settings.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.content,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showConfirmReset = false
                        onResetStorage()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.lossGlyph,
                        contentColor = colors.onAccent,
                    ),
                ) {
                    Text("Reset Database")
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmReset = false }) {
                    Text("Cancel", color = colors.muted)
                }
            },
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = colors.surface,
            border = BorderStroke(1.dp, colors.border),
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Warning,
                    contentDescription = null,
                    tint = colors.lossGlyph,
                    modifier = Modifier.size(32.dp),
                )
                Text(
                    text = "Encrypted Storage Locked",
                    style = MaterialTheme.typography.headlineSmall,
                    color = colors.content,
                )
                Text(
                    text = reason,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.content,
                )
                Text(
                    text = "OpenFolio preserved your encrypted database instead of overwriting it. Retry after unlocking your device, or reset storage to start fresh and restore from an encrypted backup.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.muted,
                )
                Spacer(Modifier.height(4.dp))
                Button(
                    onClick = onRetryUnlock,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.brand,
                        contentColor = colors.onAccent,
                    ),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text("Retry Unlock", style = MaterialTheme.typography.labelLarge)
                }
                OutlinedButton(
                    onClick = { showConfirmReset = true },
                    modifier = Modifier.fillMaxWidth(),
                    border = BorderStroke(1.dp, colors.lossGlyph),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.lossGlyph),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text("Reset Encrypted Database", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}
