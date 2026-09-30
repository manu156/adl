package com.adl.domain

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Facilitates navigating to a specific URL in the Browser tab from other parts of the app
 * (such as Downloads, Gallery, or notifications).
 */
@Singleton
class BrowserNavigationManager @Inject constructor() {

    private val _targetUrl = MutableSharedFlow<String>(replay = 1, extraBufferCapacity = 1)
    val targetUrl: SharedFlow<String> = _targetUrl.asSharedFlow()

    fun openUrl(url: String) {
        val trimmed = url.trim()
        if (trimmed.isNotBlank()) {
            _targetUrl.tryEmit(trimmed)
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun consumeUrl() {
        _targetUrl.resetReplayCache()
    }
}
