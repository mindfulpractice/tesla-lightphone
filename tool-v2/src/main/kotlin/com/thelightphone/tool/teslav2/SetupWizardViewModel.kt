package com.thelightphone.tool.teslav2

import android.content.Context
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

/** Which step the wizard is showing. */
enum class SetupStep {
    Welcome,
    DevAccount,
    EnterCredentials,
    Registering,
    PairKey,
    SignIn,
    Done,
}

data class SetupUiState(
    val step: SetupStep = SetupStep.Welcome,
    val clientId: String = "",
    val clientSecret: String = "",
    val isLoading: Boolean = false,
    val errorModal: String? = null,
    /** Progress messages shown during the registration step. */
    val registrationStatus: List<String> = emptyList(),
)

class SetupWizardViewModel(
    private val credentialStore: CredentialStore,
    private val tokenStore: TokenStore,
    private val api: TeslaApi,
    private val appContext: Context,
) : LightViewModel<Boolean>() {

    private val _uiState = MutableStateFlow(SetupUiState())
    val uiState: StateFlow<SetupUiState> = _uiState.asStateFlow()

    // ── Navigation ──────────────────────────────────────

    fun goToStep(step: SetupStep) {
        _uiState.update { it.copy(step = step, errorModal = null) }
    }

    fun nextStep() {
        val next = when (_uiState.value.step) {
            SetupStep.Welcome -> SetupStep.DevAccount
            SetupStep.DevAccount -> SetupStep.EnterCredentials
            SetupStep.EnterCredentials -> SetupStep.Registering
            SetupStep.Registering -> SetupStep.PairKey
            SetupStep.PairKey -> SetupStep.SignIn
            SetupStep.SignIn -> SetupStep.Done
            SetupStep.Done -> SetupStep.Done
        }
        _uiState.update { it.copy(step = next, errorModal = null) }
    }

    // ── Credential entry ────────────────────────────────

    fun updateClientId(value: String) {
        _uiState.update { it.copy(clientId = value) }
    }

    fun updateClientSecret(value: String) {
        _uiState.update { it.copy(clientSecret = value) }
    }

    fun saveCredentials() {
        val id = _uiState.value.clientId.trim()
        val secret = _uiState.value.clientSecret.trim()

        if (id.isBlank() || secret.isBlank()) {
            _uiState.update { it.copy(errorModal = "Both fields are required.") }
            return
        }

        credentialStore.saveClientId(id)
        credentialStore.saveClientSecret(secret)
        Log.i("SetupWizard", "Credentials saved")

        // Move to auto-registration step
        nextStep()
        runRegistration()
    }

    // ── Automated partner registration ──────────────────

    private fun runRegistration() {
        viewModelScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) {
                _uiState.update {
                    it.copy(
                        isLoading = true,
                        registrationStatus = listOf("Getting partner token..."),
                    )
                }
            }

            // Step 1: Get partner token
            val partnerResult = api.getPartnerToken()
            if (partnerResult.isFailure) {
                val error = partnerResult.exceptionOrNull()?.message ?: "Unknown error"
                Log.e("SetupWizard", "Partner token failed: $error")
                withContext(Dispatchers.Main) {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorModal = "Partner auth failed: $error\n\nCheck your Client ID and Secret.",
                        )
                    }
                }
                return@launch
            }

            val partnerToken = partnerResult.getOrThrow()
            withContext(Dispatchers.Main) {
                _uiState.update {
                    it.copy(
                        registrationStatus = listOf(
                            "✓ Got partner token",
                            "Registering domain...",
                        ),
                    )
                }
            }

            // Step 2: Register domain with Tesla
            val registerResult = api.registerDomain(partnerToken)
            if (registerResult.isFailure) {
                val error = registerResult.exceptionOrNull()?.message ?: "Unknown error"
                Log.e("SetupWizard", "Domain registration failed: $error")
                withContext(Dispatchers.Main) {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorModal = "Registration failed: $error",
                        )
                    }
                }
                return@launch
            }

            withContext(Dispatchers.Main) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        registrationStatus = listOf(
                            "✓ Got partner token",
                            "✓ Domain registered",
                        ),
                    )
                }
            }

            // Short pause so user can see the checkmarks, then advance
            kotlinx.coroutines.delay(1500)
            withContext(Dispatchers.Main) {
                nextStep()
            }
        }
    }

    fun retryRegistration() {
        _uiState.update { it.copy(errorModal = null) }
        runRegistration()
    }

    // ── OAuth (WebView callback) ────────────────────────

    /**
     * Called when the in-app WebView captures the OAuth redirect.
     * The redirect URL contains ?code=AUTH_CODE which we exchange for tokens.
     */
    fun onOAuthCodeReceived(code: String, codeVerifier: String) {
        viewModelScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) {
                _uiState.update { it.copy(isLoading = true) }
            }

            val result = api.exchangeAuthCode(code, codeVerifier)

            if (result.isFailure) {
                val error = result.exceptionOrNull()?.message ?: "Unknown error"
                Log.e("SetupWizard", "OAuth exchange failed: $error")
                withContext(Dispatchers.Main) {
                    _uiState.update {
                        it.copy(isLoading = false, errorModal = "Sign-in failed: $error")
                    }
                }
                return@launch
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
                        _uiState.update { it.copy(isLoading = false) }
                        nextStep() // → Done
                    }
                },
                onFailure = { error ->
                    // Tokens are saved even if vehicle fetch fails — user can retry later
                    credentialStore.markSetupComplete()
                    withContext(Dispatchers.Main) {
                        _uiState.update { it.copy(isLoading = false) }
                        nextStep() // → Done (vehicle will be fetched on HomeScreen)
                    }
                },
            )
        }
    }

    // ── Done ────────────────────────────────────────────

    fun finishSetup() {
        screenResult(true)
    }

    fun dismissError() {
        _uiState.update { it.copy(errorModal = null) }
    }

    fun goBackToCredentials() {
        _uiState.update {
            it.copy(
                step = SetupStep.EnterCredentials,
                errorModal = null,
                registrationStatus = emptyList(),
            )
        }
    }
}
