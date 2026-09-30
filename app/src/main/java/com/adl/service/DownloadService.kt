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
import com.adl.data.db.DownloadEntity
import com.adl.data.db.DownloadStatus
import com.adl.data.repository.DownloadRepository
import com.adl.domain.CookieExporter
import com.adl.domain.DownloadLogRepository
import com.adl.domain.PriorityDownloadQueue
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
import javax.inject.Inject

@AndroidEntryPoint
class DownloadService : Service() {

    @Inject lateinit var repository: DownloadRepository
    @Inject lateinit var cookieExporter: CookieExporter
    @Inject lateinit var priorityQueue: PriorityDownloadQueue
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var downloadLogRepository: DownloadLogRepository

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val py by lazy { Python.getInstance() }
    private val wrapper by lazy { py.getModule("gallery_wrapper") }
    private val notificationManager by lazy {
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    companion object {
        const val ACTION_START = "com.adl.ACTION_START_DOWNLOAD"
        const val ACTION_PAUSE = "com.adl.ACTION_PAUSE_DOWNLOAD"
        const val ACTION_CANCEL = "com.adl.ACTION_CANCEL_DOWNLOAD"
        const val EXTRA_URL = "extra_url"
        const val EXTRA_DOWNLOAD_ID = "extra_download_id"
        const val CHANNEL_ID = "adl_downloads"
        const val NOTIF_ID_BASE = 1000
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
                serviceScope.launch { startDownload(url, downloadId) }
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
        return START_REDELIVER_INTENT
    }

    private suspend fun startDownload(url: String, downloadId: Long) {
        val notifId = NOTIF_ID_BASE + downloadId.toInt()
        startForeground(notifId, buildNotification(downloadId, url, 0, 0))

        downloadLogRepository.addLog(downloadId, "[INFO] Service starting download for: $url")

        // Update DB: mark as IN_PROGRESS
        repository.updateProgress(downloadId, DownloadStatus.IN_PROGRESS, 0)

        val outputDir = getExternalFilesDir(null)!!.absolutePath
        downloadLogRepository.addLog(downloadId, "[INFO] Storage directory: $outputDir")

        val cookiesPath = withContext(Dispatchers.Main) {
            cookieExporter.exportForUrl(url)?.absolutePath ?: ""
        }
        downloadLogRepository.addLog(
            downloadId,
            "[INFO] Cookies: ${if (cookiesPath.isNotBlank()) "Loaded ($cookiesPath)" else "None (anonymous session)"}"
        )

        // Read user config without blocking indefinitely
        val configJson = settingsRepository.galleryDlConfig.firstOrNull() ?: ""
        val threads = settingsRepository.downloadThreads.firstOrNull() ?: 3
        val sleepStr = settingsRepository.sleepInterval.firstOrNull() ?: "0.5"
        val sleepSec = sleepStr.toDoubleOrNull() ?: 0.5

        // Start the Python download
        downloadLogRepository.addLog(downloadId, "[INFO] Launching gallery-dl engine (threads: $threads, sleep: ${sleepSec}s)...")
        wrapper.callAttr(
            "start_download",
            url,
            outputDir,
            downloadId.toString(),
            cookiesPath,
            configJson,
            threads,
            sleepSec,
        )

        // Poll progress events from the Python queue
        var imageIndex = 0
        var isComplete = false
        var lastReportedTotal = 0
        while (!isComplete) {
            // Update priority if gallery is being viewed
            if (priorityQueue.isBeingViewed(downloadId)) {
                val indices = priorityQueue.getPriorityIndicesString(downloadId)
                wrapper.callAttr("set_priority_indices", url, indices)
            }

            val eventJson = wrapper.callAttr("poll_event", 0.2)?.toString()
                ?: continue

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

                        downloadLogRepository.addLog(downloadId, "[INFO] Saved image #$imageIndex: ${filepath.substringAfterLast('/')}")
                        repository.updateProgress(downloadId, DownloadStatus.IN_PROGRESS, imageIndex)
                        if (filepath.isNotBlank()) {
                            repository.saveDownloadedImage(downloadId, imageIndex - 1, filepath)
                            // Use first image as thumbnail
                            if (imageIndex == 1) repository.setThumbnail(downloadId, filepath)
                        }
                        updateNotification(notifId, downloadId, url, imageIndex, -1)
                    }
                    "complete" -> {
                        val total = event.optInt("total_images", imageIndex)
                        downloadLogRepository.addLog(downloadId, "[SUCCESS] Download complete. Total images: $total")
                        repository.updateProgress(downloadId, DownloadStatus.COMPLETED, total)
                        repository.setTotalImages(downloadId, total)
                        repository.syncDiskImages(downloadId, outputDir)
                        showCompletedNotification(notifId, downloadId, url, total)
                        isComplete = true
                    }
                    "error" -> {
                        val msg = event.optString("message", "Unknown error")
                        Log.e(TAG, "Download error for $url: $msg")
                        downloadLogRepository.addLog(downloadId, "[ERROR] $msg")
                        repository.updateProgress(downloadId, DownloadStatus.FAILED, imageIndex)
                        showErrorNotification(notifId, url, msg)
                        isComplete = true
                    }
                    "cancelled" -> {
                        downloadLogRepository.addLog(downloadId, "[WARN] Download paused / stopped")
                        // Mark as PAUSED
                        repository.updateStatus(downloadId, DownloadStatus.PAUSED)
                        isComplete = true
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error parsing event: ${e.message}")
            }
        }

        stopForeground(STOP_FOREGROUND_DETACH)
        stopSelf()
    }

