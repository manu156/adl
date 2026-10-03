package com.adl.ui.downloads

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adl.data.db.DownloadEntity
import com.adl.data.db.DownloadStatus
import com.adl.data.repository.DownloadRepository
import com.adl.domain.DownloadLogRepository
import com.adl.service.DownloadService
import com.chaquo.python.Python
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

@HiltViewModel
class DownloadsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: DownloadRepository,
    private val logRepository: DownloadLogRepository,
) : ViewModel() {

    val activeDownloads: StateFlow<List<DownloadEntity>> =
        repository.observeActive()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val completedDownloads: StateFlow<List<DownloadEntity>> =
        repository.observeCompleted()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val downloadLogs: StateFlow<Map<Long, List<String>>> = logRepository.logs

    fun getLogs(downloadId: Long): List<String> = logRepository.getLogs(downloadId)

    suspend fun getCombinedLogs(downloadId: Long): String = withContext(Dispatchers.IO) {
        val memoryLogs = logRepository.getLogs(downloadId).joinToString("\n")
        val pythonLogs = try {
            val py = Python.getInstance()
            val wrapper = py.getModule("gallery_wrapper")
            wrapper.callAttr("get_logs", downloadId.toString()).toString()
        } catch (e: Exception) {
            ""
        }
        when {
            memoryLogs.isNotBlank() && pythonLogs.isNotBlank() -> {
                if (pythonLogs.contains(memoryLogs.take(100))) pythonLogs else "$memoryLogs\n$pythonLogs"
            }
            memoryLogs.isNotBlank() -> memoryLogs
            pythonLogs.isNotBlank() -> pythonLogs
            else -> "No logs recorded yet. Waiting for download engine to start..."
        }
    }

    fun pauseDownload(download: DownloadEntity) {
        viewModelScope.launch {
            logRepository.addLog(download.id, "[INFO] Pausing download...")
            val intent = DownloadService.pauseIntent(context, download.id)
            context.startService(intent)
            repository.updateStatus(download.id, DownloadStatus.PAUSED)
        }
    }

    fun resumeDownload(download: DownloadEntity) {
        viewModelScope.launch {
            logRepository.addLog(download.id, "[INFO] Continuing download...")
            repository.updateStatus(download.id, DownloadStatus.PENDING)
            val intent = DownloadService.startIntent(context, download.url, download.id)
            context.startForegroundService(intent)
        }
    }

    fun deleteDownload(download: DownloadEntity, deleteFiles: Boolean = true) {
        viewModelScope.launch {
            // Cancel service if active
            val intent = DownloadService.cancelIntent(context, download.id, download.url)
            context.startService(intent)

            logRepository.clearLogs(download.id)
            repository.deleteDownload(download.id, deleteFiles = deleteFiles)
        }
    }
}
