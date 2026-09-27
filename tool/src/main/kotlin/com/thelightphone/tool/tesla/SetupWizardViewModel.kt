package com.thelightphone.tool.tesla

import android.util.Base64
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
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID

/** Setup wizard steps. */
enum class SetupStep {
    Welcome,
    ManualEntry,
    SignIn,
    Processing,
    Done,
}

/** Which field is currently being edited in ManualEntry. */
enum class EditingField {
    ClientId,
    ClientSecret,
}

data class SetupUiState(
    val step: SetupStep = SetupStep.Welcome,
    val isLoading: Boolean = false,
    val errorModal: String? = null,
    val statusMessages: List<String> = emptyList(),
    val showQrScanner: Boolean = false,
    val setupComplete: Boolean = false,
    /** Tesla OAuth URL for the WebView to load. */
    val oauthUrl: String? = null,
    /** Manual entry field values. */
    val manualClientId: String = "",
    val manualClientSecret: String = "",
    /** Which field the keyboard editor is open for (null = show form). */
    val editingField: EditingField? = null,
)

class SetupWizardViewModel(
    private val credentialStore: CredentialStore,
    private val tokenStore: TokenStore,
    private val api: TeslaApi,
) : LightViewModel<Boolean>() {

    private val _uiState = MutableStateFlow(SetupUiState())
    val uiState: StateFlow<SetupUiState> = _uiState.asStateFlow()

    /** PKCE code verifier — generated fresh each sign-in attempt. */
    private var codeVerifier: String? = null

    /** Session key for resetting the text field state. */
    private var editorSession = 0

    // ── Navigation ──────────────────────────────────────

    fun goToStep(step: SetupStep) {
        _uiState.update {
            it.copy(
                step = step,
                errorModal = null,
                statusMessages = emptyList(),
                oauthUrl = null,
                editingField = null,
            )
        }
    }

    // ── QR scanning ─────────────────────────────────────

    fun showQrScanner() {
        _uiState.update { it.copy(showQrScanner = true) }
    }

    fun hideQrScanner() {
        _uiState.update { it.copy(showQrScanner = false) }
    }

    /**
     * Handles scanned QR codes. The desktop setup page generates one code:
     *
     * Credentials QR: {"t":"cred","i":"<client_id>","s":"<client_secret>"}
     *
     * After scanning, the app opens a WebView for Tesla OAuth sign-in.
     */
    fun onQrScanned(raw: String) {
        try {
            val json = org.json.JSONObject(raw.trim())
            val type = json.optString("t", "")

            when (type) {
                "cred" -> handleCredentialQr(json)
                else -> handleLegacyQr(json)
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

    /** Credentials QR — save and open WebView for Tesla sign-in. */
    private fun handleCredentialQr(json: org.json.JSONObject) {
        val id = json.optString("i", "").trim()
        val secret = json.optString("s", "").trim()

        if (id.isBlank() || secret.isBlank()) {
            _uiState.update {
                it.copy(
                    showQrScanner = false,
                    errorModal = "QR code is missing credentials. Scan the QR code from the setup page.",
                )
            }
            return
        }

        credentialStore.saveClientId(id)
        credentialStore.saveClientSecret(secret)
        Log.i("SetupWizard", "Credentials saved from QR")

        startOAuthFlow(id)
    }

    /** Legacy format with full key names. */
    private fun handleLegacyQr(json: org.json.JSONObject) {
        val id = json.optString("client_id", "").trim()
        val secret = json.optString("client_secret", "").trim()

        if (id.isNotBlank() && secret.isNotBlank()) {
            credentialStore.saveClientId(id)
            credentialStore.saveClientSecret(secret)
            Log.i("SetupWizard", "Credentials saved from legacy QR")
            startOAuthFlow(id)
            return
        }

        _uiState.update {
            it.copy(
                showQrScanner = false,
                errorModal = "Unrecognized QR code. Use the code from tesla-lightphone.app/setup.",
            )
        }
    }

    // ── Manual entry ────────────────────────────────────

    fun startEditing(field: EditingField) {
        editorSession++
        _uiState.update { it.copy(editingField = field) }
    }

    fun cancelEditing() {
        _uiState.update { it.copy(editingField = null) }
    }

    fun submitFieldValue(field: EditingField, value: String) {
        when (field) {
            EditingField.ClientId -> _uiState.update {
                it.copy(manualClientId = value, editingField = null)
            }
            EditingField.ClientSecret -> _uiState.update {
                it.copy(manualClientSecret = value, editingField = null)
            }
        }
    }

    fun getEditorSession(): Int = editorSession

    /** Submit manually entered credentials and start OAuth. */
    fun submitManualCredentials() {
        val id = _uiState.value.manualClientId.trim()
        val secret = _uiState.value.manualClientSecret.trim()

        if (id.isBlank() || secret.isBlank()) {
            _uiState.update {
                it.copy(errorModal = "Both Client ID and Client Secret are required.")
            }
            return
        }

        credentialStore.saveClientId(id)
        credentialStore.saveClientSecret(secret)
        Log.i("SetupWizard", "Credentials saved from manual entry")

        startOAuthFlow(id)
    }

    // ── OAuth via WebView ───────────────────────────────

    /** Generate PKCE parameters and build the Tesla OAuth URL. */
    private fun startOAuthFlow(clientId: String) {
        val verifier = generateCodeVerifier()
        codeVerifier = verifier
        Log.i("SetupWizard", "PKCE verifier generated")
        val challenge = generateCodeChallenge(verifier)
        val url = buildOAuthUrl(clientId, challenge)

        _uiState.update {
            it.copy(
                showQrScanner = false,
                step = SetupStep.SignIn,
                oauthUrl = url,
                editingField = null,
            )
        }
    }

    /** Called when the WebView intercepts the redirect with an auth code. */
    fun onOAuthCodeReceived(code: String) {
        val verifier = codeVerifier
        Log.i("SetupWizard", "OAuth code received")
        if (verifier == null) {
            _uiState.update {
                it.copy(
                    step = SetupStep.Welcome,
                    oauthUrl = null,
                    errorModal = "Sign-in session expired. Please try again.",
                )
            }
            return
        }

        _uiState.update {
            it.copy(
                step = SetupStep.Processing,
                oauthUrl = null,
                isLoading = true,
                statusMessages = listOf(
                    "✓ Credentials saved",
                    "✓ Signed in to Tesla",
                    "Exchanging tokens...",
                ),
            )
        }

        exchangeAndFinish(code, verifier)
    }

    /** Called when the WebView redirect contains an error. */
    fun onOAuthError(error: String) {
        Log.e("SetupWizard", "OAuth error: $error")
        _uiState.update {
            it.copy(
                step = SetupStep.ManualEntry,
                oauthUrl = null,
                errorModal = "Tesla sign-in failed: $error\n\nCheck your credentials and try again.",
            )
        }
    }

    /** Cancel sign-in and return to ManualEntry so user can edit and retry. */
    fun cancelSignIn() {
        codeVerifier = null
        _uiState.update {
            it.copy(
                step = SetupStep.ManualEntry,
                oauthUrl = null,
            )
        }
    }

    // ── PKCE helpers ────────────────────────────────────

    private fun generateCodeVerifier(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(
            bytes,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )
    }

    private fun generateCodeChallenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.encodeToString(
            digest,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )
    }

    private fun buildOAuthUrl(clientId: String, codeChallenge: String): String {
        val params = listOf(
            "response_type" to "code",
            "client_id" to clientId,
            "redirect_uri" to CredentialStore.REDIRECT_URI,
            "scope" to "openid offline_access vehicle_device_data vehicle_cmds",
            "state" to UUID.randomUUID().toString(),
            "code_challenge" to codeChallenge,
            "code_challenge_method" to "S256",
        )
        val query = params.joinToString("&") { (k, v) ->
            "$k=${URLEncoder.encode(v, "UTF-8")}"
        }
        return "https://auth.tesla.com/oauth2/v3/authorize?$query"
    }

    // ── Token exchange ──────────────────────────────────

    private fun exchangeAndFinish(authCode: String, codeVerifier: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val tokenResult = api.exchangeAuthCode(authCode, codeVerifier)

            if (tokenResult.isFailure) {
                val error = tokenResult.exceptionOrNull()?.message ?: "Unknown error"
                Log.e("SetupWizard", "Token exchange failed: $error")
                val userMessage = if (error.contains("unauthorized_client", ignoreCase = true)) {
                    "Your Client Secret appears to be incorrect. Please double-check it on developer.tesla.com. Note: the letters I (uppercase i) and l (lowercase L) can look the same on this screen."
                } else {
                    "Something went wrong connecting to Tesla. Please try again."
                }
                withContext(Dispatchers.Main) {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorModal = userMessage,
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
                            "✓ Signed in to Tesla",
                            "✓ Tokens received",
                            "Finding your vehicles...",
                        ),
                    )
                }
            }

            // Register domain (partner account) — best effort
            try {
                val partnerResult = api.getPartnerToken()
                partnerResult.getOrNull()?.let { partnerToken ->
                    api.registerDomain(partnerToken)
                }
            } catch (e: Exception) {
                Log.w("SetupWizard", "Partner registration skipped: ${e.message}")
            }

            // Fetch vehicles and select the first one
            val vehiclesResult = api.getVehicles()
            vehiclesResult.fold(
                onSuccess = { vehicles ->
                    val vehicle = vehicles.firstOrNull()
                    if (vehicle != null) {
                        tokenStore.saveSelectedVehicle(
                            vehicle.vin,
                            vehicle.displayName ?: "My Tesla",
                        )
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
