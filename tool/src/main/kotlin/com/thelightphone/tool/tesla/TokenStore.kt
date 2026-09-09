package com.thelightphone.tool.tesla

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Persists Tesla auth tokens using the Light SDK's DataStore.
 * Sensitive tokens (refresh, access) are encrypted via AndroidKeyStore
 * using AES-256-GCM, following the LP Authenticator pattern.
 * Non-sensitive settings (controls, units) are stored in plaintext.
 */
class TokenStore(private val dataStore: DataStore<Preferences>) {

    private val cipher = TeslaTokenCipher()

    companion object {
        private val KEY_REFRESH_TOKEN = stringPreferencesKey("tesla_refresh_token")
        private val KEY_ACCESS_TOKEN = stringPreferencesKey("tesla_access_token")
        private val KEY_SELECTED_VIN = stringPreferencesKey("tesla_selected_vin")
        private val KEY_VEHICLE_NAME = stringPreferencesKey("tesla_vehicle_name")
        private val KEY_CONTROLS = stringPreferencesKey("tesla_controls")
        private val KEY_UNITS = stringPreferencesKey("tesla_units")
    }

    suspend fun saveRefreshToken(token: String) {
        dataStore.edit { it[KEY_REFRESH_TOKEN] = cipher.encrypt(token) }
    }

    suspend fun getRefreshToken(): String? {
        val encrypted = dataStore.data.map { it[KEY_REFRESH_TOKEN] }.first() ?: return null
        return try {
            cipher.decrypt(encrypted)
        } catch (_: Exception) {
            // If decryption fails (e.g. old unencrypted value), clear and return null
            null
        }
    }

    suspend fun saveAccessToken(token: String) {
        dataStore.edit { it[KEY_ACCESS_TOKEN] = cipher.encrypt(token) }
    }

    suspend fun getAccessToken(): String? {
        val encrypted = dataStore.data.map { it[KEY_ACCESS_TOKEN] }.first() ?: return null
        return try {
            cipher.decrypt(encrypted)
        } catch (_: Exception) {
            null
        }
    }

    suspend fun saveSelectedVehicle(vin: String, name: String?) {
        dataStore.edit {
            it[KEY_SELECTED_VIN] = vin
            name?.let { n -> it[KEY_VEHICLE_NAME] = n }
        }
    }

    suspend fun getSelectedVin(): String? =
        dataStore.data.map { it[KEY_SELECTED_VIN] }.first()

    suspend fun getVehicleName(): String? =
        dataStore.data.map { it[KEY_VEHICLE_NAME] }.first()

    suspend fun hasToken(): Boolean =
        getRefreshToken() != null

    /**
     * Persist control order + visibility.
     * Format: "Lock:1,Climate:0,Defrost:1,..." (id:visible pairs in order)
     */
    suspend fun saveControls(controls: List<ControlSetting>) {
        val encoded = controls.joinToString(",") { "${it.id.name}:${if (it.visible) 1 else 0}" }
        dataStore.edit { it[KEY_CONTROLS] = encoded }
    }

    suspend fun getControls(): List<ControlSetting> {
        val raw = dataStore.data.map { it[KEY_CONTROLS] }.first() ?: return DEFAULT_CONTROLS
        return try {
            val parsed = raw.split(",").mapNotNull { entry ->
                val parts = entry.split(":")
                if (parts.size != 2) return@mapNotNull null
                val id = ControlId.entries.firstOrNull { it.name == parts[0] } ?: return@mapNotNull null
                ControlSetting(id, parts[1] == "1")
            }
            // Add any new controls that weren't in the saved config
            val savedIds = parsed.map { it.id }.toSet()
            val missing = ControlId.entries.filter { it !in savedIds }.map { ControlSetting(it) }
            parsed + missing
        } catch (_: Exception) {
            DEFAULT_CONTROLS
        }
    }

    suspend fun saveUnits(units: UnitSystem) {
        dataStore.edit { it[KEY_UNITS] = units.storageValue() }
    }

    suspend fun getUnits(): UnitSystem {
        val raw = dataStore.data.map { it[KEY_UNITS] }.first()
        return unitSystemFromStorage(raw)
    }

    suspend fun clear() {
        dataStore.edit { it.clear() }
    }
}
