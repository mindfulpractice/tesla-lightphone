package com.thelightphone.tool.tesla

import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.LightViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class TokenSetupUiState(
    val isLoading: Boolean = false,
    val editorSession: Int = 0,
    val errorModal: String? = null,
)

class TokenSetupViewModel(
    private val tokenStore: TokenStore,
    private val api: TeslaApi,
) : LightViewModel<Boolean>() {

    private val _uiState = MutableStateFlow(TokenSetupUiState())
    val uiState: StateFlow<TokenSetupUiState> = _uiState.asStateFlow()

    /**
     * Handle scanned/pasted input. Supports two formats:
     * - tesla-auth:CODE|VERIFIER — new flow, exchanges auth code for tokens in-app
     * - tesla-token:REFRESH_TOKEN — legacy flow, uses refresh token directly
     * - raw token string — treated as refresh token
     */
    fun onSubmit(raw: String, onResult: (Boolean) -> Unit) {
        val input = raw.trim()
        if (input.isBlank()) {
            _uiState.update { it.copy(errorModal = "Nothing scanned. Try again.") }
            return
        }

        _uiState.update { it.copy(isLoading = true) }

        viewModelScope.launch(Dispatchers.IO) {
            // Clean up adb input artifacts: strip literal backslashes before : and |
            val cleaned = input.replace("\\:", ":").replace("\\|", "|")
            android.util.Log.d("TokenSetup", "Input (${cleaned.length} chars): ${cleaned.take(30)}...")

            // Determine which flow to use based on prefix
            val authResult: Result<String> = when {
                cleaned.startsWith("tesla-auth:") -> {
                    // New flow: exchange authorization code + verifier for tokens
                    val payload = cleaned.removePrefix("tesla-auth:")
                    val parts = payload.split("|", limit = 2)
                    if (parts.size != 2) {
                        Result.failure(Exception("Invalid format — no | separator found in: ${payload.take(20)}..."))
                    } else {
                        android.util.Log.d("TokenSetup", "Exchanging auth code (${parts[0].take(10)}...) with verifier")
                        api.exchangeAuthCode(parts[0], parts[1])
                    }
                }
                cleaned.contains("|") && !cleaned.startsWith("tesla-token:") -> {
                    // Auto-detect: looks like CODE|VERIFIER without prefix
                    val parts = cleaned.split("|", limit = 2)
                    android.util.Log.d("TokenSetup", "Auto-detected code|verifier (no prefix)")
                    api.exchangeAuthCode(parts[0], parts[1])
                }
                else -> {
                    // Legacy: raw is a refresh token (with or without tesla-token: prefix)
                    android.util.Log.d("TokenSetup", "Legacy refresh token flow")
                    val token = cleaned.removePrefix("tesla-token:").trim()
                    tokenStore.saveRefreshToken(token)
                    api.refreshAccessToken()
                }
            }

            if (authResult.isFailure) {
                val errorMsg = authResult.exceptionOrNull()?.message ?: "Unknown error"
                android.util.Log.e("TokenSetup", "Auth failed: $errorMsg")
                tokenStore.clear()
                withContext(Dispatchers.Main) {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            editorSession = it.editorSession + 1,
                            errorModal = "Auth error: $errorMsg",
                        )
                    }
                }
                return@launch
            }

            val vehiclesResult = api.getVehicles()
            vehiclesResult.fold(
                onSuccess = { vehicles ->
                    val vehicle = vehicles.firstOrNull()
                    if (vehicle != null) {
                        tokenStore.saveSelectedVehicle(vehicle.vin, vehicle.displayName ?: "My Tesla")
                    }
                    withContext(Dispatchers.Main) {
                        _uiState.update { it.copy(isLoading = false) }
                        onResult(true)
                    }
                },
                onFailure = { error ->
                    withContext(Dispatchers.Main) {
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                errorModal = error.message ?: "Failed to fetch vehicles.",
                            )
                        }
                    }
                },
            )
        }
    }

    fun dismissError() {
        _uiState.update { it.copy(errorModal = null) }
    }
}
