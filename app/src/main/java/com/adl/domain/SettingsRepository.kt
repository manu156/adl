package com.adl.domain

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "adl_settings")

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    companion object {
        val KEY_DOWNLOAD_PATH = stringPreferencesKey("download_path")
        val KEY_CONCURRENT_DOWNLOADS = intPreferencesKey("concurrent_downloads")
        val KEY_DOWNLOAD_THREADS = intPreferencesKey("download_threads")
        val KEY_GALLERY_DL_CONFIG = stringPreferencesKey("gallery_dl_config")
        val KEY_FILENAME_TEMPLATE = stringPreferencesKey("filename_template")
        val KEY_SLEEP_INTERVAL = stringPreferencesKey("sleep_interval")
        val KEY_RETRIES = intPreferencesKey("retries")
        val KEY_HOMEPAGE = stringPreferencesKey("homepage")
        val KEY_GRID_COLUMNS = intPreferencesKey("grid_columns")
        val KEY_RECENT_URLS = stringPreferencesKey("recent_urls")

        const val DEFAULT_CONFIG = """
{
  "filename": "{filename}.{extension}",
  "sleep": 0.5,
  "retries": 4,
  "timeout": 30,
  "verify": true
}"""
    }

    val downloadPath: Flow<String> = context.dataStore.data
        .map { it[KEY_DOWNLOAD_PATH] ?: "" }

    val concurrentDownloads: Flow<Int> = context.dataStore.data
        .map { it[KEY_CONCURRENT_DOWNLOADS] ?: 2 }

    val downloadThreads: Flow<Int> = context.dataStore.data
        .map { it[KEY_DOWNLOAD_THREADS] ?: 3 }

    val galleryDlConfig: Flow<String> = context.dataStore.data
        .map { it[KEY_GALLERY_DL_CONFIG] ?: DEFAULT_CONFIG }

    val filenameTemplate: Flow<String> = context.dataStore.data
        .map { it[KEY_FILENAME_TEMPLATE] ?: "{filename}.{extension}" }

    val sleepInterval: Flow<String> = context.dataStore.data
        .map { it[KEY_SLEEP_INTERVAL] ?: "0.5" }

    val retries: Flow<Int> = context.dataStore.data
        .map { it[KEY_RETRIES] ?: 4 }

    val homepage: Flow<String> = context.dataStore.data
        .map { it[KEY_HOMEPAGE] ?: "https://www.google.com" }

    val gridColumns: Flow<Int> = context.dataStore.data
        .map { it[KEY_GRID_COLUMNS] ?: 3 }

    suspend fun setGalleryDlConfig(json: String) {
        context.dataStore.edit { it[KEY_GALLERY_DL_CONFIG] = json }
    }

    suspend fun setFilenameTemplate(template: String) {
        context.dataStore.edit { it[KEY_FILENAME_TEMPLATE] = template }
    }

    suspend fun setSleepInterval(value: String) {
        context.dataStore.edit { it[KEY_SLEEP_INTERVAL] = value }
    }

    suspend fun setRetries(value: Int) {
        context.dataStore.edit { it[KEY_RETRIES] = value }
    }

    suspend fun setHomepage(url: String) {
        context.dataStore.edit { it[KEY_HOMEPAGE] = url }
    }

    suspend fun setGridColumns(cols: Int) {
        context.dataStore.edit { it[KEY_GRID_COLUMNS] = cols }
    }

    suspend fun setDownloadThreads(threads: Int) {
        context.dataStore.edit { it[KEY_DOWNLOAD_THREADS] = threads.coerceIn(1, 8) }
    }

    suspend fun setConcurrentDownloads(count: Int) {
        context.dataStore.edit { it[KEY_CONCURRENT_DOWNLOADS] = count.coerceIn(1, 5) }
    }

    // ─ Recent URLs History (last 5 URLs) ─────────────────────────────

    val recentUrls: Flow<List<HistoryItem>> = context.dataStore.data
        .map { prefs -> parseHistory(prefs[KEY_RECENT_URLS] ?: "") }

    suspend fun addRecentUrl(url: String, title: String = "") {
        val trimmed = url.trim()
        if (trimmed.isBlank() || trimmed.startsWith("about:", ignoreCase = true)) return

        context.dataStore.edit { prefs ->
            val current = parseHistory(prefs[KEY_RECENT_URLS] ?: "").toMutableList()
            current.removeAll { it.url.equals(trimmed, ignoreCase = true) }
            val effectiveTitle = if (title.isNotBlank()) title.trim() else trimmed
            current.add(0, HistoryItem(url = trimmed, title = effectiveTitle, timestamp = System.currentTimeMillis()))
            val last5 = current.take(5)
            prefs[KEY_RECENT_URLS] = serializeHistory(last5)
        }
    }

    suspend fun removeRecentUrl(url: String) {
        val trimmed = url.trim()
        context.dataStore.edit { prefs ->
            val current = parseHistory(prefs[KEY_RECENT_URLS] ?: "").toMutableList()
            current.removeAll { it.url.equals(trimmed, ignoreCase = true) }
            prefs[KEY_RECENT_URLS] = serializeHistory(current)
        }
    }

    suspend fun clearRecentUrls() {
        context.dataStore.edit { prefs ->
            prefs.remove(KEY_RECENT_URLS)
        }
    }
}

data class HistoryItem(
    val url: String,
    val title: String = "",
    val timestamp: Long = System.currentTimeMillis(),
)

private fun parseHistory(json: String): List<HistoryItem> {
    if (json.isBlank()) return emptyList()
    return try {
        val array = org.json.JSONArray(json)
        val list = mutableListOf<HistoryItem>()
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            list.add(
                HistoryItem(
                    url = obj.getString("url"),
                    title = obj.optString("title", ""),
                    timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
                )
            )
        }
        list
    } catch (_: Exception) {
        emptyList()
    }
}

private fun serializeHistory(items: List<HistoryItem>): String {
    val array = org.json.JSONArray()
    for (item in items) {
        val obj = org.json.JSONObject()
        obj.put("url", item.url)
        obj.put("title", item.title)
        obj.put("timestamp", item.timestamp)
        array.put(obj)
    }
    return array.toString()
}
