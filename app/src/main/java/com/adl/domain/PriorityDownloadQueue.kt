package com.adl.domain

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Priority queue is disabled — downloads are strictly sequential for reliability.
 * Retained as a no-op placeholder for dependency injection.
 */
@Singleton
class PriorityDownloadQueue @Inject constructor(
    scope: kotlinx.coroutines.CoroutineScope? = null,
) {
    fun setPriorityImages(downloadId: Long, priorityIndices: Collection<Int>) {
        // No-op: downloads are strictly sequential
    }

    fun clearPriority(downloadId: Long) {
        // No-op
    }

    fun getPriorityIndicesString(downloadId: Long): String = ""

    fun isBeingViewed(downloadId: Long): Boolean = false
}
