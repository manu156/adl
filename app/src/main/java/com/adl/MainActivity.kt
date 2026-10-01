package com.adl

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.adl.data.repository.DownloadRepository
import com.adl.domain.BrowserNavigationManager
import com.adl.ui.AdlApp
import com.adl.ui.theme.AdlTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var browserNavigationManager: BrowserNavigationManager

    @Inject
    lateinit var downloadRepository: DownloadRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // On every start, move any IN_PROGRESS downloads (left over from a killed service)
        // to PAUSED so the user can resume them. Never touches COMPLETED downloads.
        CoroutineScope(Dispatchers.IO).launch {
            downloadRepository.fixStuckDownloads()
        }

        setContent {
            AdlTheme {
                AdlApp(browserNavigationManager = browserNavigationManager)
            }
        }
    }
}
