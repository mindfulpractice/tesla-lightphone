package com.thelightphone.tool.tesla

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightFullscreenModal
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.LightQrCodeScanner
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextField
import com.thelightphone.sdk.ui.LightTextInputEditor
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.defaultKeyboardOptions
import com.thelightphone.sdk.ui.gridUnitsAsDp
import kotlinx.coroutines.flow.MutableStateFlow

class SetupWizardScreen(sealedActivity: SealedLightActivity) :
    LightScreen<Boolean, SetupWizardViewModel>(sealedActivity) {

    override val viewModelClass: Class<SetupWizardViewModel>
        get() = SetupWizardViewModel::class.java

    override fun createViewModel(): SetupWizardViewModel {
        val credentialStore = CredentialStore(lightContext.filesDir)
        val tokenStore = TokenStore(lightContext.dataStore)
        val api = TeslaApi(tokenStore, credentialStore)
        return SetupWizardViewModel(credentialStore, tokenStore, api)
    }

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val state by viewModel.uiState.collectAsState()

        val keyboardOptionsFlow = remember { MutableStateFlow(defaultKeyboardOptions()) }

        LaunchedEffect(state.setupComplete) {
            if (state.setupComplete) {
                goBack(true)
            }
        }

        LightTheme(colors = themeColors) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                if (state.showQrScanner) {
                    LightQrCodeScanner(
                        title = "Scan QR Code",
                        onScanned = { viewModel.onQrScanned(it) },
                        onBack = { viewModel.hideQrScanner() },
                        modifier = Modifier.background(LightThemeTokens.colors.background),
                    )
                } else if (state.editingField != null) {
                    // Editor at top level of Content (same pattern as Weather app).
                    // key() on editorSession forces a fresh TextFieldState per edit,
                    // so the Client ID text doesn't carry over into Client Secret.
                    val editingField = state.editingField!!
                    val title = when (editingField) {
                        EditingField.ClientId -> "Client ID"
                        EditingField.ClientSecret -> "Client Secret"
                    }
                    val initialValue = when (editingField) {
                        EditingField.ClientId -> state.manualClientId
                        EditingField.ClientSecret -> state.manualClientSecret
                    }
                    val editorSession = viewModel.getEditorSession()

                    key(editorSession) {
                        val textFieldState = rememberTextFieldState(initialValue)

                        LightTextInputEditor(
                            title = title,
                            state = textFieldState,
                            editorKey = editorSession,
                            keyboardOptionsFlow = keyboardOptionsFlow,
                            onSubmit = { result ->
                                viewModel.submitFieldValue(editingField, result.toString())
                            },
                            onBack = { viewModel.cancelEditing() },
                            submitLabel = "DONE",
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxSize()
                                .background(LightThemeTokens.colors.background),
                        )
                    }
                } else {
                    when (state.step) {
                        SetupStep.Welcome -> WelcomeContent()
                        SetupStep.ManualEntry -> ManualEntryContent(state)
                        SetupStep.SignIn -> SignInContent(state)
                        SetupStep.Processing -> ProcessingContent(state)
                        SetupStep.KeyPairing -> KeyPairingContent()
                        SetupStep.Done -> DoneContent()
                    }
                }

                state.errorModal?.let { message ->
                    LightFullscreenModal(
                        message = message,
                        onClose = { viewModel.dismissError() },
                    )
                }
            }
        }
    }

    // ── Welcome ─────────────────────────────────────────

    @Composable
    private fun WelcomeContent() {
        Column(modifier = Modifier.fillMaxSize()) {
            LightTopBar(
                leftButton = LightBarButton.LightIcon(
                    icon = LightIcons.BACK,
                    onClick = { goBack(false) },
                ),
                center = LightTopBarCenter.Text("Setup"),
                modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 1f.gridUnitsAsDp()),
            ) {
                Spacer(modifier = Modifier.height(1f.gridUnitsAsDp()))
                LightText(
                    text = "Visit tesla-lightphone.app/setup for instructions and to get your credentials.",
                    variant = LightTextVariant.Copy,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            LightBottomBar(
                items = listOf(
                    LightBarButton.Text(
                        text = "MANUAL",
                        onClick = { viewModel.goToStep(SetupStep.ManualEntry) },
                    ),
                    LightBarButton.Text(
                        text = "SCAN QR",
                        onClick = { viewModel.showQrScanner() },
                    ),
                ),
            )
        }
    }

    // ── Manual Entry (form only, editor is at Content level) ──

    @Composable
    private fun ManualEntryContent(state: SetupUiState) {
        Column(modifier = Modifier.fillMaxSize()) {
            LightTopBar(
                leftButton = LightBarButton.LightIcon(
                    icon = LightIcons.BACK,
                    onClick = { viewModel.goToStep(SetupStep.Welcome) },
                ),
                center = LightTopBarCenter.Text("Credentials"),
                modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 1f.gridUnitsAsDp()),
            ) {
                LightText(
                    text = "Instructions at tesla-lightphone.app/setup",
                    variant = LightTextVariant.Fine,
                    modifier = Modifier.fillMaxWidth(),
                )

                LightTextField(
                    label = "Client ID",
                    value = state.manualClientId,
                    placeholder = "Tap to enter",
                    onClick = { viewModel.startEditing(EditingField.ClientId) },
                )

                LightTextField(
                    label = "Client Secret",
                    value = state.manualClientSecret,
                    placeholder = "Tap to enter",
                    onClick = { viewModel.startEditing(EditingField.ClientSecret) },
                )
            }

            LightBottomBar(
                items = listOf(
                    null,
                    LightBarButton.Text(
                        text = "CONTINUE",
                        onClick = { viewModel.submitManualCredentials() },
                    ),
                    null,
                ),
            )
        }
    }

    // ── Sign In: WebView with back button ───────────────

    @SuppressLint("SetJavaScriptEnabled")
    @Composable
    private fun SignInContent(state: SetupUiState) {
        val oauthUrl = state.oauthUrl ?: return
        val webViewLoading = remember { mutableStateOf(true) }

        Column(modifier = Modifier.fillMaxSize()) {
            LightTopBar(
                leftButton = LightBarButton.LightIcon(
                    icon = LightIcons.BACK,
                    onClick = { viewModel.cancelSignIn() },
                ),
                center = LightTopBarCenter.Text("Sign In"),
                modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
            )

            // Show "Loading..." while WebView is rendering (prevents black screen)
            if (webViewLoading.value) {
                LightText(
                    text = "Loading Tesla sign-in...",
                    variant = LightTextVariant.Fine,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 1f.gridUnitsAsDp()),
                )
            }

            AndroidView(
                factory = { context ->
                    // Clear old cookies/sessions so a retry with corrected
                    // credentials isn't rejected by stale auth state.
                    val cookieManager = CookieManager.getInstance()
                    cookieManager.setAcceptCookie(true)
                    cookieManager.removeAllCookies(null)
                    cookieManager.flush()

                    WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.setSupportZoom(true)
                        settings.builtInZoomControls = true
                        settings.displayZoomControls = false
                        settings.loadWithOverviewMode = true
                        settings.useWideViewPort = true
                        isFocusable = true
                        isFocusableInTouchMode = true
                        requestFocus()

                        cookieManager.setAcceptThirdPartyCookies(this, true)

                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) {
                                webViewLoading.value = false
                            }

                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?,
                            ): Boolean {
                                val url = request?.url?.toString() ?: return false
                                if (url.startsWith(CredentialStore.REDIRECT_URI)) {
                                    val uri = request.url
                                    val code = uri.getQueryParameter("code")
                                    if (code != null) {
                                        viewModel.onOAuthCodeReceived(code)
                                    } else {
                                        val error =
                                            uri.getQueryParameter("error_description")
                                                ?: uri.getQueryParameter("error")
                                                ?: "Sign-in was not completed"
                                        viewModel.onOAuthError(error)
                                    }
                                    return true
                                }
                                return false
                            }
                        }

                        loadUrl(oauthUrl)
                    }
                },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            )
        }
    }

    // ── Processing ──────────────────────────────────────

    @Composable
    private fun ProcessingContent(state: SetupUiState) {
        Column(modifier = Modifier.fillMaxSize()) {
            LightTopBar(
                center = LightTopBarCenter.Text("Setup"),
                modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 1f.gridUnitsAsDp()),
            ) {
                Spacer(modifier = Modifier.height(1f.gridUnitsAsDp()))

                for (line in state.statusMessages) {
                    LightText(
                        text = line,
                        variant = LightTextVariant.Copy,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 0.25f.gridUnitsAsDp()),
                    )
                }
            }

            if (!state.isLoading) {
                LightBottomBar(
                    items = listOf(
                        null,
                        LightBarButton.Text(
                            text = "TRY AGAIN",
                            onClick = { viewModel.goToStep(SetupStep.ManualEntry) },
                        ),
                        null,
                    ),
                )
            }
        }
    }


    // ── Key Pairing: enroll public key on vehicle ───────

    @SuppressLint("SetJavaScriptEnabled")
    @Composable
    private fun KeyPairingContent() {
        val webViewLoading = remember { mutableStateOf(true) }

        Column(modifier = Modifier.fillMaxSize()) {
            LightTopBar(
                center = LightTopBarCenter.Text("Pair Key"),
                modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
            )

            LightText(
                text = if (webViewLoading.value)
                    "Loading..."
                else
                    "Scan QR with smartphone. Tap CONTINUE after pairing is confirmed.",
                variant = LightTextVariant.Fine,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 1f.gridUnitsAsDp()),
            )

            AndroidView(
                factory = { context ->
                    val cookieManager = CookieManager.getInstance()
                    cookieManager.setAcceptCookie(true)

                    WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.setSupportZoom(true)
                        settings.builtInZoomControls = true
                        settings.displayZoomControls = false
                        settings.loadWithOverviewMode = true
                        settings.useWideViewPort = true
                        // Desktop user-agent so Tesla shows QR code
                        // instead of trying to open the Tesla app
                        settings.userAgentString =
                            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) " +
                            "AppleWebKit/537.36 (KHTML, like Gecko) " +
                            "Chrome/120.0.0.0 Safari/537.36"
                        isFocusable = true
                        isFocusableInTouchMode = true
                        requestFocus()

                        cookieManager.setAcceptThirdPartyCookies(this, true)

                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) {
                                webViewLoading.value = false
                            }

                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?,
                            ): Boolean {
                                val url = request?.url?.toString() ?: return false
                                // Block intent:// and market:// redirects
                                if (url.startsWith("intent://") ||
                                    url.startsWith("market://")) {
                                    return true
                                }
                                return false
                            }
                        }

                        loadUrl("https://tesla.com/_ak/" + CredentialStore.DOMAIN)
                    }
                },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            )

            LightBottomBar(
                items = listOf(
                    null,
                    LightBarButton.Text(
                        text = "CONTINUE",
                        onClick = { viewModel.keyPairingDone() },
                    ),
                    null,
                ),
            )
        }
    }

    // ── Done ────────────────────────────────────────────

    @Composable
    private fun DoneContent() {
        Column(modifier = Modifier.fillMaxSize()) {
            LightTopBar(
                center = LightTopBarCenter.Text("Setup"),
                modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 1f.gridUnitsAsDp()),
            ) {
                Spacer(modifier = Modifier.height(1f.gridUnitsAsDp()))
                LightText(
                    text = "Your Tesla is connected.",
                    variant = LightTextVariant.Copy,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            LightBottomBar(
                items = listOf(
                    null,
                    LightBarButton.Text(
                        text = "GO TO CONTROLS",
                        onClick = { viewModel.finishSetup() },
                    ),
                    null,
                ),
            )
        }
    }
}
