package com.thelightphone.tool.tesla

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import kotlinx.coroutines.launch
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcon
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

class SettingsScreen(sealedActivity: SealedLightActivity) :
    LightScreen<Boolean, SettingsViewModel>(sealedActivity) {

    override val viewModelClass: Class<SettingsViewModel>
        get() = SettingsViewModel::class.java

    override fun createViewModel(): SettingsViewModel {
        val tokenStore = TokenStore(lightContext.dataStore)
        return SettingsViewModel(tokenStore)
    }

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val state by viewModel.uiState.collectAsState()
        val scope = rememberCoroutineScope()

        LightTheme(colors = themeColors) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                when (state.page) {
                    SettingsPage.Root -> RootPage(
                        vehicleName = state.vehicleName,
                        vin = state.vin,
                        units = state.units,
                        onControls = { viewModel.navigateTo(SettingsPage.Controls) },
                        onOrder = { viewModel.navigateTo(SettingsPage.Order) },
                        onToggleUnits = viewModel::toggleUnits,
                        onDisconnect = {
                            scope.launch {
                                viewModel.disconnect()
                                goBack(false)
                            }
                        },
                        onBack = { goBack(true) },
                    )

                    SettingsPage.Controls -> ControlsPage(
                        controls = state.controls,
                        onToggle = viewModel::toggleVisibility,
                        onBack = { viewModel.navigateTo(SettingsPage.Root) },
                    )

                    SettingsPage.Order -> OrderPage(
                        controls = state.controls.filter { it.visible },
                        onMoveUp = viewModel::moveUp,
                        onMoveDown = viewModel::moveDown,
                        onBack = { viewModel.navigateTo(SettingsPage.Root) },
                    )
                }
            }
        }
    }
}

// ── Root page ───────────────────────────────────────────

@Composable
private fun RootPage(
    vehicleName: String,
    vin: String,
    units: UnitSystem,
    onControls: () -> Unit,
    onOrder: () -> Unit,
    onToggleUnits: () -> Unit,
    onDisconnect: () -> Unit,
    onBack: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        LightTopBar(
            leftButton = LightBarButton.LightIcon(
                icon = LightIcons.BACK,
                onClick = onBack,
            ),
            center = LightTopBarCenter.Text("Settings"),
            modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 1f.gridUnitsAsDp()),
        ) {
            // Units toggle
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .lightClickable(onClick = onToggleUnits)
                    .padding(vertical = 0.75f.gridUnitsAsDp()),
            ) {
                LightText(
                    text = "Units",
                    variant = LightTextVariant.Detail,
                )
                LightText(
                    text = units.displayLabel(),
                    variant = LightTextVariant.Copy,
                )
            }

            LightText(
                text = "Controls",
                variant = LightTextVariant.Copy,
                modifier = Modifier
                    .fillMaxWidth()
                    .lightClickable(onClick = onControls)
                    .padding(vertical = 0.75f.gridUnitsAsDp()),
            )
            LightText(
                text = "Order",
                variant = LightTextVariant.Copy,
                modifier = Modifier
                    .fillMaxWidth()
                    .lightClickable(onClick = onOrder)
                    .padding(vertical = 0.75f.gridUnitsAsDp()),
            )

            Spacer(modifier = Modifier.height(2f.gridUnitsAsDp()))

            LightText(
                text = "Disconnect",
                variant = LightTextVariant.Copy,
                modifier = Modifier
                    .fillMaxWidth()
                    .lightClickable(onClick = onDisconnect)
                    .padding(vertical = 0.75f.gridUnitsAsDp()),
            )
            if (vehicleName.isNotEmpty()) {
                LightText(
                    text = if (vin.isNotEmpty()) "$vehicleName · ${vin.takeLast(6)}" else vehicleName,
                    variant = LightTextVariant.Superfine,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

        }
    }
}

// ── Controls page (toggle on/off) ───────────────────────

@Composable
private fun ControlsPage(
    controls: List<ControlSetting>,
    onToggle: (Int) -> Unit,
    onBack: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        LightTopBar(
            leftButton = LightBarButton.LightIcon(
                icon = LightIcons.BACK,
                onClick = onBack,
            ),
            center = LightTopBarCenter.Text("Controls"),
            modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
        )

        LightScrollView(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 1f.gridUnitsAsDp()),
        ) {
            controls.forEachIndexed { index, control ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .lightClickable(onClick = { onToggle(index) })
                        .padding(vertical = 0.75f.gridUnitsAsDp()),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LightIcon(
                        icon = if (control.visible) LightIcons.TOGGLE_STATE_ON else LightIcons.TOGGLE_STATE_OFF,
                        size = 2f,
                    )
                    Spacer(modifier = Modifier.width(0.75f.gridUnitsAsDp()))
                    LightText(
                        text = control.id.label,
                        variant = LightTextVariant.Copy,
                    )
                }
            }
        }
    }
}

// ── Order page (reorder visible controls) ───────────────

@Composable
private fun OrderPage(
    controls: List<ControlSetting>,
    onMoveUp: (Int) -> Unit,
    onMoveDown: (Int) -> Unit,
    onBack: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        LightTopBar(
            leftButton = LightBarButton.LightIcon(
                icon = LightIcons.BACK,
                onClick = onBack,
            ),
            center = LightTopBarCenter.Text("Order"),
            modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
        )

        LightScrollView(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 1f.gridUnitsAsDp()),
        ) {
            controls.forEachIndexed { index, control ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 0.75f.gridUnitsAsDp()),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (!controls.isFirst(index)) {
                        Box(
                            modifier = Modifier
                                .lightClickable(onClick = { onMoveUp(index) })
                                .padding(horizontal = 0.25f.gridUnitsAsDp()),
                        ) {
                            LightIcon(icon = LightIcons.UP)
                        }
                    } else {
                        Spacer(modifier = Modifier.width(2.5f.gridUnitsAsDp()))
                    }

                    if (!controls.isLast(index)) {
                        Box(
                            modifier = Modifier
                                .lightClickable(onClick = { onMoveDown(index) })
                                .padding(horizontal = 0.25f.gridUnitsAsDp()),
                        ) {
                            LightIcon(icon = LightIcons.DOWN)
                        }
                    } else {
                        Spacer(modifier = Modifier.width(2.5f.gridUnitsAsDp()))
                    }

                    Spacer(modifier = Modifier.width(0.5f.gridUnitsAsDp()))

                    LightText(
                        text = control.id.label,
                        variant = LightTextVariant.Copy,
                    )
                }
            }
        }
    }
}

private fun <T> List<T>.isFirst(index: Int) = index == 0
private fun <T> List<T>.isLast(index: Int) = index == lastIndex
