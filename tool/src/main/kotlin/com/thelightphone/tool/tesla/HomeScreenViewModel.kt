package com.thelightphone.tool.tesla

import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SimpleLightScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HomeScreenViewModel(
    private val tokenStore: TokenStore,
    private val api: TeslaApi,
) : LightViewModel<Unit>() {

    private val _uiState = MutableStateFlow(TeslaUiState())
    val uiState: StateFlow<TeslaUiState> = _uiState.asStateFlow()
    private var startCountdownJob: kotlinx.coroutines.Job? = null
    private var authJob: kotlinx.coroutines.Job? = null

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        checkAuth()
    }

    private fun checkAuth() {
        authJob?.cancel()
        authJob = viewModelScope.launch(Dispatchers.IO) {
            if (!tokenStore.hasToken()) {
                withContext(Dispatchers.Main) {
                    _uiState.value = TeslaUiState(mode = TeslaScreenMode.NeedsSetup)
                }
                return@launch
            }

            // Refresh access token on startup — retry once before giving up
            var refreshResult = api.refreshAccessToken()
            if (refreshResult.isFailure) {
                kotlinx.coroutines.delay(2000)
                refreshResult = api.refreshAccessToken()
            }
            if (refreshResult.isFailure) {
                val error = refreshResult.exceptionOrNull()
                val isAuthError = error?.message?.contains("401") == true ||
                    error?.message?.contains("invalid_grant") == true ||
                    error?.message?.contains("Auth failed") == true
                if (isAuthError) {
                    // Genuine auth rejection — tokens are invalid, must re-authenticate
                    tokenStore.clear()
                    withContext(Dispatchers.Main) {
                        _uiState.value = TeslaUiState(
                            mode = TeslaScreenMode.NeedsSetup,
                            errorModal = "Session expired. Scan QR code to reconnect.",
                        )
                    }
                } else {
                    // Network error — keep tokens, show controls with an error
                    val vin = tokenStore.getSelectedVin()
                    val name = tokenStore.getVehicleName() ?: ""
                    if (vin != null) {
                        val controls = tokenStore.getControls()
                        val units = tokenStore.getUnits()
                        withContext(Dispatchers.Main) {
                            _uiState.value = TeslaUiState(
                                mode = TeslaScreenMode.Controls(vehicleName = name),
                                controlSettings = controls,
                                units = units,
                                errorModal = "No internet — check your connection.",
                            )
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            _uiState.value = TeslaUiState(
                                mode = TeslaScreenMode.NeedsSetup,
                                errorModal = "No internet — check your connection.",
                            )
                        }
                    }
                }
                return@launch
            }

            // Fetch vehicles if we don't have one selected yet
            if (tokenStore.getSelectedVin() == null) {
                val vehiclesResult = api.getVehicles()
                vehiclesResult.fold(
                    onSuccess = { vehicles ->
                        if (vehicles.isEmpty()) {
                            withContext(Dispatchers.Main) {
                                _uiState.value = TeslaUiState(
                                    mode = TeslaScreenMode.NeedsSetup,
                                    errorModal = "No vehicles found on this account.",
                                )
                            }
                            return@launch
                        }
                        val v = vehicles.first()
                        tokenStore.saveSelectedVehicle(v.vin, v.displayName ?: "My Tesla")
                    },
                    onFailure = { error ->
                        withContext(Dispatchers.Main) {
                            _uiState.value = TeslaUiState(
                                mode = TeslaScreenMode.NeedsSetup,
                                errorModal = "Vehicle fetch error: ${error.message}",
                            )
                        }
                        return@launch
                    },
                )
            }

            val vin = tokenStore.getSelectedVin()
            val name = tokenStore.getVehicleName() ?: ""
            if (tokenStore.hasToken() && vin != null) {
                val controls = tokenStore.getControls()
                val units = tokenStore.getUnits()
                withContext(Dispatchers.Main) {
                    _uiState.value = TeslaUiState(
                        mode = TeslaScreenMode.Controls(vehicleName = name),
                        controlSettings = controls,
                        units = units,
                    )
                }
                // Only fetch if no cached state — otherwise show cached data.
                // User can tap refresh for fresh data.
                if (_uiState.value.vehicleState == null) {
                    fetchVehicleState()
                }
            } else {
                withContext(Dispatchers.Main) {
                    _uiState.value = TeslaUiState(mode = TeslaScreenMode.NeedsSetup)
                }
            }
        }
    }

    fun onTokenSaved() {
        checkAuth()
    }

    fun reloadControls() {
        viewModelScope.launch(Dispatchers.IO) {
            val controls = tokenStore.getControls()
            val units = tokenStore.getUnits()
            withContext(Dispatchers.Main) {
                _uiState.update { it.copy(controlSettings = controls, units = units) }
            }
        }
    }

    // ── Vehicle state ────────────────────────────────────

    fun fetchVehicleState() {
        viewModelScope.launch(Dispatchers.IO) {
            val vin = tokenStore.getSelectedVin() ?: return@launch

            withContext(Dispatchers.Main) {
                _uiState.update { it.copy(isRefreshing = true) }
            }

            // Try fetching state directly — works if car is awake or API has cached data
            var result = api.getVehicleState(vin)

            // If 408 (vehicle offline), wake and retry
            if (result.isFailure && result.exceptionOrNull()?.message?.contains("408") == true) {
                val wakeResult = api.wakeUp(vin)
                if (wakeResult.isFailure) {
                    withContext(Dispatchers.Main) {
                        _uiState.update {
                            it.copy(
                                isRefreshing = false,
                                errorModal = "Vehicle asleep — try again in a moment",
                            )
                        }
                    }
                    return@launch
                }
                result = api.getVehicleState(vin)
            }

            withContext(Dispatchers.Main) {
                result.fold(
                    onSuccess = { state ->
                        _uiState.update {
                            it.copy(
                                vehicleState = state,
                                isRefreshing = false,
                                statusMessage = null,
                            )
                        }
                    },
                    onFailure = { error ->
                        _uiState.update {
                            it.copy(
                                isRefreshing = false,
                                errorModal = "Refresh failed: ${error.message?.take(100)}",
                            )
                        }
                    },
                )
            }
        }
    }

    // ── Commands ─────────────────────────────────────────

    fun executeCommand(command: TeslaCommand) {
        viewModelScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) {
                _uiState.update { it.copy(isLoading = true, activeCommand = command, statusMessage = null) }
            }

            val vin = tokenStore.getSelectedVin()
            if (vin == null) {
                withContext(Dispatchers.Main) {
                    _uiState.update {
                        it.copy(isLoading = false, activeCommand = null, errorModal = "No vehicle selected.")
                    }
                }
                return@launch
            }

            val result = when (command) {
                TeslaCommand.Lock -> api.lock(vin)
                TeslaCommand.Unlock -> api.unlock(vin)
                TeslaCommand.RemoteStart -> api.remoteStart(vin)
                TeslaCommand.ClimateOn -> api.climateOn(vin)
                TeslaCommand.ClimateOff -> api.climateOff(vin)
                TeslaCommand.DefrostOn -> api.defrostOn(vin)
                TeslaCommand.DefrostOff -> api.defrostOff(vin)
                TeslaCommand.OverheatOff -> api.overheatOff(vin)
                TeslaCommand.OverheatFan -> api.overheatFanOnly(vin)
                TeslaCommand.OverheatAC -> api.overheatAcOn(vin)
                TeslaCommand.OpenTrunk -> api.openTrunk(vin)
                TeslaCommand.CloseTrunk -> api.closeTrunk(vin)
                TeslaCommand.OpenFrunk -> api.openFrunk(vin)
                TeslaCommand.ChargePortOpen -> api.chargePortOpen(vin)
                TeslaCommand.ChargePortClose -> api.chargePortClose(vin)
                TeslaCommand.SentryOn -> api.sentryOn(vin)
                TeslaCommand.SentryOff -> api.sentryOff(vin)
                TeslaCommand.FlashLights -> api.flashLights(vin)
                TeslaCommand.HonkHorn -> api.honkHorn(vin)
                TeslaCommand.VentWindows -> api.ventWindows(vin)
                TeslaCommand.CloseWindows -> api.closeWindows(vin)
            }

            withContext(Dispatchers.Main) {
                result.fold(
                    onSuccess = { cmdResult ->
                        // Optimistically update local state
                        val newVehicleState = _uiState.value.vehicleState?.let { vs ->
                            when (command) {
                                TeslaCommand.Lock -> vs.copy(locked = true)
                                TeslaCommand.Unlock -> vs.copy(locked = false)
                                TeslaCommand.ClimateOn -> vs.copy(climateOn = true)
                                TeslaCommand.ClimateOff -> vs.copy(climateOn = false, defrostOn = false)
                                TeslaCommand.DefrostOn -> vs.copy(defrostOn = true, climateOn = true)
                                TeslaCommand.DefrostOff -> vs.copy(defrostOn = false)
                                TeslaCommand.OverheatOff -> vs.copy(overheatMode = OverheatMode.Off)
                                TeslaCommand.OverheatFan -> vs.copy(overheatMode = OverheatMode.FanOnly)
                                TeslaCommand.OverheatAC -> vs.copy(overheatMode = OverheatMode.AC)
                                TeslaCommand.OpenTrunk -> vs.copy(trunkOpen = true)
                                TeslaCommand.CloseTrunk -> vs.copy(trunkOpen = false)
                                TeslaCommand.OpenFrunk -> vs.copy(frunkOpen = true)
                                TeslaCommand.ChargePortOpen -> vs.copy(chargePortOpen = true)
                                TeslaCommand.ChargePortClose -> vs.copy(chargePortOpen = false)
                                TeslaCommand.SentryOn -> vs.copy(sentryMode = true)
                                TeslaCommand.SentryOff -> vs.copy(sentryMode = false)
                                TeslaCommand.VentWindows -> vs.copy(windowsOpen = true)
                                TeslaCommand.CloseWindows -> vs.copy(windowsOpen = false)
                                else -> vs
                            }
                        }
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                activeCommand = null,
                                vehicleState = newVehicleState ?: it.vehicleState,
                                statusMessage = if (cmdResult.result) null else cmdResult.reason.ifEmpty { null },
                            )
                        }
                        // Start 2-minute countdown for remote start
                        if (command == TeslaCommand.RemoteStart && cmdResult.result) {
                            startStartCountdown()
                        }
                    },
                    onFailure = { error ->
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                activeCommand = null,
                                errorModal = error.message ?: "Command failed",
                            )
                        }
                    },
                )
            }

        }
    }

    private fun startStartCountdown() {
        startCountdownJob?.cancel()
        val expiresAt = System.currentTimeMillis() + 120_000L
        startCountdownJob = viewModelScope.launch {
            while (true) {
                val remaining = ((expiresAt - System.currentTimeMillis()) / 1000).toInt()
                if (remaining <= 0) break
                _uiState.update { it.copy(startSecondsLeft = remaining) }
                delay(1000)
            }
            _uiState.update { it.copy(startSecondsLeft = null) }
        }
    }

    fun disconnect() {
        authJob?.cancel()
        startCountdownJob?.cancel()
        viewModelScope.launch(Dispatchers.IO) {
            tokenStore.clear()
            withContext(Dispatchers.Main) {
                _uiState.value = TeslaUiState(mode = TeslaScreenMode.NeedsSetup)
            }
        }
    }

    fun dismissError() {
        _uiState.update { it.copy(errorModal = null) }
    }
}
