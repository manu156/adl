package com.adl.domain

import com.chaquo.python.Python
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tracks which image indices are currently visible in the gallery detail view or full-screen viewer,
 * so DownloadService can prioritize downloading those images first (e.g. current image, 5 forward, 1 back).
 *
 * Updates are broadcast immediately to the Python gallery-dl wrapper for zero-latency priority switching.
 */
@Singleton
class PriorityDownloadQueue @Inject constructor(
    private val scope: CoroutineScope,
) {
    // Maps downloadId → ordered list of high-priority image indices
    private val _priorities = MutableStateFlow<Map<Long, List<Int>>>(emptyMap())
    val priorities: StateFlow<Map<Long, List<Int>>> = _priorities.asStateFlow()

    /**
     * Update the ordered list of priority indices for a given download.
     * Order matters: earlier indices are downloaded before later indices.
     */
    fun setPriorityImages(downloadId: Long, priorityIndices: Collection<Int>) {
        val orderedList = priorityIndices.distinct()
        _priorities.update { current ->
            current + (downloadId to orderedList)
        }
        // Immediately notify Python runtime so active workers pick high-priority images with zero delay
        scope.launch(Dispatchers.IO) {
            try {
                if (Python.isStarted()) {
                    val py = Python.getInstance()
                    val wrapper = py.getModule("gallery_wrapper")
                    val csv = orderedList.joinToString(",")
                    wrapper.callAttr("set_priority_indices", downloadId.toString(), csv)
                }
            } catch (e: Exception) {
                // Ignore if Python isn't running
            }
        }
    }

    /**
     * Remove priority tracking for a download (when gallery detail/viewer is closed).
     */
    fun clearPriority(downloadId: Long) {
        _priorities.update { current ->
            current - downloadId
        }
        scope.launch(Dispatchers.IO) {
            try {
                if (Python.isStarted()) {
                    val py = Python.getInstance()
                    val wrapper = py.getModule("gallery_wrapper")
                    wrapper.callAttr("set_priority_indices", downloadId.toString(), "")
                }
            } catch (e: Exception) {
            }
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
