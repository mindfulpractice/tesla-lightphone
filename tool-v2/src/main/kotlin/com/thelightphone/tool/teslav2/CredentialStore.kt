package com.thelightphone.tool.teslav2

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Stores Tesla API credentials (CLIENT_ID, CLIENT_SECRET) using
 * Android's EncryptedSharedPreferences, backed by hardware Keystore.
 *
 * These never leave the device — they're only sent to Tesla's own
 * OAuth endpoints during authentication.
 */
class CredentialStore(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        PREFS_FILE,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun saveClientId(clientId: String) {
        prefs.edit().putString(KEY_CLIENT_ID, clientId).apply()
    }

    fun getClientId(): String? = prefs.getString(KEY_CLIENT_ID, null)

    fun saveClientSecret(clientSecret: String) {
        prefs.edit().putString(KEY_CLIENT_SECRET, clientSecret).apply()
    }

    fun getClientSecret(): String? = prefs.getString(KEY_CLIENT_SECRET, null)

    /** The redirect URI is fixed to our domain — not user-configurable. */
    fun getRedirectUri(): String = REDIRECT_URI

    fun hasCredentials(): Boolean =
        !getClientId().isNullOrBlank() && !getClientSecret().isNullOrBlank()

    /** Whether initial setup (credentials + OAuth) has been completed. */
    fun isSetupComplete(): Boolean =
        prefs.getBoolean(KEY_SETUP_COMPLETE, false)

    fun markSetupComplete() {
        prefs.edit().putBoolean(KEY_SETUP_COMPLETE, true).apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val PREFS_FILE = "tesla_credentials"
        private const val KEY_CLIENT_ID = "client_id"
        private const val KEY_CLIENT_SECRET = "client_secret"
        private const val KEY_SETUP_COMPLETE = "setup_complete"

        /** Fixed domain and redirect URI for all users (Option A — shared key). */
        const val DOMAIN = "tesla-lightphone.app"
        const val REDIRECT_URI = "https://tesla-lightphone.app/auth"
    }
}
