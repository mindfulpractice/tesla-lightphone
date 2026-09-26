package com.thelightphone.tool.teslav2

import android.annotation.SuppressLint
import android.net.Uri
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightFullscreenModal
import com.thelightphone.sdk.LightQrCodeScanner
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp

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

        // When setup is complete, return true to HomeScreen
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
                // Show QR scanner full-screen when active
                if (state.showQrScanner) {
                    LightQrCodeScanner(
                        title = "Scan Credentials QR",
                        onScanned = { viewModel.onQrScanned(it) },
                        onBack = { viewModel.hideQrScanner() },
                        modifier = Modifier.background(LightThemeTokens.colors.background),
                    )
                } else {
                    when (state.step) {
                        SetupStep.Welcome -> WelcomeContent()
                        SetupStep.SignIn -> SignInContent(state)
                        SetupStep.Processing -> ProcessingContent(state)
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

    // ── Welcome: instructions + Scan QR ─────────────────

    @Composable
    private fun WelcomeContent() {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 1f.gridUnitsAsDp()),
        ) {
            LightTopBar(center = LightTopBarCenter.Text("Setup"))

            LightScrollView(modifier = Modifier.weight(1f)) {
                Spacer(modifier = Modifier.height(1f.gridUnitsAsDp()))

                LightText(
                    text = "Tesla Control Setup",
                    variant = LightTextVariant.Title,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(1f.gridUnitsAsDp()))
                LightText(
                    text = "Set up your credentials on a computer, then scan the QR code here to sign in.",
                    variant = LightTextVariant.Copy,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(1f.gridUnitsAsDp()))
                LightText(
                    text = "1. On a computer, go to\ntesla-lightphone.app/setup",
                    variant = LightTextVariant.Copy,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(0.5f.gridUnitsAsDp()))
                LightText(
                    text = "2. Follow the steps to create your Tesla developer app",
                    variant = LightTextVariant.Copy,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(0.5f.gridUnitsAsDp()))
                LightText(
                    text = "3. Enter your credentials and scan the QR code it shows",
                    variant = LightTextVariant.Copy,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(1f.gridUnitsAsDp()))
                LightText(
                    text = "After scanning, you'll sign in to Tesla directly on this phone.",
                    variant = LightTextVariant.Detail,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            LightBottomBar(
                items = listOf(
                    null,
                    LightBarButton.Text(
                        text = "SCAN QR CODE",
                        onClick = { viewModel.showQrScanner() },
                    ),
                    null,
                ),
            )
        }
    }

    // ── Sign In: full-screen WebView for Tesla OAuth ────

    @SuppressLint("SetJavaScriptEnabled")
    @Composable
    private fun SignInContent(state: SetupUiState) {
        val oauthUrl = state.oauthUrl ?: return

        // Full-screen WebView — no title, no header, no bottom bar.
        // This gives maximum room for the Tesla sign-in page
        // so the user can scroll to all fields and the keyboard
        // doesn't block the inputs.
        AndroidView(
            factory = { context ->
                // Enable cookies for Tesla auth session
                CookieManager.getInstance().setAcceptCookie(true)

                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.setSupportZoom(true)
                    settings.builtInZoomControls = true
                    settings.displayZoomControls = false
                    settings.loadWithOverviewMode = true
                    settings.useWideViewPort = true

                    // Ensure the WebView can receive focus for keyboard input
                    isFocusable = true
                    isFocusableInTouchMode = true
                    requestFocus()

                    // Accept third-party cookies (needed for Tesla OAuth)
                    CookieManager.getInstance()
                        .setAcceptThirdPartyCookies(this, true)

                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?,
                        ): Boolean {
                            val url = request?.url?.toString() ?: return false
                            // Intercept redirect to our domain
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
            modifier = Modifier.fillMaxSize(),
        )
    }

    // ── Processing: exchanging tokens ───────────────────

    @Composable
    private fun ProcessingContent(state: SetupUiState) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 1f.gridUnitsAsDp()),
        ) {
            LightTopBar(center = LightTopBarCenter.Text("Setup"))

            LightScrollView(modifier = Modifier.weight(1f)) {
                Spacer(modifier = Modifier.height(2f.gridUnitsAsDp()))

                LightText(
                    text = "Setting up...",
                    variant = LightTextVariant.Title,
                    modifier = Modifier.fillMaxWidth(),
                )
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

                if (state.isLoading) {
                    Spacer(modifier = Modifier.height(1f.gridUnitsAsDp()))
                    LightText(
                        text = "This takes a few seconds.",
                        variant = LightTextVariant.Fine,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            if (!state.isLoading && state.errorModal != null) {
                LightBottomBar(
                    items = listOf(
                        null,
                        LightBarButton.Text(
                            text = "TRY AGAIN",
                            onClick = { viewModel.goToStep(SetupStep.Welcome) },
                        ),
                        null,
                    ),
                )
            }
        }
    }

    // ── Done ────────────────────────────────────────────

    @Composable
    private fun DoneContent() {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 1f.gridUnitsAsDp()),
        ) {
            LightTopBar(center = LightTopBarCenter.Text("Done"))

            LightScrollView(modifier = Modifier.weight(1f)) {
                Spacer(modifier = Modifier.height(2f.gridUnitsAsDp()))

                LightText(
                    text = "Setup Complete!",
                    variant = LightTextVariant.Title,
                    modifier = Modifier.fillMaxWidth(),
                )
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