    private suspend fun pauseDownload(downloadId: Long) {
        wrapper.callAttr("cancel_download", downloadId.toString())
        repository.updateStatus(downloadId, DownloadStatus.PAUSED)
        downloadLogRepository.addLog(downloadId, "[WARN] Download paused by user")
        val notifId = NOTIF_ID_BASE + downloadId.toInt()
        notificationManager.cancel(notifId)
    }

    private suspend fun cancelDownload(url: String, downloadId: Long) {
        if (downloadId >= 0) {
            wrapper.callAttr("cancel_download", downloadId.toString())
            repository.updateStatus(downloadId, DownloadStatus.CANCELLED)
            downloadLogRepository.addLog(downloadId, "[WARN] Download cancelled by user")
            val notifId = NOTIF_ID_BASE + downloadId.toInt()
            notificationManager.cancel(notifId)
        }
        if (url.isNotBlank()) {
            wrapper.callAttr("cancel_download", url)
        }
    }

    // ─ Notifications ─────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "ADL Downloads",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Gallery download progress"
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun buildNotification(
        downloadId: Long,
        url: String,
        progress: Int,
        max: Int,
    ) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.stat_sys_download)
        .setContentTitle("Downloading gallery")
        .setContentText(url.take(60))
        .setProgress(max, progress, max == 0)
        .setOngoing(true)
        .setContentIntent(
            PendingIntent.getActivity(
                this, 0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE,
            )
        )
        .build()

    private fun updateNotification(notifId: Int, downloadId: Long, url: String, done: Int, total: Int) {
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Downloading gallery")
            .setContentText("${done} images downloaded")
            .setProgress(if (total > 0) total else 0, done, total <= 0)
            .setOngoing(true)
            .build()
        notificationManager.notify(notifId, notif)
    }

    private fun showCompletedNotification(notifId: Int, downloadId: Long, url: String, total: Int) {
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Download complete")
            .setContentText("$total images saved")
            .setAutoCancel(true)
            .build()
        notificationManager.notify(notifId, notif)
    }

    private fun showErrorNotification(notifId: Int, url: String, message: String) {
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("Download failed")
            .setContentText(message.take(80))
            .setAutoCancel(true)
            .build()
        notificationManager.notify(notifId, notif)
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
