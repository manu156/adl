package com.adl.domain

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DownloadLogRepository @Inject constructor() {
    private val _logs = MutableStateFlow<Map<Long, List<String>>>(emptyMap())
    val logs: StateFlow<Map<Long, List<String>>> = _logs.asStateFlow()

    fun addLog(downloadId: Long, message: String) {
        _logs.update { current ->
            val list = current[downloadId] ?: emptyList()
            val updated = (list + message).takeLast(1000)
            current + (downloadId to updated)
        }
    }

    fun getLogs(downloadId: Long): List<String> {
        return _logs.value[downloadId] ?: emptyList()
    }

    fun clearLogs(downloadId: Long) {
        _logs.update { it - downloadId }
    }
}
