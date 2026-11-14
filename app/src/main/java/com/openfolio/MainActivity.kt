package com.openfolio

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import com.openfolio.core.ui.theme.OpenFolioTheme
import com.openfolio.feature.navigation.OpenFolioApp
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            OpenFolioTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    OpenFolioApp()
                }
            }
        }
    }
}
