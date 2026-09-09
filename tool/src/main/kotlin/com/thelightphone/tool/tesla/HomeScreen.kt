package com.thelightphone.tool.tesla

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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Modifier
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightFullscreenModal
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable

@InitialScreen
class HomeScreen(sealedActivity: SealedLightActivity) :
    LightScreen<Unit, HomeScreenViewModel>(sealedActivity) {

    override val viewModelClass: Class<HomeScreenViewModel>
        get() = HomeScreenViewModel::class.java

    override fun createViewModel(): HomeScreenViewModel {
        val tokenStore = TokenStore(lightContext.dataStore)
        val api = TeslaApi(tokenStore)

        // Load or generate VCP key pair
        try {
            val privKeyFile = java.io.File(lightContext.filesDir, "tesla_private_key.pem")
            val pubKeyFile = java.io.File(lightContext.filesDir, "tesla_public_key.pem")

            if (!privKeyFile.exists() || !pubKeyFile.exists()) {
                // Generate a new EC P-256 key pair on first launch
                val kpg = java.security.KeyPairGenerator.getInstance("EC")
                kpg.initialize(java.security.spec.ECGenParameterSpec("secp256r1"))
                val kp = kpg.generateKeyPair()

                val privPem = "-----BEGIN PRIVATE KEY-----\n" +
                    android.util.Base64.encodeToString(kp.private.encoded, android.util.Base64.NO_WRAP)
                        .chunked(64).joinToString("\n") +
                    "\n-----END PRIVATE KEY-----\n"
                val pubPem = "-----BEGIN PUBLIC KEY-----\n" +
                    android.util.Base64.encodeToString(kp.public.encoded, android.util.Base64.NO_WRAP)
                        .chunked(64).joinToString("\n") +
                    "\n-----END PUBLIC KEY-----\n"

                privKeyFile.writeText(privPem)
                pubKeyFile.writeText(pubPem)
                android.util.Log.i("HomeScreen", "Generated new VCP key pair")
            }

            api.loadKeyPair(privKeyFile.readBytes(), pubKeyFile.readBytes())
            api.resetSessions()
        } catch (e: Exception) {
            android.util.Log.e("HomeScreen", "Failed to load/generate VCP keys: ${e.message}")
        }

        return HomeScreenViewModel(tokenStore, api)
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
                when (state.mode) {
                    is TeslaScreenMode.NeedsSetup -> {
                        SetupPromptContent(
                            onSetup = {
                                navigateTo(::TokenSetupScreen) { result ->
                                    if (result == true) {
                                        viewModel.onTokenSaved()
                                    }
                                }
                            },
                        )
                    }

                    is TeslaScreenMode.Controls -> {
                        val controls = state.mode as TeslaScreenMode.Controls
                        ControlsContent(
                            vehicleName = controls.vehicleName,
                            vehicleState = state.vehicleState,
                            isLoading = state.isLoading,
                            isRefreshing = state.isRefreshing,
                            activeCommand = state.activeCommand,
                            startSecondsLeft = state.startSecondsLeft,
                            controlSettings = state.controlSettings,
                            units = state.units,
                            onCommand = viewModel::executeCommand,
                            onRefresh = viewModel::fetchVehicleState,
                            // onPairKey — enroll key via tesla-control CLI instead
                            onOpenSettings = {
                                navigateTo(::SettingsScreen) { result ->
                                    if (result == true) {
                                        viewModel.reloadControls()
                                    } else {
                                        // Disconnected — reset to setup
                                        viewModel.onTokenSaved()
                                    }
                                }
                            },
                        )
                    }
                }

                state.errorModal?.let { message ->
                    LightFullscreenModal(
                        message = message,
                        onClose = viewModel::dismissError,
                    )
                }
            }
        }
    }
}

@Composable
private fun SetupPromptContent(onSetup: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        LightTopBar(
            center = LightTopBarCenter.Text("Tesla"),
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 1f.gridUnitsAsDp()),
        ) {
            Spacer(modifier = Modifier.height(2f.gridUnitsAsDp()))
            LightText(
                text = "Connect your Tesla to get started.",
                variant = LightTextVariant.Copy,
            )
            Spacer(modifier = Modifier.height(1f.gridUnitsAsDp()))
            LightText(
                text = "On another device, visit:",
                variant = LightTextVariant.Fine,
            )
            Spacer(modifier = Modifier.height(0.5f.gridUnitsAsDp()))
            LightText(
                text = BuildConfig.TESLA_REDIRECT_URI
                    .removePrefix("https://").removePrefix("http://").trimEnd('/'),
                variant = LightTextVariant.Copy,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 0.75f.gridUnitsAsDp()),
            )
            LightText(
                text = "Sign in with Tesla, then scan the QR code.",
                variant = LightTextVariant.Fine,
            )
        }

        LightBottomBar(
            items = listOf(
                null,
                LightBarButton.Text(
                    text = "SCAN QR CODE",
                    onClick = onSetup,
                ),
                null,
            ),
        )
    }
}

