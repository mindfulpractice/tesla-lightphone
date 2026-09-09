package com.thelightphone.tool.tesla

/**
 * Identifies each control row on the home screen.
 * The [label] is the display name shown in settings.
 */
enum class ControlId(val label: String) {
    Status("Status"),
    Lock("Lock"),
    Climate("Climate"),
    Defrost("Defrost"),
    Overheat("Overheat"),
    Start("Start"),
    Trunk("Trunk"),
    Frunk("Frunk"),
    ChargePort("Port"),
    Sentry("Sentry"),
    Windows("Windows"),
    Flash("Flash"),
    Horn("Horn"),
}

data class ControlSetting(
    val id: ControlId,
    val visible: Boolean = true,
)

/** Default order — all controls visible. */
val DEFAULT_CONTROLS: List<ControlSetting> =
    ControlId.entries.map { ControlSetting(it, visible = true) }

/** Metric vs Imperial unit system, following the Weather tool's pattern. */
enum class UnitSystem {
    Imperial,
    Metric,
}

fun UnitSystem.toggle(): UnitSystem = when (this) {
    UnitSystem.Imperial -> UnitSystem.Metric
    UnitSystem.Metric -> UnitSystem.Imperial
}

fun UnitSystem.displayLabel(): String = when (this) {
    UnitSystem.Imperial -> "Imperial"
    UnitSystem.Metric -> "Metric"
}

fun UnitSystem.storageValue(): String = when (this) {
    UnitSystem.Imperial -> "imperial"
    UnitSystem.Metric -> "metric"
}

fun unitSystemFromStorage(value: String?): UnitSystem = when (value) {
    "metric" -> UnitSystem.Metric
    else -> UnitSystem.Imperial
}
