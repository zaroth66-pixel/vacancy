package com.ethiotelecom.ussd.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.ethiotelecom.ussd.UssdApplication
import com.ethiotelecom.ussd.model.UssdCategory
import com.ethiotelecom.ussd.model.UssdCode
import com.ethiotelecom.ussd.model.UssdConfig
import kotlinx.coroutines.launch

sealed class UiState {
    object Loading : UiState()
    data class Success(val config: UssdConfig) : UiState()
    data class Error(val message: String, val config: UssdConfig? = null) : UiState()
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val app   = application as UssdApplication
    private val repo  = app.configRepository
    private val prefs = app.preferenceManager

    private val _uiState        = MutableLiveData<UiState>()
    val uiState: LiveData<UiState> = _uiState

    private val _filteredCodes  = MutableLiveData<List<UssdCode>>()
    val filteredCodes: LiveData<List<UssdCode>> = _filteredCodes

    private val _selectedCategory = MutableLiveData<UssdCategory?>()
    val selectedCategory: LiveData<UssdCategory?> = _selectedCategory

    private val _pinnedCodes    = MutableLiveData<List<UssdCode>>()
    val pinnedCodes: LiveData<List<UssdCode>> = _pinnedCodes

    private var currentConfig: UssdConfig? = null

    init { loadConfig() }

    fun loadConfig(forceRefresh: Boolean = false) {
        _uiState.value = UiState.Loading
        viewModelScope.launch {
            val result = repo.getConfig(forceRefresh)
            if (result.isSuccess) {
                val config = result.getOrThrow()
                currentConfig = config
                applyPinnedState(config)
                _uiState.value = UiState.Success(config)
            } else {
                val bundled = repo.getBundledConfig()
                currentConfig = bundled
                applyPinnedState(bundled)
                _uiState.value = UiState.Error(
                    result.exceptionOrNull()?.message ?: "Failed to load",
                    bundled
                )
            }
        }
    }

    fun search(query: String) {
        val config = currentConfig ?: return
        if (query.isBlank()) { _filteredCodes.value = emptyList(); return }
        val lower   = query.trim().lowercase()
        _filteredCodes.value = config.categories.flatMap { it.codes }
            .filter { code ->
                code.label.lowercase().contains(lower) ||
                code.code.contains(lower) ||
                code.description?.lowercase()?.contains(lower) == true ||
                code.tags.any { it.lowercase().contains(lower) } ||
                code.category.lowercase().contains(lower)
            }
            .sortedWith(
                compareByDescending<UssdCode> { it.label.lowercase().startsWith(lower) }
                    .thenByDescending { it.useCount }
            )
    }

    fun selectCategory(category: UssdCategory?) { _selectedCategory.value = category }

    fun togglePin(code: UssdCode) {
        code.isPinned = !code.isPinned
        val allCodes = currentConfig?.categories?.flatMap { it.codes } ?: return
        val pinned   = allCodes.filter { it.isPinned }
        prefs.savePinnedCodes(pinned)
        _pinnedCodes.value = pinned
    }

    fun recordDial(code: UssdCode) {
        val now = System.currentTimeMillis()
        code.lastUsed = now
        code.useCount++
        prefs.incrementUseCount(code.id)
        prefs.setLastUsed(code.id, now)
    }

    private fun applyPinnedState(config: UssdConfig) {
        val pinnedIds = prefs.getPinnedCodeIds()
        val allCodes  = config.categories.flatMap { it.codes }
        allCodes.forEach {
            it.isPinned = it.id in pinnedIds
            it.useCount = prefs.getUseCount(it.id)
            it.lastUsed = prefs.getLastUsed(it.id)
        }
        _pinnedCodes.value = allCodes.filter { it.isPinned }
    }
}
