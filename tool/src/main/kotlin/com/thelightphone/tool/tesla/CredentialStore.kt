package com.thelightphone.tool.tesla

import java.io.File
import org.json.JSONObject

/**
 * Stores Tesla API credentials (CLIENT_ID, CLIENT_SECRET) encrypted on disk
 * using TeslaTokenCipher (AES-256-GCM backed by Android KeyStore).
 *
 * These never leave the device — they're only sent to Tesla's own
 * OAuth endpoints during authentication.
 */
class CredentialStore(private val filesDir: File) {

    private val cipher = TeslaTokenCipher()
    private val file = File(filesDir, "tesla_credentials.enc")

    /** In-memory cache so reads don't hit disk every time. */
    private var cache: MutableMap<String, String>? = null

    private fun load(): MutableMap<String, String> {
        cache?.let { return it }
        val map = if (file.exists()) {
            try {
                val decrypted = cipher.decrypt(file.readText())
                val json = JSONObject(decrypted)
                val result = mutableMapOf<String, String>()
                for (key in json.keys()) {
                    result[key] = json.getString(key)
                }
                result
            } catch (_: Exception) {
                mutableMapOf()
            }
        } else {
            mutableMapOf()
        }
        cache = map
        return map
    }

    private fun persist(map: MutableMap<String, String>) {
        cache = map
        val json = JSONObject(map as Map<*, *>).toString()
        file.writeText(cipher.encrypt(json))
    }

    fun saveClientId(clientId: String) {
        val map = load()
        map[KEY_CLIENT_ID] = clientId
        persist(map)
    }

    fun getClientId(): String? = load()[KEY_CLIENT_ID]

    fun saveClientSecret(clientSecret: String) {
        val map = load()
        map[KEY_CLIENT_SECRET] = clientSecret
        persist(map)
    }

    fun getClientSecret(): String? = load()[KEY_CLIENT_SECRET]

    /** The redirect URI is fixed to our domain — not user-configurable. */
    fun getRedirectUri(): String = REDIRECT_URI

    fun hasCredentials(): Boolean =
        !getClientId().isNullOrBlank() && !getClientSecret().isNullOrBlank()

    /** Whether initial setup (credentials + OAuth) has been completed. */
    fun isSetupComplete(): Boolean =
        load()[KEY_SETUP_COMPLETE] == "true"

    fun markSetupComplete() {
        val map = load()
        map[KEY_SETUP_COMPLETE] = "true"
        persist(map)
    }

    fun clear() {
        cache = null
        file.delete()
    }

    companion object {
        private const val KEY_CLIENT_ID = "client_id"
        private const val KEY_CLIENT_SECRET = "client_secret"
        private const val KEY_SETUP_COMPLETE = "setup_complete"

        /** Fixed domain and redirect URI for all users (Option A — shared key). */
        const val DOMAIN = "tesla-lightphone.app"
        const val REDIRECT_URI = "https://tesla-lightphone.app/setup"
    }
}