@Composable
private fun ControlsContent(
    vehicleName: String,
    vehicleState: VehicleState?,
    isLoading: Boolean,
    isRefreshing: Boolean,
    activeCommand: TeslaCommand?,
    startSecondsLeft: Int?,
    controlSettings: List<ControlSetting>,
    units: UnitSystem,
    onCommand: (TeslaCommand) -> Unit,
    onRefresh: () -> Unit,
    onOpenSettings: () -> Unit,
    onPairKey: (() -> Unit)? = null,
) {
    // Ticking clock so timestamp updates over time
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000) // tick every 30 seconds
            now = System.currentTimeMillis()
        }
    }

    val timestampText = when {
        isRefreshing -> "..."
        vehicleState != null -> {
            val diff = (now - vehicleState.timestamp) / 1000
            when {
                diff < 60 -> "just now"
                diff < 3600 -> "${diff / 60}m ago"
                else -> "${diff / 3600}h ago"
            }
        }
        else -> "refresh"
    }

    var statusExpanded by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        // ── Scrollable controls (data-driven) ───
        LightScrollView(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 1f.gridUnitsAsDp()),
        ) {
            // Key pairing button — visible until key is fully paired
            if (onPairKey != null) {
                LightText(
                    text = "Pair Key",
                    variant = LightTextVariant.Copy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .lightClickable(onClick = onPairKey)
                        .padding(vertical = 0.75f.gridUnitsAsDp()),
                )
            }

            controlSettings.filter { it.visible }.forEach { setting ->
                if (setting.id == ControlId.Status) {
                    StatusBlock(
                        vehicleState = vehicleState,
                        isExpanded = statusExpanded,
                        isRefreshing = isRefreshing,
                        units = units,
                        onClick = { statusExpanded = !statusExpanded },
                    )
                } else {
                    ControlBlock(
                        id = setting.id,
                        vehicleState = vehicleState,
                        activeCommand = activeCommand,
                        startSecondsLeft = startSecondsLeft,
                        isLoading = isLoading,
                        units = units,
                        onCommand = onCommand,
                    )
                }
            }
        }

        LightBottomBar(
            items = listOf(
                LightBarButton.LightIcon(
                    icon = LightIcons.SETTINGS,
                    onClick = onOpenSettings,
                ),
                LightBarButton.Text(
                    text = timestampText,
                    onClick = null,
                ),
                LightBarButton.LightIcon(
                    icon = LightIcons.REFRESH,
                    onClick = { if (!isRefreshing) onRefresh() },
                ),
            ),
        )
    }
}


/** A single tappable state line — Copy variant per LP conventions. */
@Composable
private fun StateBlock(
    text: String,
    onClick: () -> Unit,
) {
    LightText(
        text = text,
        variant = LightTextVariant.Copy,
        modifier = Modifier
            .fillMaxWidth()
            .lightClickable(onClick = onClick)
            .padding(vertical = 0.75f.gridUnitsAsDp()),
    )
}

/** Status row — toggles between "Status" label and one-line vitals summary. */
@Composable
private fun StatusBlock(
    vehicleState: VehicleState?,
    isExpanded: Boolean,
    isRefreshing: Boolean,
    units: UnitSystem,
    onClick: () -> Unit,
) {
    val text = when {
        isRefreshing -> "..."
        !isExpanded -> "Status"
        else -> buildVitalsLine(vehicleState, units) ?: "Status"
    }
    val variant = if (isExpanded && vehicleState != null) LightTextVariant.Fine else LightTextVariant.Copy
    LightText(
        text = text,
        variant = variant,
        modifier = Modifier
            .fillMaxWidth()
            .lightClickable(onClick = onClick)
            .padding(vertical = 0.75f.gridUnitsAsDp()),
    )
}

