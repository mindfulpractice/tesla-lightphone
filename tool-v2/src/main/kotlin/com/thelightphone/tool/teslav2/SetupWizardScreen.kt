package com.thelightphone.tool.teslav2

import android.net.Uri
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightFullscreenModal
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTextField
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import java.security.MessageDigest
import java.security.SecureRandom

class SetupWizardScreen(sealedActivity: SealedLightActivity) :
    LightScreen<Boolean, SetupWizardViewModel>(sealedActivity) {

    override val viewModelClass: Class<SetupWizardViewModel>
        get() = SetupWizardViewModel::class.java

    override fun createViewModel(): SetupWizardViewModel {
        val credentialStore = CredentialStore(lightContext)
        val tokenStore = TokenStore(lightContext.dataStore)
        val api = TeslaApi(tokenStore, credentialStore)
        return SetupWizardViewModel(credentialStore, tokenStore, api, lightContext)
    }

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val state by viewModel.uiState.collectAsState()

        LightTheme(colors = themeColors) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                when (state.step) {
                    SetupStep.Welcome -> WelcomeContent()
                    SetupStep.DevAccount -> DevAccountContent()
                    SetupStep.EnterCredentials -> CredentialsContent(state)
                    SetupStep.Registering -> RegisteringContent(state)
                    SetupStep.PairKey -> PairKeyContent()
                    SetupStep.SignIn -> SignInContent()
                    SetupStep.Done -> DoneContent()
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

    // ── Step 1: Welcome ─────────────────────────────────

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
                    text = "This app lets you control your Tesla from your Light Phone.",
                    variant = LightTextVariant.Copy,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(1f.gridUnitsAsDp()))
                LightText(
                    text = "You'll need:\n• A Tesla developer account (free)\n• Your car nearby for key pairing",
                    variant = LightTextVariant.Copy,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(1f.gridUnitsAsDp()))
                LightText(
                    text = "Takes about 10 minutes.",
                    variant = LightTextVariant.Copy,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            LightBottomBar {
                LightBarButton(
                    text = "Get Started",
                    onClick = { viewModel.nextStep() },
                )
            }
        }
    }

    // ── Step 2: Developer Account ───────────────────────

    @Composable
    private fun DevAccountContent() {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 1f.gridUnitsAsDp()),
        ) {
            LightTopBar(
                center = LightTopBarCenter.Text("Step 1 of 5"),
                startIcon = LightIcons.ArrowLeft,
                onStartIconClick = { viewModel.goToStep(SetupStep.Welcome) },
            )

            LightScrollView(modifier = Modifier.weight(1f)) {
                Spacer(modifier = Modifier.height(1f.gridUnitsAsDp()))

                LightText(
                    text = "Create a Tesla Developer Account",
                    variant = LightTextVariant.Title,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(1f.gridUnitsAsDp()))
                LightText(
                    text = "Go to developer.tesla.com and create an application.",
                    variant = LightTextVariant.Copy,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(1f.gridUnitsAsDp()))
                LightText(
                    text = "Set the Allowed Origin to:\nhttps://${CredentialStore.DOMAIN}",
                    variant = LightTextVariant.Copy,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(0.5f.gridUnitsAsDp()))
                LightText(
                    text = "Set the Allowed Redirect to:\n${CredentialStore.REDIRECT_URI}",
                    variant = LightTextVariant.Copy,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(1f.gridUnitsAsDp()))
                LightText(
                    text = "Copy your Client ID and Client Secret.",
                    variant = LightTextVariant.Copy,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            LightBottomBar {
                LightBarButton(
                    text = "I have my credentials",
                    onClick = { viewModel.nextStep() },
                )
            }
        }
    }

    // ── Step 3: Enter Credentials ───────────────────────

    @Composable
    private fun CredentialsContent(state: SetupUiState) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 1f.gridUnitsAsDp()),
        ) {
            LightTopBar(
                center = LightTopBarCenter.Text("Step 2 of 5"),
                startIcon = LightIcons.ArrowLeft,
                onStartIconClick = { viewModel.goToStep(SetupStep.DevAccount) },
            )

            LightScrollView(modifier = Modifier.weight(1f)) {
                Spacer(modifier = Modifier.height(1f.gridUnitsAsDp()))

                LightText(
                    text = "Enter Your Credentials",
                    variant = LightTextVariant.Title,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(1f.gridUnitsAsDp()))

                LightText(
                    text = "Client ID",
                    variant = LightTextVariant.Label,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(0.25f.gridUnitsAsDp()))
                LightTextField(
                    value = state.clientId,
                    onValueChange = { viewModel.updateClientId(it) },
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(modifier = Modifier.height(1f.gridUnitsAsDp()))

                LightText(
                    text = "Client Secret",
                    variant = LightTextVariant.Label,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(0.25f.gridUnitsAsDp()))
                LightTextField(
                    value = state.clientSecret,
                    onValueChange = { viewModel.updateClientSecret(it) },
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(modifier = Modifier.height(1f.gridUnitsAsDp()))
                LightText(
                    text = "These stay on your device. They are never sent to anyone except Tesla.",
                    variant = LightTextVariant.Caption,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            LightBottomBar {
                LightBarButton(
                    text = "Save",
                    onClick = { viewModel.saveCredentials() },
                )
            }
        }
    }

    // ── Step 4: Auto Registration ───────────────────────

    @Composable
    private fun RegisteringContent(state: SetupUiState) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 1f.gridUnitsAsDp()),
        ) {
            LightTopBar(center = LightTopBarCenter.Text("Step 3 of 5"))

            LightScrollView(modifier = Modifier.weight(1f)) {
                Spacer(modifier = Modifier.height(1f.gridUnitsAsDp()))

                LightText(
                    text = "Registering with Tesla...",
                    variant = LightTextVariant.Title,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(1f.gridUnitsAsDp()))

                for (line in state.registrationStatus) {
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
                        variant = LightTextVariant.Caption,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            // Show retry button if there was an error
            if (!state.isLoading && state.errorModal != null) {
                LightBottomBar {
                    LightBarButton(
                        text = "Edit Credentials",
                        onClick = { viewModel.goBackToCredentials() },
                    )
                }
            }
        }
    }

    // ── Step 5: Pair Virtual Key ────────────────────────

    @Composable
    private fun PairKeyContent() {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 1f.gridUnitsAsDp()),
        ) {
            LightTopBar(center = LightTopBarCenter.Text("Step 4 of 5"))

            LightScrollView(modifier = Modifier.weight(1f)) {
                Spacer(modifier = Modifier.height(1f.gridUnitsAsDp()))

                LightText(
                    text = "Pair Your Car",
                    variant = LightTextVariant.Title,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(1f.gridUnitsAsDp()))
                LightText(
                    text = "Tap the button below. Your Tesla smartphone app will ask you to approve a virtual key.",
                    variant = LightTextVariant.Copy,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(1f.gridUnitsAsDp()))
                LightText(
                    text = "Make sure you're near your car and have the Tesla app on your smartphone.",
                    variant = LightTextVariant.Caption,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            LightBottomBar {
                LightBarButton(
                    text = "Pair Vehicle",
                    onClick = {
                        // Open Tesla's key pairing URL in the system browser
                        val intent = android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            Uri.parse("https://tesla.com/_ak/${CredentialStore.DOMAIN}"),
                        )
                        lightContext.startActivity(intent)
                    },
                )
            }

            // "Next" button appears below for after pairing is done
            Spacer(modifier = Modifier.height(0.5f.gridUnitsAsDp()))
            LightText(
                text = "After approving in the Tesla app, tap Next.",
                variant = LightTextVariant.Caption,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 1f.gridUnitsAsDp())
                    .lightClickable { viewModel.nextStep() },
            )
        }
    }

    // ── Step 6: OAuth Sign In (WebView) ─────────────────

    @Composable
    private fun SignInContent() {
        // Generate PKCE values for this auth session
        var codeVerifier by remember { mutableStateOf(generateCodeVerifier()) }
        val codeChallenge = generateCodeChallenge(codeVerifier)

        val clientId = viewModel.uiState.collectAsState().value.clientId.ifBlank {
            // Fall back to stored credential
            CredentialStore(lightContext).getClientId() ?: ""
        }
        val redirectUri = CredentialStore.REDIRECT_URI

        val authUrl = "https://auth.tesla.com/oauth2/v3/authorize" +
            "?client_id=$clientId" +
            "&redirect_uri=${Uri.encode(redirectUri)}" +
            "&response_type=code" +
            "&scope=openid+vehicle_device_data+vehicle_cmds+vehicle_charging_cmds+offline_access" +
            "&code_challenge=$codeChallenge" +
            "&code_challenge_method=S256" +
            "&state=setup"

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 1f.gridUnitsAsDp()),
        ) {
            LightTopBar(center = LightTopBarCenter.Text("Step 5 of 5"))

            LightText(
                text = "Sign In with Tesla",
                variant = LightTextVariant.Title,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 0.5f.gridUnitsAsDp()),
            )

            // In-app WebView for Tesla OAuth
            AndroidView(
                factory = { context ->
                    WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?,
                            ): Boolean {
                                val url = request?.url?.toString() ?: return false

                                // Intercept the redirect URI
                                if (url.startsWith(redirectUri)) {
                                    val uri = Uri.parse(url)
                                    val code = uri.getQueryParameter("code")
                                    if (code != null) {
                                        viewModel.onOAuthCodeReceived(code, codeVerifier)
                                    } else {
                                        val error = uri.getQueryParameter("error")
                                        viewModel.dismissError()
                                    }
                                    return true
                                }
                                return false
                            }
                        }
                        loadUrl(authUrl)
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )
        }
    }

    // ── Step 7: Done ────────────────────────────────────

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

            LightBottomBar {
                LightBarButton(
                    text = "Go to Controls",
                    onClick = { viewModel.finishSetup() },
                )
            }
        }
    }

    // ── PKCE helpers ────────────────────────────────────

    private fun generateCodeVerifier(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return android.util.Base64.encodeToString(bytes, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
    }

    private fun generateCodeChallenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return android.util.Base64.encodeToString(digest, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
    }
}
