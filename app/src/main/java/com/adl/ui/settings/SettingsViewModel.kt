package com.adl.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adl.domain.SettingsRepository
import com.chaquo.python.Python
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val configJson: String = "",
    val filenameTemplate: String = "{filename}.{extension}",
    val sleepInterval: String = "0.5",
    val downloadThreads: Int = 3,
    val retries: Int = 4,
    val homepage: String = "https://www.google.com",
    val gridColumns: Int = 3,
    val configValidationError: String = "",
    val isSaving: Boolean = false,
    val saveSuccess: Boolean = false,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    val galleryDlConfig: StateFlow<String> = settingsRepository.galleryDlConfig
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SettingsRepository.DEFAULT_CONFIG)

    init {
        viewModelScope.launch {
            settingsRepository.galleryDlConfig.collect { json ->
                _uiState.update { it.copy(configJson = json) }
            }
        }
        viewModelScope.launch {
            settingsRepository.filenameTemplate.collect { t ->
                _uiState.update { it.copy(filenameTemplate = t) }
            }
        }
        viewModelScope.launch {
            settingsRepository.sleepInterval.collect { s ->
                _uiState.update { it.copy(sleepInterval = s) }
            }
        }
        viewModelScope.launch {
            settingsRepository.downloadThreads.collect { t ->
                _uiState.update { it.copy(downloadThreads = t) }
            }
        }
        viewModelScope.launch {
            settingsRepository.retries.collect { r ->
                _uiState.update { it.copy(retries = r) }
            }
        }
        viewModelScope.launch {
            settingsRepository.homepage.collect { h ->
                _uiState.update { it.copy(homepage = h) }
            }
        }
        viewModelScope.launch {
            settingsRepository.gridColumns.collect { c ->
                _uiState.update { it.copy(gridColumns = c) }
            }
        }
    }

    fun updateConfigJson(json: String) {
        _uiState.update { it.copy(configJson = json, configValidationError = "") }
    }

    fun updateFilenameTemplate(value: String) {
        _uiState.update { it.copy(filenameTemplate = value) }
    }

    fun updateSleepInterval(value: String) {
        _uiState.update { it.copy(sleepInterval = value) }
    }

    fun updateDownloadThreads(threads: Int) {
        _uiState.update { it.copy(downloadThreads = threads.coerceIn(1, 8)) }
    }

    fun updateRetries(value: Int) {
        _uiState.update { it.copy(retries = value) }
    }

    fun updateHomepage(value: String) {
        _uiState.update { it.copy(homepage = value) }
    }

    fun updateGridColumns(value: Int) {
        _uiState.update { it.copy(gridColumns = value.coerceIn(2, 5)) }
    }

    fun validateAndSave() {
        val state = _uiState.value
        // Validate config JSON via Python
        val error = validateConfigJson(state.configJson)
        if (error.isNotBlank()) {
            _uiState.update { it.copy(configValidationError = error) }
            return
        }
        _uiState.update { it.copy(isSaving = true, configValidationError = "") }
        viewModelScope.launch {
            settingsRepository.setGalleryDlConfig(state.configJson)
            settingsRepository.setFilenameTemplate(state.filenameTemplate)
            settingsRepository.setSleepInterval(state.sleepInterval)
            settingsRepository.setDownloadThreads(state.downloadThreads)
            settingsRepository.setRetries(state.retries)
            settingsRepository.setHomepage(state.homepage)
            settingsRepository.setGridColumns(state.gridColumns)
            _uiState.update { it.copy(isSaving = false, saveSuccess = true) }
            kotlinx.coroutines.delay(2000)
            _uiState.update { it.copy(saveSuccess = false) }
        }
    }

    fun clearBrowserHistory() {
        viewModelScope.launch {
            settingsRepository.clearRecentUrls()
            _uiState.update { it.copy(saveSuccess = true) }
        }
    }

    fun resetToDefaults() {
        _uiState.update {
            it.copy(
                configJson = SettingsRepository.DEFAULT_CONFIG,
                filenameTemplate = "{filename}.{extension}",
                sleepInterval = "0.5",
                downloadThreads = 3,
                retries = 4,
                homepage = "https://www.google.com",
                gridColumns = 3,
                configValidationError = "",
            )
        }
    }

    private fun validateConfigJson(json: String): String {
        return try {
            val py = Python.getInstance()
            val module = py.getModule("config_helper")
            module.callAttr("validate_config_json", json).toString()
        } catch (e: Exception) {
            "" // Don't block saving if Python validation fails
        }
    }
}
