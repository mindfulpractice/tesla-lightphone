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
    ScanAuth,
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

    // Temporarily hold auth code and verifier between QR scans
    private var pendingAuthCode: String? = null
    private var pendingCodeVerifier: String? = null

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
     * Handles scanned QR codes. The desktop setup page generates two codes:
     *
     * QR 1 (credentials): {"t":"cred","i":"<client_id>","s":"<client_secret>"}
     * QR 2 (auth):         {"t":"auth","a":"<auth_code>","v":"<code_verifier>"}
     *
     * After scanning QR 1, the app saves credentials and prompts for QR 2.
     * After scanning QR 2, the app exchanges the auth code for tokens.
     */
    fun onQrScanned(raw: String) {
        try {
            val json = org.json.JSONObject(raw.trim())
            val type = json.optString("t", "")

            when (type) {
                "cred" -> handleCredentialQr(json)
                "auth" -> handleAuthQr(json)
                else -> {
                    // Try legacy format (single QR with full keys)
                    handleLegacyQr(json)
                }
            }
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

    /** QR 1: credentials — save and prompt for QR 2. */
    private fun handleCredentialQr(json: org.json.JSONObject) {
        val id = json.optString("i", "").trim()
        val secret = json.optString("s", "").trim()

        if (id.isBlank() || secret.isBlank()) {
            _uiState.update {
                it.copy(
                    showQrScanner = false,
                    errorModal = "QR code is missing credentials. Scan the first QR code from the setup page.",
                )
            }
            return
        }

        credentialStore.saveClientId(id)
        credentialStore.saveClientSecret(secret)
        Log.i("SetupWizard", "Credentials saved from QR 1")

        // Move to scan QR 2
        _uiState.update {
            it.copy(
                showQrScanner = false,
                step = SetupStep.ScanAuth,
            )
        }
    }

    /** QR 2: auth code + verifier — exchange tokens and finish. */
    private fun handleAuthQr(json: org.json.JSONObject) {
        val authCode = json.optString("a", "").trim()
        val codeVerifier = json.optString("v", "").trim()

        if (authCode.isBlank() || codeVerifier.isBlank()) {
            _uiState.update {
                it.copy(
                    showQrScanner = false,
                    errorModal = "QR code is missing sign-in data. Scan the second QR code from the setup page.",
                )
            }
            return
        }

        _uiState.update {
            it.copy(
                showQrScanner = false,
                step = SetupStep.Processing,
                isLoading = true,
                statusMessages = listOf("✓ Credentials saved", "Signing in..."),
            )
        }

        exchangeAndFinish(authCode, codeVerifier)
    }

    /** Fallback: handles old single-QR format with full key names. */
    private fun handleLegacyQr(json: org.json.JSONObject) {
        val id = json.optString("client_id", "").trim()
        val secret = json.optString("client_secret", "").trim()
        val authCode = json.optString("auth_code", "").trim()
        val codeVerifier = json.optString("code_verifier", "").trim()

        if (id.isNotBlank() && secret.isNotBlank()) {
            credentialStore.saveClientId(id)
            credentialStore.saveClientSecret(secret)

            if (authCode.isNotBlank() && codeVerifier.isNotBlank()) {
                _uiState.update {
                    it.copy(
                        showQrScanner = false,
                        step = SetupStep.Processing,
                        isLoading = true,
                        statusMessages = listOf("✓ Credentials saved", "Signing in..."),
                    )
                }
                exchangeAndFinish(authCode, codeVerifier)
            } else {
                _uiState.update {
                    it.copy(showQrScanner = false, step = SetupStep.ScanAuth)
                }
            }
            return
        }

        _uiState.update {
            it.copy(
                showQrScanner = false,
                errorModal = "Unrecognized QR code. Use the codes from tesla-lightphone.app/setup.",
            )
        }
    }

    /**
     * Exchanges the OAuth auth code for tokens, fetches vehicles,
     * and completes setup.
     */
    private fun exchangeAndFinish(authCode: String, codeVerifier: String) {
        viewModelScope.launch(Dispatchers.IO) {
            // Exchange auth code for access + refresh tokens
            val tokenResult = api.exchangeAuthCode(authCode, codeVerifier)

            if (tokenResult.isFailure) {
                val error = tokenResult.exceptionOrNull()?.message ?: "Unknown error"
                Log.e("SetupWizard", "Token exchange failed: $error")
                withContext(Dispatchers.Main) {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorModal = "Sign-in failed: $error\n\nPlease redo the setup on the desktop page and scan new QR codes.",
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
                            it.copy(isLoading = false, step = SetupStep.Done)
                        }
                    }
                },
                onFailure = { error ->
                    Log.w("SetupWizard", "Vehicle fetch failed: ${error.message}")
                    credentialStore.markSetupComplete()
                    withContext(Dispatchers.Main) {
                        _uiState.update {
                            it.copy(isLoading = false, step = SetupStep.Done)
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
