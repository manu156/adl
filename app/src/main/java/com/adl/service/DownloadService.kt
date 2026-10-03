package com.adl.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.adl.MainActivity
import com.adl.data.db.DownloadStatus
import com.adl.data.repository.DownloadRepository
import com.adl.domain.CookieExporter
import com.adl.domain.DownloadLogRepository
import com.adl.domain.SettingsRepository
import com.chaquo.python.Python
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.ArrayDeque
import javax.inject.Inject

@AndroidEntryPoint
class DownloadService : Service() {

    @Inject lateinit var repository: DownloadRepository
    @Inject lateinit var cookieExporter: CookieExporter
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var downloadLogRepository: DownloadLogRepository

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val py by lazy { Python.getInstance() }
    private val wrapper by lazy { py.getModule("gallery_wrapper") }
    private val notificationManager by lazy {
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    private data class DownloadTask(val url: String, val downloadId: Long)

    private val queueLock = Any()
    private val downloadQueue = ArrayDeque<DownloadTask>()
    @Volatile private var currentRunningDownloadId: Long? = null
    private var isProcessing = false

    companion object {
        const val ACTION_START = "com.adl.ACTION_START_DOWNLOAD"
        const val ACTION_PAUSE = "com.adl.ACTION_PAUSE_DOWNLOAD"
        const val ACTION_CANCEL = "com.adl.ACTION_CANCEL_DOWNLOAD"
        const val EXTRA_URL = "extra_url"
        const val EXTRA_DOWNLOAD_ID = "extra_download_id"
        const val CHANNEL_ID = "adl_downloads"
        const val NOTIF_FOREGROUND_ID = 1000
        private const val TAG = "DownloadService"

        fun startIntent(context: Context, url: String, downloadId: Long): Intent =
            Intent(context, DownloadService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_DOWNLOAD_ID, downloadId)
            }

        fun pauseIntent(context: Context, downloadId: Long): Intent =
            Intent(context, DownloadService::class.java).apply {
                action = ACTION_PAUSE
                putExtra(EXTRA_DOWNLOAD_ID, downloadId)
            }

        fun cancelIntent(context: Context, downloadId: Long, url: String = ""): Intent =
            Intent(context, DownloadService::class.java).apply {
                action = ACTION_CANCEL
                putExtra(EXTRA_DOWNLOAD_ID, downloadId)
                putExtra(EXTRA_URL, url)
            }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val url = intent.getStringExtra(EXTRA_URL) ?: return START_NOT_STICKY
                val downloadId = intent.getLongExtra(EXTRA_DOWNLOAD_ID, -1L)
                if (downloadId < 0) return START_NOT_STICKY

                // Immediately start foreground notification to satisfy Android 8+ requirement
                startForeground(NOTIF_FOREGROUND_ID, buildForegroundNotification("Queued download...", url))

                enqueueDownload(url, downloadId)
            }
            ACTION_PAUSE -> {
                val downloadId = intent.getLongExtra(EXTRA_DOWNLOAD_ID, -1L)
                if (downloadId >= 0) {
                    serviceScope.launch { pauseDownload(downloadId) }
                }
            }
            ACTION_CANCEL -> {
                val downloadId = intent.getLongExtra(EXTRA_DOWNLOAD_ID, -1L)
                val url = intent.getStringExtra(EXTRA_URL) ?: ""
                serviceScope.launch { cancelDownload(url, downloadId) }
            }
        }
        // Do NOT redeliver old intents on process death — state is tracked in Room DB
        return START_NOT_STICKY
    }

    private fun enqueueDownload(url: String, downloadId: Long) {
        synchronized(queueLock) {
            if (currentRunningDownloadId == downloadId || downloadQueue.any { it.downloadId == downloadId }) {
                Log.d(TAG, "Download $downloadId is already running or queued")
                return
            }
            downloadQueue.add(DownloadTask(url, downloadId))
            downloadLogRepository.addLog(downloadId, "[INFO] Download added to queue (position: ${downloadQueue.size})")

            if (!isProcessing) {
                isProcessing = true
                serviceScope.launch { processQueue() }
            }
        }
    }

    private suspend fun processQueue() {
        while (true) {
            val nextTask = synchronized(queueLock) {
                if (downloadQueue.isEmpty()) {
                    isProcessing = false
                    currentRunningDownloadId = null
                    return@synchronized null
                }
                downloadQueue.removeFirst()
            } ?: break

            currentRunningDownloadId = nextTask.downloadId
            try {
                executeDownload(nextTask.url, nextTask.downloadId)
            } catch (e: Exception) {
                Log.e(TAG, "Error executing download ${nextTask.downloadId}", e)
                downloadLogRepository.addLog(nextTask.downloadId, "[ERROR] Download failed: ${e.message}")
                repository.updateStatus(nextTask.downloadId, DownloadStatus.FAILED)
            } finally {
                currentRunningDownloadId = null
            }
        }

        // All downloads completed; remove foreground notification and shut down service
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private suspend fun executeDownload(url: String, downloadId: Long) {
        val downloadEntity = repository.observeById(downloadId).firstOrNull()
        val title = downloadEntity?.galleryName?.ifBlank { url } ?: url

        updateForegroundNotification(title, "Starting download...", 0, 0)
        downloadLogRepository.addLog(downloadId, "[INFO] Starting download: $url")

        // Update DB: mark as IN_PROGRESS
        repository.updateStatus(downloadId, DownloadStatus.IN_PROGRESS)

        val outputDir = getExternalFilesDir(null)!!.absolutePath
        val cookiesPath = withContext(Dispatchers.Main) {
            cookieExporter.exportForUrl(url)?.absolutePath ?: ""
        }

        val configJson = settingsRepository.galleryDlConfig.firstOrNull() ?: ""
        val sleepStr = settingsRepository.sleepInterval.firstOrNull() ?: "0.5"
        val sleepSec = sleepStr.toDoubleOrNull() ?: 0.5

        // Reset state for this download
        wrapper.callAttr("reset_state", downloadId.toString(), url)

        // Launch single-threaded sequential gallery-dl download
        wrapper.callAttr(
            "start_download",
            url,
            outputDir,
            downloadId.toString(),
            cookiesPath,
            configJson,
            1,
            sleepSec,
        )

        var imageIndex = 0
        var isComplete = false
        var lastReportedTotal = downloadEntity?.totalImages ?: 0
        val pollDeadlineMs = 5 * 60 * 1000L
        var lastEventMs = System.currentTimeMillis()

        while (!isComplete) {
            val eventJson = wrapper.callAttr("poll_event", 0.2)?.toString()
            if (eventJson == null) {
                if (System.currentTimeMillis() - lastEventMs > pollDeadlineMs) {
                    val msg = "Download timed out — no events for 5 minutes"
                    Log.e(TAG, msg)
                    downloadLogRepository.addLog(downloadId, "[ERROR] $msg")
                    repository.updateStatus(downloadId, DownloadStatus.FAILED)
                    showCompletionNotification(downloadId, title, false, "Timed out after 5 minutes")
                    isComplete = true
                }
                continue
            }
            lastEventMs = System.currentTimeMillis()

            try {
                val event = JSONObject(eventJson)
                val evtDownloadId = event.optString("download_id")
                if (evtDownloadId != downloadId.toString()) continue

                when (event.getString("type")) {
                    "log" -> {
                        val msg = event.optString("message", "")
                        val level = event.optString("level", "INFO")
                        if (msg.isNotBlank()) {
                            downloadLogRepository.addLog(downloadId, "[$level] $msg")
                        }
                    }
                    "total" -> {
                        val total = event.optInt("total", 0)
                        if (total > 0 && total != lastReportedTotal) {
                            lastReportedTotal = total
                            repository.setTotalImages(downloadId, total)
                        }
                    }
                    "image" -> {
                        imageIndex = event.getInt("image_index")
                        val filepath = event.optString("filepath", "")

                        if (imageIndex > lastReportedTotal && lastReportedTotal > 0) {
                            lastReportedTotal = imageIndex
                            repository.setTotalImages(downloadId, imageIndex)
                        }

                        if (filepath.isNotBlank()) {
                            downloadLogRepository.addLog(downloadId, "[INFO] Saved image #$imageIndex: ${filepath.substringAfterLast('/')}")
                            repository.saveDownloadedImage(downloadId, imageIndex - 1, filepath)
                            // Set thumbnail from first valid image
                            if (imageIndex == 1 || downloadEntity?.thumbnailPath.isNullOrBlank()) {
                                repository.setThumbnail(downloadId, filepath)
                            }
                        }
                        repository.updateProgress(downloadId, DownloadStatus.IN_PROGRESS, imageIndex)

                        val statusText = if (lastReportedTotal > 0) "$imageIndex / $lastReportedTotal images" else "$imageIndex images"
                        updateForegroundNotification(title, statusText, imageIndex, lastReportedTotal)
                    }
                    "complete" -> {
                        val total = event.optInt("total_images", imageIndex)
                        downloadLogRepository.addLog(downloadId, "[SUCCESS] Download complete. Total images: $total")
                        repository.updateProgress(downloadId, DownloadStatus.COMPLETED, total)
                        repository.setTotalImages(downloadId, total)
                        showCompletionNotification(downloadId, title, true, "$total images saved")
                        isComplete = true
                    }
                    "error" -> {
                        val msg = event.optString("message", "Unknown error")
                        Log.e(TAG, "Download error for $url: $msg")
                        downloadLogRepository.addLog(downloadId, "[ERROR] $msg")
                        repository.updateProgress(downloadId, DownloadStatus.FAILED, imageIndex)
                        showCompletionNotification(downloadId, title, false, msg)
                        isComplete = true
                    }
                    "cancelled" -> {
                        downloadLogRepository.addLog(downloadId, "[WARN] Download paused / stopped")
                        repository.updateStatus(downloadId, DownloadStatus.PAUSED)
                        isComplete = true
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error parsing event: ${e.message}")
            }
        }
    }

    private suspend fun pauseDownload(downloadId: Long) {
        synchronized(queueLock) {
            downloadQueue.removeAll { it.downloadId == downloadId }
        }
        if (currentRunningDownloadId == downloadId) {
            wrapper.callAttr("cancel_download", downloadId.toString())
        }
        repository.updateStatus(downloadId, DownloadStatus.PAUSED)
        downloadLogRepository.addLog(downloadId, "[WARN] Download paused by user")
    }

    private suspend fun cancelDownload(url: String, downloadId: Long) {
        synchronized(queueLock) {
            downloadQueue.removeAll { it.downloadId == downloadId }
        }
        if (currentRunningDownloadId == downloadId) {
            wrapper.callAttr("cancel_download", downloadId.toString())
        } else if (url.isNotBlank()) {
            wrapper.callAttr("cancel_download", url)
        }
        if (downloadId >= 0) {
            repository.updateStatus(downloadId, DownloadStatus.CANCELLED)
            downloadLogRepository.addLog(downloadId, "[WARN] Download cancelled by user")
        }
    }

    // ─ Notifications ─────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "ADL Downloads",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Gallery download progress and status"
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun buildForegroundNotification(title: String, text: String): android.app.Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE,
                )
            )
            .build()

    private fun updateForegroundNotification(title: String, text: String, progress: Int, max: Int) {
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setProgress(if (max > 0) max else 0, progress, max <= 0)
            .setOngoing(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE,
                )
            )
            .build()
        notificationManager.notify(NOTIF_FOREGROUND_ID, notif)
    }

    private fun showCompletionNotification(downloadId: Long, title: String, success: Boolean, message: String) {
        val notifId = (20000 + downloadId % 10000).toInt()
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(if (success) android.R.drawable.stat_sys_download_done else android.R.drawable.stat_notify_error)
            .setContentTitle(if (success) "Downloaded: $title" else "Download failed: $title")
            .setContentText(message)
            .setAutoCancel(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE,
                )
            )
            .build()
        notificationManager.notify(notifId, notif)
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
