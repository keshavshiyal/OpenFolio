package com.openfolio

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
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

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
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
            }
        }
    }
}
