package com.thelightphone.tool.teslav2

import android.util.Log
import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.LightViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The simplified setup wizard steps. */
enum class SetupStep {
    Welcome,
    Processing,
    Done,
}

data class SetupUiState(
    val step: SetupStep = SetupStep.Welcome,
    val isLoading: Boolean = false,
    val errorModal: String? = null,
    val statusMessages: List<String> = emptyList(),
    val showQrScanner: Boolean = false,
    val setupComplete: Boolean = false,
)

class SetupWizardViewModel(
    private val credentialStore: CredentialStore,
    private val tokenStore: TokenStore,
    private val api: TeslaApi,
) : LightViewModel<Boolean>() {

    private val _uiState = MutableStateFlow(SetupUiState())
    val uiState: StateFlow<SetupUiState> = _uiState.asStateFlow()

    // ── Navigation ──────────────────────────────────────

    fun goToStep(step: SetupStep) {
        _uiState.update { it.copy(step = step, errorModal = null, statusMessages = emptyList()) }
    }

    // ── QR scanning ─────────────────────────────────────

    fun showQrScanner() {
        _uiState.update { it.copy(showQrScanner = true) }
    }

    fun hideQrScanner() {
        _uiState.update { it.copy(showQrScanner = false) }
    }

    /**
     * Called when the QR scanner reads the code from the desktop setup page.
     * Expected JSON: {
     *   "client_id": "...",
     *   "client_secret": "...",
     *   "auth_code": "...",
     *   "code_verifier": "..."
     * }
     *
     * The desktop page has already completed OAuth — we just need to
     * save credentials, exchange the auth code for tokens, fetch
     * vehicles, and finish.
     */
    fun onQrScanned(raw: String) {
        try {
            val json = org.json.JSONObject(raw.trim())
            val clientId = json.optString("client_id", "").trim()
            val clientSecret = json.optString("client_secret", "").trim()
            val authCode = json.optString("auth_code", "").trim()
            val codeVerifier = json.optString("code_verifier", "").trim()

            if (clientId.isBlank() || clientSecret.isBlank()) {
                _uiState.update {
                    it.copy(
                        showQrScanner = false,
                        errorModal = "QR code is missing credentials. Make sure you completed all steps on the setup page.",
                    )
                }
                return
            }

            if (authCode.isBlank() || codeVerifier.isBlank()) {
                _uiState.update {
                    it.copy(
                        showQrScanner = false,
                        errorModal = "QR code is missing sign-in data. Make sure you signed in with Tesla on the setup page.",
                    )
                }
                return
            }

            // Hide scanner, move to processing screen
            _uiState.update {
                it.copy(
                    showQrScanner = false,
                    step = SetupStep.Processing,
                    isLoading = true,
                    statusMessages = listOf("Saving credentials..."),
                )
            }

            // Save credentials
            credentialStore.saveClientId(clientId)
            credentialStore.saveClientSecret(clientSecret)
            Log.i("SetupWizard", "Credentials saved from QR")

            // Exchange auth code for tokens and finish setup
            exchangeAndFinish(authCode, codeVerifier)
        } catch (e: Exception) {
            Log.e("SetupWizard", "QR parse error: ${e.message}")
            _uiState.update {
                it.copy(
                    showQrScanner = false,
                    errorModal = "Couldn't read QR code. Make sure you're scanning the code from tesla-lightphone.app/setup.",
                )
            }
        }
    }

    /**
     * Exchanges the OAuth auth code for tokens, fetches vehicles,
     * and completes setup.
     */
    private fun exchangeAndFinish(authCode: String, codeVerifier: String) {
        viewModelScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) {
                _uiState.update {
                    it.copy(
                        statusMessages = listOf(
                            "✓ Credentials saved",
                            "Exchanging auth code...",
                        ),
                    )
                }
            }

            // Exchange auth code for access + refresh tokens
            val tokenResult = api.exchangeAuthCode(authCode, codeVerifier)

            if (tokenResult.isFailure) {
                val error = tokenResult.exceptionOrNull()?.message ?: "Unknown error"
                Log.e("SetupWizard", "Token exchange failed: $error")
                withContext(Dispatchers.Main) {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorModal = "Sign-in failed: $error\n\nPlease redo the setup on the desktop page and scan a new QR code.",
                        )
                    }
                }
                return@launch
            }

            withContext(Dispatchers.Main) {
                _uiState.update {
                    it.copy(
                        statusMessages = listOf(
                            "✓ Credentials saved",
                            "✓ Signed in",
                            "Finding your vehicles...",
                        ),
                    )
                }
            }

            // Fetch vehicles and select the first one
            val vehiclesResult = api.getVehicles()
            vehiclesResult.fold(
                onSuccess = { vehicles ->
                    val vehicle = vehicles.firstOrNull()
                    if (vehicle != null) {
                        tokenStore.saveSelectedVehicle(vehicle.vin, vehicle.displayName ?: "My Tesla")
                    }
                    credentialStore.markSetupComplete()
                    withContext(Dispatchers.Main) {
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                step = SetupStep.Done,
                            )
                        }
                    }
                },
                onFailure = { error ->
                    // Tokens saved — vehicle fetch can be retried from HomeScreen
                    Log.w("SetupWizard", "Vehicle fetch failed: ${error.message}")
                    credentialStore.markSetupComplete()
                    withContext(Dispatchers.Main) {
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                step = SetupStep.Done,
                            )
                        }
                    }
                },
            )
        }
    }

    // ── Done ────────────────────────────────────────────

    fun finishSetup() {
        _uiState.update { it.copy(setupComplete = true) }
    }

    fun dismissError() {
        _uiState.update { it.copy(errorModal = null) }
    }
}
