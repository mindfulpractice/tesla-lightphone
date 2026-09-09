package com.thelightphone.tool.tesla

import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SimpleLightScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class SettingsPage { Root, Controls, Order }

data class SettingsUiState(
    val page: SettingsPage = SettingsPage.Root,
    val controls: List<ControlSetting> = DEFAULT_CONTROLS,
    val units: UnitSystem = UnitSystem.Imperial,
    val vehicleName: String = "",
    val vin: String = "",
)

class SettingsViewModel(
    private val tokenStore: TokenStore,
) : LightViewModel<Boolean>() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    override fun onScreenShow(screen: SimpleLightScreen<Boolean>) {
        super.onScreenShow(screen)
        loadControls()
    }

    private fun loadControls() {
        viewModelScope.launch(Dispatchers.IO) {
            val controls = tokenStore.getControls()
            val units = tokenStore.getUnits()
            val name = tokenStore.getVehicleName() ?: ""
            val vin = tokenStore.getSelectedVin() ?: ""
            _uiState.update {
                it.copy(controls = controls, units = units, vehicleName = name, vin = vin)
            }
        }
    }

    fun navigateTo(page: SettingsPage) {
        _uiState.update { it.copy(page = page) }
    }

    fun toggleVisibility(index: Int) {
        _uiState.update { state ->
            val list = state.controls.toMutableList()
            val item = list[index]
            list[index] = item.copy(visible = !item.visible)
            state.copy(controls = list)
        }
        saveControls()
    }

    /**
     * Move a visible control up. The [visibleIndex] is relative to the
     * filtered visible-only list, so we map it back to the full list.
     */
    fun moveUp(visibleIndex: Int) {
        if (visibleIndex <= 0) return
        _uiState.update { state ->
            val list = state.controls.toMutableList()
            val visibleIds = list.filter { it.visible }.map { it.id }
            val targetId = visibleIds[visibleIndex]
            val prevId = visibleIds[visibleIndex - 1]
            val targetIdx = list.indexOfFirst { it.id == targetId }
            val prevIdx = list.indexOfFirst { it.id == prevId }
            // Swap in the full list
            val temp = list[targetIdx]
            list[targetIdx] = list[prevIdx]
            list[prevIdx] = temp
            state.copy(controls = list)
        }
        saveControls()
    }

    /**
     * Move a visible control down. Same visible-index mapping as [moveUp].
     */
    fun moveDown(visibleIndex: Int) {
        _uiState.update { state ->
            val visibleIds = state.controls.filter { it.visible }.map { it.id }
            if (visibleIndex >= visibleIds.lastIndex) return@update state
            val list = state.controls.toMutableList()
            val targetId = visibleIds[visibleIndex]
            val nextId = visibleIds[visibleIndex + 1]
            val targetIdx = list.indexOfFirst { it.id == targetId }
            val nextIdx = list.indexOfFirst { it.id == nextId }
            val temp = list[targetIdx]
            list[targetIdx] = list[nextIdx]
            list[nextIdx] = temp
            state.copy(controls = list)
        }
        saveControls()
    }

    fun toggleUnits() {
        val newUnits = _uiState.value.units.toggle()
        _uiState.update { it.copy(units = newUnits) }
        viewModelScope.launch(Dispatchers.IO) {
            tokenStore.saveUnits(newUnits)
        }
    }

    suspend fun disconnect() {
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            tokenStore.clear()
        }
    }

    private fun saveControls() {
        viewModelScope.launch(Dispatchers.IO) {
            tokenStore.saveControls(_uiState.value.controls)
        }
    }
}
