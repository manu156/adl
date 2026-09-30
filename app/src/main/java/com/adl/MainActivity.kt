package com.adl

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.adl.domain.BrowserNavigationManager
import com.adl.ui.AdlApp
import com.adl.ui.theme.AdlTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var browserNavigationManager: BrowserNavigationManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AdlTheme {
                AdlApp(browserNavigationManager = browserNavigationManager)
            }
        }
    }
}