private fun buildVitalsLine(vs: VehicleState?, units: UnitSystem): String? {
    if (vs == null) return null
    val parts = mutableListOf<String>()
    vs.batteryLevel?.let { parts.add("$it%") }
    vs.batteryRange?.let { range ->
        when (units) {
            UnitSystem.Imperial -> parts.add("${range.toInt()} mi")
            UnitSystem.Metric -> parts.add("${(range * 1.60934).toInt()} km")
        }
    }
    vs.insideTemp?.let { tempC ->
        when (units) {
            UnitSystem.Imperial -> parts.add("${(tempC * 9.0 / 5.0 + 32).toInt()}°F")
            UnitSystem.Metric -> parts.add("${tempC.toInt()}°C")
        }
    }
    if (!vs.locked) parts.add("Unlocked")
    if (vs.climateOn) parts.add("Climate")
    if (vs.sentryMode) parts.add("Sentry")
    if (vs.chargingState == "Charging") parts.add("Charging")
    return if (parts.isNotEmpty()) parts.joinToString(" · ") else null
}

/** Renders a single control by its ControlId. */
@Composable
private fun ControlBlock(
    id: ControlId,
    vehicleState: VehicleState?,
    activeCommand: TeslaCommand?,
    startSecondsLeft: Int?,
    isLoading: Boolean,
    units: UnitSystem,
    onCommand: (TeslaCommand) -> Unit,
) {
    when (id) {
        ControlId.Status -> { /* handled separately in ControlsContent */ }
        ControlId.Lock -> StateBlock(
            text = when {
                activeCommand == TeslaCommand.Lock -> "Locking..."
                activeCommand == TeslaCommand.Unlock -> "Unlocking..."
                vehicleState == null -> "Locked"
                vehicleState.locked -> "Locked"
                else -> "Unlocked"
            },
            onClick = {
                if (!isLoading) {
                    if (vehicleState?.locked == false) onCommand(TeslaCommand.Lock)
                    else onCommand(TeslaCommand.Unlock)
                }
            },
        )

        ControlId.Climate -> StateBlock(
            text = when {
                activeCommand == TeslaCommand.ClimateOn || activeCommand == TeslaCommand.ClimateOff -> "..."
                vehicleState == null -> "Climate"
                vehicleState.climateOn -> {
                    val tempStr = vehicleState.insideTemp?.let { temp ->
                        when (units) {
                            UnitSystem.Imperial -> " ${(temp * 9.0 / 5.0 + 32).toInt()}°"
                            UnitSystem.Metric -> " ${temp.toInt()}°"
                        }
                    } ?: ""
                    "Climate on$tempStr"
                }
                else -> "Climate off"
            },
            onClick = {
                if (!isLoading) {
                    if (vehicleState?.climateOn == true) onCommand(TeslaCommand.ClimateOff)
                    else onCommand(TeslaCommand.ClimateOn)
                }
            },
        )

        ControlId.Defrost -> StateBlock(
            text = when {
                activeCommand == TeslaCommand.DefrostOn || activeCommand == TeslaCommand.DefrostOff -> "..."
                vehicleState == null -> "Defrost"
                vehicleState.defrostOn -> "Defrost on"
                else -> "Defrost off"
            },
            onClick = {
                if (!isLoading) {
                    if (vehicleState?.defrostOn == true) onCommand(TeslaCommand.DefrostOff)
                    else onCommand(TeslaCommand.DefrostOn)
                }
            },
        )

        ControlId.Overheat -> StateBlock(
            text = when {
                activeCommand == TeslaCommand.OverheatOff
                    || activeCommand == TeslaCommand.OverheatFan
                    || activeCommand == TeslaCommand.OverheatAC -> "..."
                vehicleState == null -> "Overheat"
                else -> when (vehicleState.overheatMode) {
                    OverheatMode.Off -> "Overheat off"
                    OverheatMode.FanOnly -> "Overheat fan"
                    OverheatMode.AC -> "Overheat AC"
                }
            },
            onClick = {
                if (!isLoading) {
                    val next = when (vehicleState?.overheatMode) {
                        OverheatMode.Off, null -> TeslaCommand.OverheatFan
                        OverheatMode.FanOnly -> TeslaCommand.OverheatAC
                        OverheatMode.AC -> TeslaCommand.OverheatOff
                    }
                    onCommand(next)
                }
            },
        )

        ControlId.Start -> StateBlock(
            text = when {
                activeCommand == TeslaCommand.RemoteStart -> "..."
                startSecondsLeft != null && startSecondsLeft > 0 -> {
                    val m = startSecondsLeft / 60
                    val s = startSecondsLeft % 60
                    "Started $m:%02d".format(s)
                }
                vehicleState == null -> "Start"
                else -> "Start"
            },
            onClick = { if (!isLoading) onCommand(TeslaCommand.RemoteStart) },
        )

        ControlId.Trunk -> StateBlock(
            text = when {
                activeCommand == TeslaCommand.OpenTrunk || activeCommand == TeslaCommand.CloseTrunk -> "..."
                vehicleState == null -> "Trunk"
                vehicleState.trunkOpen -> "Trunk open"
                else -> "Trunk closed"
            },
            onClick = {
                if (!isLoading) {
                    val cmd = if (vehicleState?.trunkOpen == true) TeslaCommand.CloseTrunk else TeslaCommand.OpenTrunk
                    onCommand(cmd)
                }
            },
        )

        ControlId.Frunk -> StateBlock(
            text = when {
                activeCommand == TeslaCommand.OpenFrunk -> "..."
                vehicleState == null -> "Frunk"
                vehicleState.frunkOpen -> "Frunk open"
                else -> "Frunk closed"
            },
            onClick = { if (!isLoading) onCommand(TeslaCommand.OpenFrunk) },
        )

        ControlId.ChargePort -> StateBlock(
            text = when {
                activeCommand == TeslaCommand.ChargePortOpen
                    || activeCommand == TeslaCommand.ChargePortClose -> "..."
                vehicleState == null -> "Port"
                vehicleState.chargePortOpen -> "Port open"
                else -> "Port closed"
            },
            onClick = {
                if (!isLoading) {
                    if (vehicleState?.chargePortOpen == true) onCommand(TeslaCommand.ChargePortClose)
                    else onCommand(TeslaCommand.ChargePortOpen)
                }
            },
        )

        ControlId.Sentry -> StateBlock(
            text = when {
                activeCommand == TeslaCommand.SentryOn
                    || activeCommand == TeslaCommand.SentryOff -> "..."
                vehicleState == null -> "Sentry"
                vehicleState.sentryMode -> "Sentry on"
                else -> "Sentry off"
            },
            onClick = {
                if (!isLoading) {
                    if (vehicleState?.sentryMode == true) onCommand(TeslaCommand.SentryOff)
                    else onCommand(TeslaCommand.SentryOn)
                }
            },
        )

        ControlId.Windows -> StateBlock(
            text = when {
                activeCommand == TeslaCommand.VentWindows
                    || activeCommand == TeslaCommand.CloseWindows -> "..."
                vehicleState == null -> "Windows"
                vehicleState.windowsOpen -> "Windows open"
                else -> "Windows closed"
            },
            onClick = {
                if (!isLoading) {
                    if (vehicleState?.windowsOpen == true) onCommand(TeslaCommand.CloseWindows)
                    else onCommand(TeslaCommand.VentWindows)
                }
            },
        )

        ControlId.Flash -> StateBlock(
            text = if (activeCommand == TeslaCommand.FlashLights) "..." else "Flash",
            onClick = { if (!isLoading) onCommand(TeslaCommand.FlashLights) },
        )

        ControlId.Horn -> StateBlock(
            text = if (activeCommand == TeslaCommand.HonkHorn) "..." else "Horn",
            onClick = { if (!isLoading) onCommand(TeslaCommand.HonkHorn) },
        )
    }
}

// ── Models ────────────────────────────────────────────────

enum class TeslaCommand {
    Lock, Unlock, RemoteStart, ClimateOn, ClimateOff,
    DefrostOn, DefrostOff, OverheatOff, OverheatFan, OverheatAC,
    OpenTrunk, CloseTrunk, OpenFrunk, ChargePortOpen, ChargePortClose,
    SentryOn, SentryOff, FlashLights, HonkHorn,
    VentWindows, CloseWindows,
}

sealed class TeslaScreenMode {
    data object NeedsSetup : TeslaScreenMode()
    data class Controls(val vehicleName: String) : TeslaScreenMode()
}

data class TeslaUiState(
    val mode: TeslaScreenMode = TeslaScreenMode.NeedsSetup,
    val vehicleState: VehicleState? = null,
    val statusMessage: String? = null,
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val activeCommand: TeslaCommand? = null,
    val errorModal: String? = null,
    val startSecondsLeft: Int? = null,
    val controlSettings: List<ControlSetting> = DEFAULT_CONTROLS,
    val units: UnitSystem = UnitSystem.Imperial,
)
