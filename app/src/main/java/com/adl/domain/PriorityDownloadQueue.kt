package com.adl.domain

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tracks which image indices are currently visible in the gallery detail view,
 * so DownloadService can prioritize downloading those images first.
 *
 * The Python wrapper reads priority indices via Chaquopy calls from DownloadService.
 */
@Singleton
class PriorityDownloadQueue @Inject constructor(
    private val scope: CoroutineScope,
) {
    // Maps downloadId → set of high-priority image indices (currently visible in viewport)
    private val _priorities = MutableStateFlow<Map<Long, Set<Int>>>(emptyMap())
    val priorities: StateFlow<Map<Long, Set<Int>>> = _priorities.asStateFlow()

    /**
     * Update the set of priority indices for a given download.
     * Called from GalleryDetailScreen when scroll position changes.
     */
    fun setPriorityImages(downloadId: Long, visibleIndices: Set<Int>) {
        _priorities.update { current ->
            current + (downloadId to visibleIndices)
        }
    }

    /**
     * Remove priority tracking for a download (when gallery detail is closed).
     */
    fun clearPriority(downloadId: Long) {
        _priorities.update { current ->
            current - downloadId
        }
    }

    /**
     * Get comma-separated priority indices string for Python,
     * or empty string if no priority set.
     */
    fun getPriorityIndicesString(downloadId: Long): String {
        val indices = _priorities.value[downloadId] ?: return ""
        return indices.joinToString(",")
    }

    /** Returns true if the gallery for this download is currently being viewed. */
    fun isBeingViewed(downloadId: Long): Boolean {
        return _priorities.value.containsKey(downloadId)
    }
}
