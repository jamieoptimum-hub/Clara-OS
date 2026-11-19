package com.jarvis.smarthomelauncher.ui.viewmodels

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jarvis.smarthomelauncher.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

fun Context.findComponentActivity(): ComponentActivity? =
    generateSequence(this) { (it as? ContextWrapper)?.baseContext }
        .filterIsInstance<ComponentActivity>()
        .firstOrNull()

data class DashboardUiState(
    val selectedTab: String = "dashboard",
    val selectedRoomFilter: String = "All Rooms",
    val isEditMode: Boolean = false,
    val searchQuery: String = "",
    val activeModalTileId: String? = null,
    val activeModalType: String? = null
)

class DashboardViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    private val _tilesFlow = MutableStateFlow<List<SmartTile>>(emptyList())
    val tilesFlow: StateFlow<List<SmartTile>> = _tilesFlow.asStateFlow()

    private val _bridgeSettingsFlow = MutableStateFlow(BridgeSettings())
    val bridgeSettingsFlow: StateFlow<BridgeSettings> = _bridgeSettingsFlow.asStateFlow()

    private val _automationRulesFlow = MutableStateFlow<List<AutomationRule>>(emptyList())
    val automationRulesFlow: StateFlow<List<AutomationRule>> = _automationRulesFlow.asStateFlow()

    val tiles = mutableStateListOf<SmartTile>()
    var bridgeSettings by mutableStateOf(BridgeSettings())
    val automationRules = mutableStateListOf<AutomationRule>()

    init {
        observeReactiveRepositoryFlows()
        hydrateInitialStateAsync()
    }

    private fun hydrateInitialStateAsync() {
        val context = getApplication<Application>().applicationContext
        viewModelScope.launch(Dispatchers.IO) {
            DeviceRepository.loadTiles(context)
            DeviceRepository.loadBridge(context)
            DeviceRepository.loadAutomations(context)
            DeviceRepository.loadTelemetryHistory(context)
            DeviceRepository.loadSecurityAuditLogs(context)
            DeviceRepository.loadSelfHealingLogs(context)
            DeviceRepository.loadDvrSnapshots(context)
            DeviceRepository.loadAutomationLogs(context)
        }
    }

    private fun observeReactiveRepositoryFlows() {
        viewModelScope.launch {
            launch {
                DeviceRepository.tilesFlow.collect { loadedTiles ->
                    _tilesFlow.value = loadedTiles
                    for (t in loadedTiles) {
                        val idx = tiles.indexOfFirst { it.id == t.id }
                        if (idx != -1) {
                            if (tiles[idx] != t) {
                                tiles[idx] = t
                            }
                        } else {
                            tiles.add(t)
                        }
                    }
                    tiles.removeAll { currentTile -> loadedTiles.none { it.id == currentTile.id } }
                }
            }
            launch {
                DeviceRepository.bridgeSettingsFlow.collect { bridge ->
                    _bridgeSettingsFlow.value = bridge
                    bridgeSettings = bridge
                }
            }
            launch {
                DeviceRepository.automationRulesFlow.collect { newRules ->
                    _automationRulesFlow.value = newRules
                    if (automationRules.toList() != newRules) {
                        automationRules.clear()
                        automationRules.addAll(newRules)
                    }
                }
            }
        }
    }

    fun updateTile(context: Context, updatedTile: SmartTile) {
        val idx = tiles.indexOfFirst { it.id == updatedTile.id }
        if (idx != -1) {
            tiles[idx] = updatedTile
        }
        _tilesFlow.value = tiles.toList()
        viewModelScope.launch(Dispatchers.IO) {
            DeviceRepository.saveTiles(context, tiles.toList())
        }
    }

    fun saveTiles(context: Context, newTiles: List<SmartTile>) {
        if (tiles.toList() != newTiles) {
            tiles.clear()
            tiles.addAll(newTiles)
        }
        _tilesFlow.value = newTiles
        viewModelScope.launch(Dispatchers.IO) {
            DeviceRepository.saveTiles(context, newTiles)
        }
    }

    fun updateBridgeSettings(context: Context, updatedBridge: BridgeSettings) {
        bridgeSettings = updatedBridge
        _bridgeSettingsFlow.value = updatedBridge
        viewModelScope.launch(Dispatchers.IO) {
            DeviceRepository.saveBridge(context, updatedBridge)
        }
    }

    fun saveAutomations(context: Context, newRules: List<AutomationRule>) {
        if (automationRules.toList() != newRules) {
            automationRules.clear()
            automationRules.addAll(newRules)
        }
        _automationRulesFlow.value = newRules
        viewModelScope.launch(Dispatchers.IO) {
            DeviceRepository.saveAutomations(context, newRules)
        }
    }

    fun unpairTile(context: Context, tile: SmartTile) {
        viewModelScope.launch(Dispatchers.IO) {
            DeviceRepository.unpairTileFromDashboard(context, tile, tiles)
        }
    }

    fun unbindAndForgetTile(context: Context, tile: SmartTile) {
        viewModelScope.launch(Dispatchers.IO) {
            DeviceRepository.unbindAndForgetTile(context, tile, bridgeSettings, tiles)
        }
    }

    fun selectTab(tab: String) {
        _uiState.value = _uiState.value.copy(selectedTab = tab)
    }

    fun selectRoomFilter(room: String) {
        _uiState.value = _uiState.value.copy(selectedRoomFilter = room)
    }

    fun setEditMode(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(isEditMode = enabled)
    }

    fun setSearchQuery(query: String) {
        _uiState.value = _uiState.value.copy(searchQuery = query)
    }

    fun openModal(tileId: String?, modalType: String?) {
        _uiState.value = _uiState.value.copy(activeModalTileId = tileId, activeModalType = modalType)
    }

    fun closeModal() {
        _uiState.value = _uiState.value.copy(activeModalTileId = null, activeModalType = null)
    }
}
