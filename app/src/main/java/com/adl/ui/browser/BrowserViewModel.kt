package com.adl.ui.browser

import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adl.data.db.DownloadEntity
import com.adl.data.db.DownloadStatus
import com.adl.data.repository.DownloadRepository
import com.adl.domain.BrowserNavigationManager
import com.adl.domain.CookieExporter
import com.adl.domain.HistoryItem
import com.adl.domain.SettingsRepository
import com.adl.domain.SiteDetector
import com.adl.service.DownloadService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class BrowserUiState(
    val currentUrl: String = "https://www.google.com",
    val displayUrl: String = "",
    val isLoading: Boolean = false,
    val isSupportedSite: Boolean = false,
    val siteName: String = "Gallery",
    val showDownloadSheet: Boolean = false,
    val showHistorySheet: Boolean = false,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val pageTitle: String = "",
    val downloadStarted: Boolean = false,
)

@HiltViewModel
class BrowserViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val siteDetector: SiteDetector,
    private val cookieExporter: CookieExporter,
    private val repository: DownloadRepository,
    private val settingsRepository: SettingsRepository,
    private val browserNavigationManager: BrowserNavigationManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow(BrowserUiState())
    val uiState: StateFlow<BrowserUiState> = _uiState.asStateFlow()

    val recentUrls: StateFlow<List<HistoryItem>> = settingsRepository.recentUrls
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private var retainedWebView: WebView? = null
    var savedState: Bundle? = null
        private set

    private val TAG = "BrowserViewModel"

    init {
        viewModelScope.launch {
            val homepage = settingsRepository.homepage.first()
            _uiState.update {
                if (it.displayUrl.isBlank()) {
                    it.copy(currentUrl = homepage, displayUrl = homepage)
                } else {
                    it
                }
            }
        }

        // Listen for external URL navigation requests (e.g. from Downloads screen)
        viewModelScope.launch {
            browserNavigationManager.targetUrl.collect { targetUrl ->
                if (targetUrl.isNotBlank()) {
                    navigate(targetUrl)
                    browserNavigationManager.consumeUrl()
                }
            }
        }
    }

    fun getOrCreateWebView(ctx: Context): WebView {
        if (retainedWebView == null) {
            retainedWebView = WebView(ctx.applicationContext).apply {
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    loadWithOverviewMode = true
                    useWideViewPort = true
                    setSupportZoom(true)
                    builtInZoomControls = true
                    displayZoomControls = false
                    userAgentString = settings.userAgentString
                }
                webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                        this@BrowserViewModel.onPageStarted()
                        url?.let { this@BrowserViewModel.onUrlChanged(it) }
                    }

                    override fun onPageFinished(view: WebView, url: String?) {
                        val finalUrl = url ?: return
                        val title = view.title ?: ""
                        this@BrowserViewModel.onPageLoaded(
                            url = finalUrl,
                            title = title,
                            canBack = canGoBack(),
                            canForward = canGoForward(),
                        )
                    }

                    override fun shouldOverrideUrlLoading(
                        view: WebView,
                        request: WebResourceRequest,
                    ): Boolean {
                        val newUrl = request.url.toString()
                        this@BrowserViewModel.onUrlChanged(newUrl)
                        return false
                    }
                }

                val bundle = savedState
                if (bundle != null && !bundle.isEmpty) {
                    restoreState(bundle)
                } else {
                    loadUrl(_uiState.value.currentUrl)
                }
            }
        }
        return retainedWebView!!
    }

    fun saveWebViewState() {
        val bundle = Bundle()
        retainedWebView?.saveState(bundle)
        savedState = bundle
    }

    /** Called when the user types a URL or search term and hits enter. */
    fun navigate(input: String) {
        val url = normalizeInput(input)
        savedState = null
        _uiState.update { it.copy(currentUrl = url, displayUrl = url, isLoading = true, isSupportedSite = false) }
        retainedWebView?.loadUrl(url)
        viewModelScope.launch {
            if (url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)) {
                settingsRepository.addRecentUrl(url)
            }
        }
    }

    fun goBack() {
        if (retainedWebView?.canGoBack() == true) {
            retainedWebView?.goBack()
        }
    }

    fun goForward() {
        if (retainedWebView?.canGoForward() == true) {
            retainedWebView?.goForward()
        }
    }

    fun reload() {
        retainedWebView?.reload()
    }

    /** Called by WebView when URL changes (redirect, link click, etc.). */
    fun onUrlChanged(url: String) {
        _uiState.update { it.copy(currentUrl = url, displayUrl = url) }
        viewModelScope.launch(Dispatchers.IO) {
            val supported = siteDetector.isSupported(url)
            val name = if (supported) siteDetector.getGalleryName(url) else "Gallery"
            _uiState.update { it.copy(isSupportedSite = supported, siteName = name) }
        }
    }

    /** Called by WebView when a page finishes loading. */
    fun onPageLoaded(url: String, title: String, canBack: Boolean, canForward: Boolean) {
        _uiState.update {
            it.copy(
                currentUrl = url,
                displayUrl = url,
                isLoading = false,
                pageTitle = title,
                canGoBack = canBack,
                canGoForward = canForward,
            )
        }
        viewModelScope.launch {
            if (url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)) {
                settingsRepository.addRecentUrl(url, title)
            }
        }
    }

    fun onPageStarted() {
        _uiState.update { it.copy(isLoading = true, isSupportedSite = false) }
    }

    fun showDownloadSheet() {
        _uiState.update { it.copy(showDownloadSheet = true) }
    }

    fun dismissDownloadSheet() {
        _uiState.update { it.copy(showDownloadSheet = false) }
    }

    fun showHistorySheet() {
        _uiState.update { it.copy(showHistorySheet = true) }
    }

    fun dismissHistorySheet() {
        _uiState.update { it.copy(showHistorySheet = false) }
    }

    fun clearRecentUrls() {
        viewModelScope.launch {
            settingsRepository.clearRecentUrls()
        }
    }

    fun removeRecentUrl(url: String) {
        viewModelScope.launch {
            settingsRepository.removeRecentUrl(url)
        }
    }

    /** Starts the download for the current page URL. */
    fun startDownload() {
        val url = _uiState.value.displayUrl
        if (url.isBlank()) return
        viewModelScope.launch {
            try {
                val outputDir = context.getExternalFilesDir(null)!!.absolutePath
                val entity = DownloadEntity(
                    url = url,
                    galleryName = _uiState.value.pageTitle.ifBlank { url },
                    siteName = _uiState.value.siteName,
                    outputDir = outputDir,
                    status = DownloadStatus.PENDING,
                )
                val downloadId = repository.createDownload(entity)
                // Start the foreground service
                val intent = DownloadService.startIntent(context, url, downloadId)
                context.startForegroundService(intent)
                _uiState.update { it.copy(showDownloadSheet = false, downloadStarted = true) }
                kotlinx.coroutines.delay(2000)
                _uiState.update { it.copy(downloadStarted = false) }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start download", e)
            }
        }
    }

    private fun normalizeInput(input: String): String {
        val trimmed = input.trim()
        return when {
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
            trimmed.contains(".") && !trimmed.contains(" ") -> "https://$trimmed"
            else -> "https://www.google.com/search?q=${trimmed.replace(" ", "+")}"
        }
    }

    override fun onCleared() {
        super.onCleared()
        retainedWebView?.destroy()
        retainedWebView = null
    }
}
