package com.thelightphone.tool.tesla

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.thelightphone.sdk.LightQrCodeScanner
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightFullscreenModal
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.gridUnitsAsDp

class TokenSetupScreen(sealedActivity: SealedLightActivity) :
    LightScreen<Boolean, TokenSetupViewModel>(sealedActivity) {

    override val viewModelClass: Class<TokenSetupViewModel>
        get() = TokenSetupViewModel::class.java

    override fun createViewModel(): TokenSetupViewModel {
        val tokenStore = TokenStore(lightContext.dataStore)
        val api = TeslaApi(tokenStore)
        return TokenSetupViewModel(tokenStore, api)
    }

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val state by viewModel.uiState.collectAsState()
        var pendingScan by remember { mutableStateOf<String?>(null) }

        // Auto-load from debug file on screen launch (emulator/adb testing)
        // Write via: adb shell run-as com.thelightphone.tool.tesla sh -c 'echo "PAYLOAD" > files/tesla_auth.txt'
        LaunchedEffect(Unit) {
            try {
                val file = java.io.File(lightContext.filesDir, "tesla_auth.txt")
                if (file.exists()) {
                    val content = file.readText().trim()
                    if (content.isNotEmpty()) {
                        android.util.Log.d("TokenSetup", "Loaded auth from ${file.path}")
                        pendingScan = content
                        file.delete()
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("TokenSetup", "Can't read debug file: ${e.message}")
            }
        }

        LightTheme(colors = themeColors) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                when {
                    state.isLoading -> {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 1f.gridUnitsAsDp()),
                        ) {
                            LightText(
                                text = "Connecting to Tesla...",
                                variant = LightTextVariant.Copy,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 2f.gridUnitsAsDp()),
                            )
                        }
                    }

                    else -> {
                        LightQrCodeScanner(
                            title = "Scan QR Code",
                            onScanned = { pendingScan = it },
                            onBack = { goBack(false) },
                            modifier = Modifier.background(LightThemeTokens.colors.background),
                        )
                    }
                }

                LaunchedEffect(pendingScan) {
                    val raw = pendingScan ?: return@LaunchedEffect
                    if (raw.isBlank()) return@LaunchedEffect

                    viewModel.onSubmit(raw) { success ->
                        if (success) {
                            goBack(true)
                        } else {
                            pendingScan = null
                        }
                    }
                }

                state.errorModal?.let { message ->
                    LightFullscreenModal(
                        message = message,
                        onClose = {
                            viewModel.dismissError()
                            pendingScan = null
                        },
                    )
                }
            }
        }
    }
}
