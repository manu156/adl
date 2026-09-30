package com.adl.domain

import android.util.Log
import com.chaquo.python.Python
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SiteDetector @Inject constructor() {

    private val py by lazy { Python.getInstance() }
    private val module by lazy { py.getModule("site_detector") }

    /**
     * Returns true if gallery-dl has an extractor for the given URL.
     * This runs Python code synchronously — call from a background thread.
     */
    fun isSupported(url: String): Boolean {
        if (url.isBlank()) return false
        return try {
            module.callAttr("is_supported", url).toBoolean()
        } catch (e: Exception) {
            Log.w("SiteDetector", "Error checking URL support: ${e.message}")
            false
        }
    }

    /**
     * Returns a human-readable site name for display (e.g. "Pixiv", "Imgur").
     * Runs Python code synchronously — call from a background thread.
     */
    fun getGalleryName(url: String): String {
        return try {
            module.callAttr("get_gallery_name", url).toString()
        } catch (e: Exception) {
            Log.w("SiteDetector", "Error getting gallery name: ${e.message}")
            "Gallery"
        }
    }
}
