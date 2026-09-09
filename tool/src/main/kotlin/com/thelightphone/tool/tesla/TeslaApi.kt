package com.thelightphone.tool.tesla

import android.util.Base64
import com.thelightphone.tool.tesla.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.KeyFactory
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Minimal Tesla Fleet API client using OkHttp.
 * Handles token refresh and vehicle commands.
 */
class TeslaApi(
    private val tokenStore: TokenStore,
) {
    companion object {
        private const val FLEET_URL = "https://fleet-api.prd.na.vn.cloud.tesla.com"
        private const val AUTH_URL = "https://auth.tesla.com/oauth2/v3/token"

        val CLIENT_ID: String = BuildConfig.TESLA_CLIENT_ID
        val CLIENT_SECRET: String = BuildConfig.TESLA_CLIENT_SECRET
        val REDIRECT_URI: String = BuildConfig.TESLA_REDIRECT_URI
    }

    private val json = Json { ignoreUnknownKeys = true }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    // ── VCP direct signing ──────────────────────────────────

    // One session per domain — established lazily on first command
    private var securitySession: VcpSession? = null
    private var infotainmentSession: VcpSession? = null

    // The client's enrolled private key (loaded from storage/asset)
    private var clientPrivateKey: ECPrivateKey? = null
    private var clientPublicKeyBytes: ByteArray? = null

    /** Check if a private key is loaded. */
    fun hasPrivateKey(): Boolean = clientPrivateKey != null

    /** Reset all VCP sessions — forces fresh handshake with current counters. */
    fun resetSessions() {
        securitySession = null
        infotainmentSession = null
    }

    /**
     * Load both private and public key from PEM files.
     * The key pair must already be enrolled on the vehicle.
     */
    fun loadKeyPair(privatePemBytes: ByteArray, publicPemBytes: ByteArray) {
        val privPem = String(privatePemBytes)
        val privBase64 = privPem
            .lines()
            .filter { !it.startsWith("-----") }
            .joinToString("")
        val privKeyBytes = Base64.decode(privBase64, Base64.DEFAULT)

        // Try PKCS#8 — use AndroidOpenSSL to avoid AndroidKeyStore provider
        val kf = KeyFactory.getInstance("EC", "AndroidOpenSSL")
        clientPrivateKey = try {
            kf.generatePrivate(PKCS8EncodedKeySpec(privKeyBytes)) as ECPrivateKey
        } catch (_: Exception) {
            // SEC1 — wrap in PKCS#8
            val wrapped = wrapSec1InPkcs8(privKeyBytes)
            kf.generatePrivate(PKCS8EncodedKeySpec(wrapped)) as ECPrivateKey
        }

        // Load public key
        val pubPem = String(publicPemBytes)
        val pubBase64 = pubPem
            .lines()
            .filter { !it.startsWith("-----") }
            .joinToString("")
        val pubKeyBytes = Base64.decode(pubBase64, Base64.DEFAULT)
        val pubKey = kf.generatePublic(java.security.spec.X509EncodedKeySpec(pubKeyBytes)) as ECPublicKey
        clientPublicKeyBytes = VcpSession.ecPublicKeyToUncompressed(pubKey)
    }

    private fun wrapSec1InPkcs8(sec1Bytes: ByteArray): ByteArray {
        // Simple SEC1 wrapping for P-256: extract 32-byte private key value
        val privValue = extractSec1PrivateKey(sec1Bytes)
        // PKCS#8 header for P-256 EC key with 32-byte private value
        val header = byteArrayOf(
            0x30, 0x41, 0x02, 0x01, 0x00, 0x30, 0x13, 0x06,
            0x07, 0x2A, 0x86.toByte(), 0x48, 0xCE.toByte(), 0x3D, 0x02, 0x01,
            0x06, 0x08, 0x2A, 0x86.toByte(), 0x48, 0xCE.toByte(), 0x3D, 0x03,
            0x01, 0x07, 0x04, 0x27, 0x30, 0x25, 0x02, 0x01,
            0x01, 0x04, 0x20,
        )
        return header + privValue
    }

    private fun extractSec1PrivateKey(sec1Bytes: ByteArray): ByteArray {
        // SEC1 EC private key: SEQUENCE { version=1, OCTET STRING(32) = privkey, ... }
        // The 32-byte private key usually starts at offset 7 (after SEQUENCE, version INTEGER)
        // But let's parse the ASN.1 properly:
        var i = 0
        if (sec1Bytes[i++] != 0x30.toByte()) throw Exception("Not a SEQUENCE")
        // Skip length
        val seqLen = sec1Bytes[i++].toInt() and 0xFF
        if (seqLen > 127) i += (seqLen and 0x7F) // multi-byte length
        // Skip version (INTEGER 1)
        if (sec1Bytes[i++] != 0x02.toByte()) throw Exception("Expected INTEGER")
        val intLen = sec1Bytes[i++].toInt() and 0xFF
        i += intLen
        // Next is OCTET STRING with the private key
        if (sec1Bytes[i++] != 0x04.toByte()) throw Exception("Expected OCTET STRING")
        val keyLen = sec1Bytes[i++].toInt() and 0xFF
        return sec1Bytes.copyOfRange(i, i + keyLen)
    }

    // ── Auth ──────────────────────────────────────────────

    /**
     * Exchange a refresh token for a fresh access token.
     * Called automatically before commands if the access token is missing.
     */
    suspend fun refreshAccessToken(): Result<String> = withContext(Dispatchers.IO) {
        val refreshToken = tokenStore.getRefreshToken()
            ?: return@withContext Result.failure(Exception("No refresh token saved"))

        val body = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("client_id", CLIENT_ID)
            .add("client_secret", CLIENT_SECRET)
            .add("refresh_token", refreshToken)
            .build()

        val request = Request.Builder()
            .url(AUTH_URL)
            .post(body)
            .build()

        try {
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                return@withContext Result.failure(
                    Exception("Auth failed (${response.code}): $responseBody")
                )
            }

            val tokenResponse = json.decodeFromString<TokenResponse>(responseBody)
            tokenStore.saveAccessToken(tokenResponse.accessToken)
            tokenResponse.refreshToken?.let { tokenStore.saveRefreshToken(it) }
            Result.success(tokenResponse.accessToken)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Exchange an authorization code + PKCE verifier for tokens.
     * Called when the user scans a tesla-auth: QR code from the auth page.
     */
    suspend fun exchangeAuthCode(
        code: String,
        codeVerifier: String,
    ): Result<String> = withContext(Dispatchers.IO) {
        val body = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("client_id", CLIENT_ID)
            .add("client_secret", CLIENT_SECRET)
            .add("code", code)
            .add("code_verifier", codeVerifier)
            .add("redirect_uri", REDIRECT_URI)
            .build()

        val request = Request.Builder()
            .url(AUTH_URL)
            .post(body)
            .build()

        try {
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                return@withContext Result.failure(
                    Exception("Token exchange failed (${response.code}): $responseBody"),
                )
            }

            val tokenResponse = json.decodeFromString<TokenResponse>(responseBody)
            tokenStore.saveAccessToken(tokenResponse.accessToken)
            tokenResponse.refreshToken?.let { tokenStore.saveRefreshToken(it) }
                ?: return@withContext Result.failure(Exception("No refresh token in response"))
            Result.success(tokenResponse.accessToken)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun getAccessToken(): String {
        return tokenStore.getAccessToken() ?: refreshAccessToken().getOrThrow()
    }

    // ── Vehicle list ─────────────────────────────────────

    suspend fun getVehicles(): Result<List<Vehicle>> = withContext(Dispatchers.IO) {
        try {
            val token = getAccessToken()
            val request = Request.Builder()
                .url("$FLEET_URL/api/1/vehicles")
                .header("Authorization", "Bearer $token")
                .get()
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: ""

            if (response.code == 401) {
                // Token expired — refresh and retry once
                val newToken = refreshAccessToken().getOrThrow()
                val retry = Request.Builder()
                    .url("$FLEET_URL/api/1/vehicles")
                    .header("Authorization", "Bearer $newToken")
                    .get()
                    .build()
                val retryResponse = client.newCall(retry).execute()
                val retryBody = retryResponse.body?.string() ?: ""
                if (!retryResponse.isSuccessful) {
                    return@withContext Result.failure(Exception("Failed (${retryResponse.code}): $retryBody"))
                }
                val parsed = json.decodeFromString<VehiclesResponse>(retryBody)
                return@withContext Result.success(parsed.response)
            }

            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("Failed (${response.code}): $body"))
            }

            val parsed = json.decodeFromString<VehiclesResponse>(body)
            Result.success(parsed.response)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ── Wake ────────────────────────────────────────────

    /**
     * Wake the vehicle via the proxy (VCP required even for wake).
     */
    suspend fun wakeUp(vin: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            var token = getAccessToken()
            val emptyBody = "{}".toRequestBody("application/json".toMediaType())

            // Send wake directly to Fleet API (not VCP, no proxy needed)
            var wakeResp = client.newCall(
                Request.Builder()
                    .url("$FLEET_URL/api/1/vehicles/$vin/wake_up")
                    .header("Authorization", "Bearer $token")
                    .post(emptyBody)
                    .build()
            ).execute()
            var wakeBody = wakeResp.body?.string() ?: ""
            android.util.Log.d("TeslaApi", "wake_up response (${wakeResp.code}): ${wakeBody.take(300)}")

            // Handle expired token
            if (wakeResp.code == 401) {
                token = refreshAccessToken().getOrThrow()
                val retryBody = "{}".toRequestBody("application/json".toMediaType())
                wakeResp = client.newCall(
                    Request.Builder()
                        .url("$FLEET_URL/api/1/vehicles/$vin/wake_up")
                        .header("Authorization", "Bearer $token")
                        .post(retryBody)
                        .build()
                ).execute()
                wakeBody = wakeResp.body?.string() ?: ""
            }

            if (!wakeResp.isSuccessful) {
                return@withContext Result.failure(
                    Exception("Wake failed (${wakeResp.code}): ${wakeBody.take(200)}")
                )
            }

            // Check if already online
            if (wakeBody.contains("\"state\":\"online\"")) {
                return@withContext Result.success(Unit)
            }

            // Poll via fleet API (read-only, doesn't need VCP)
            // 3 attempts × 7s = 21s max wait. Tesla rate-limits wakes to 3/min.
            var lastState = "unknown"
            repeat(3) { attempt ->
                Thread.sleep(7000)

                val resp = client.newCall(
                    Request.Builder()
                        .url("$FLEET_URL/api/1/vehicles")
                        .header("Authorization", "Bearer $token")
                        .get()
                        .build()
                ).execute()
                val body = resp.body?.string() ?: ""

                try {
                    val vehicles = json.decodeFromString<VehiclesResponse>(body)
                    val ourVehicle = vehicles.response.find { it.vin == vin }
                    lastState = ourVehicle?.state ?: "not found"
                    if (ourVehicle?.state == "online") {
                        return@withContext Result.success(Unit)
                    }
                } catch (_: Exception) {}
            }
            Result.failure(Exception("Vehicle state: $lastState after 21s. Wake: ${wakeBody.take(150)}"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ── Commands (direct VCP signing) ──────────────────────

    private var lastWakeTime = 0L
    private val WAKE_CACHE_MS = 300_000L // 5 min — skip wake if car was recently awake

    /**
     * Send a signed command directly to Tesla's Fleet API.
     * No proxy needed — signing happens on-device.
     */
    suspend fun sendSignedCommand(
        vin: String,
        command: TeslaCommand,
    ): Result<CommandResult> = withContext(Dispatchers.IO) {
        try {
            val pubKey = clientPublicKeyBytes
                ?: return@withContext Result.failure(Exception("No VCP key loaded"))

            val token = getAccessToken()

            // Lightweight pre-emptive wake: fire wake_up (no polling) if cache is cold.
            // This starts the wake process so the car is already waking by the time
            // we send the command — makes 408 retries resolve faster or avoids them entirely.
            if (System.currentTimeMillis() - lastWakeTime > WAKE_CACHE_MS) {
                try {
                    val wakeToken = getAccessToken()
                    val wakeResp = client.newCall(
                        Request.Builder()
                            .url("$FLEET_URL/api/1/vehicles/$vin/wake_up")
                            .header("Authorization", "Bearer $wakeToken")
                            .post("{}".toRequestBody("application/json".toMediaType()))
                            .build()
                    ).execute()
                    val wakeBody = wakeResp.body?.string() ?: ""
                    if (wakeBody.contains("\"state\":\"online\"")) {
                        lastWakeTime = System.currentTimeMillis()
                    }
                    // Don't poll — just proceed to command. 408 handler retries if needed.
                } catch (_: Exception) {
                    // Wake failed — proceed anyway, 408 handler will retry
                }
            }

            // Build the command payload
            val (domain, payload) = VcpMessages.buildCommandPayload(command)

            // Ensure session is established for this domain
            val session = getOrCreateSession(domain, vin, token)

            // Sign the command
            val counter = session.nextCounter()
            val expiresAt = session.expiresAt()
            android.util.Log.d("TeslaApi", "VCP: domain=$domain counter=$counter expiresAt=$expiresAt")
            val hmacTag = session.sign(payload, vin, counter, expiresAt)
            val uuid = UUID.randomUUID().toBytes()

            // Build the signed RoutableMessage
            val routableMessage = VcpMessages.buildSignedRoutableMessage(
                domain = domain,
                payload = payload,
                signerPublicKey = pubKey,
                epoch = session.epoch,
                counter = counter,
                expiresAt = expiresAt,
                hmacTag = hmacTag,
                uuid = uuid,
            )

            // POST to signed_command endpoint
            val b64 = Base64.encodeToString(routableMessage, Base64.NO_WRAP)
            val jsonBody = """{"routable_message":"$b64"}"""
            val request = Request.Builder()
                .url("$FLEET_URL/api/1/vehicles/$vin/signed_command")
                .header("Authorization", "Bearer $token")
                .header("Content-Type", "application/json")
                .post(jsonBody.toRequestBody("application/json".toMediaType()))
                .build()

            var response = client.newCall(request).execute()
            var body = response.body?.string() ?: ""

            android.util.Log.d("TeslaApi", "signed_command (${response.code}): ${body.take(300)}")

            // Handle 401
            if (response.code == 401) {
                val newToken = refreshAccessToken().getOrThrow()
                response = client.newCall(
                    request.newBuilder()
                        .header("Authorization", "Bearer $newToken")
                        .build(),
                ).execute()
                body = response.body?.string() ?: ""
            }

            // Handle 408 (asleep) — wake (already in progress from pre-emptive call) and retry
            if (response.code == 408) {
                // Pre-emptive wake already fired — just poll briefly for it to finish
                wakeUp(vin).getOrThrow()
                lastWakeTime = System.currentTimeMillis()
                // Re-send with a new counter
                val retryCounter = session.nextCounter()
                val retryExpires = session.expiresAt()
                val retryTag = session.sign(payload, vin, retryCounter, retryExpires)
                val retryUuid = UUID.randomUUID().toBytes()
                val retryMsg = VcpMessages.buildSignedRoutableMessage(
                    domain, payload, pubKey, session.epoch,
                    retryCounter, retryExpires, retryTag, retryUuid,
                )
                val retryB64 = Base64.encodeToString(retryMsg, Base64.NO_WRAP)
                val retryJson = """{"routable_message":"$retryB64"}"""
                response = client.newCall(
                    Request.Builder()
                        .url("$FLEET_URL/api/1/vehicles/$vin/signed_command")
                        .header("Authorization", "Bearer ${getAccessToken()}")
                        .header("Content-Type", "application/json")
                        .post(retryJson.toRequestBody("application/json".toMediaType()))
                        .build(),
                ).execute()
                body = response.body?.string() ?: ""
            }

            // Handle session errors (vehicle may have reset session)
            if (response.code == 422 || body.contains("invalid_session")) {
                // Invalidate session and retry once
                invalidateSession(domain)
                val newSession = getOrCreateSession(domain, vin, getAccessToken())
                val c2 = newSession.nextCounter()
                val e2 = newSession.expiresAt()
                val t2 = newSession.sign(payload, vin, c2, e2)
                val u2 = UUID.randomUUID().toBytes()
                val msg2 = VcpMessages.buildSignedRoutableMessage(
                    domain, payload, pubKey, newSession.epoch, c2, e2, t2, u2,
                )
                val b642 = Base64.encodeToString(msg2, Base64.NO_WRAP)
                val json2 = """{"routable_message":"$b642"}"""
                response = client.newCall(
                    Request.Builder()
                        .url("$FLEET_URL/api/1/vehicles/$vin/signed_command")
                        .header("Authorization", "Bearer ${getAccessToken()}")
                        .header("Content-Type", "application/json")
                        .post(json2.toRequestBody("application/json".toMediaType()))
                        .build(),
                ).execute()
                body = response.body?.string() ?: ""
            }

            if (!response.isSuccessful) {
                return@withContext Result.failure(
                    Exception("Command failed (${response.code}): ${body.take(200)}"),
                )
            }

            // Parse the vehicle's response to check for errors
            try {
                val responseJson = json.decodeFromString<JsonObject>(body)
                val responseB64 = responseJson["response"]?.jsonPrimitive?.content
                if (responseB64 != null) {
                    val responseBytes = Base64.decode(responseB64, Base64.DEFAULT)
                    val rmFields = ProtobufDecoder(responseBytes).readAllFields()
                    // Field 12 = signed_message_status (contains operation_status + signed_message_fault)
                    val statusBytes = rmFields[12] as? ByteArray
                    if (statusBytes != null) {
                        val statusFields = ProtobufDecoder(statusBytes).readAllFields()
                        val opStatus = (statusFields[1] as? Long)?.toInt() ?: 0
                        val fault = (statusFields[2] as? Long)?.toInt() ?: 0
                        android.util.Log.d("TeslaApi", "Vehicle response: opStatus=$opStatus, fault=$fault")
                        // opStatus: 0=OK, 1=WAIT, 2=ERROR; fault codes indicate specific errors
                        if (opStatus == 2) {
                            // Fault codes from universal_message.proto MessageFault_E
                            val faultName = when (fault) {
                                0 -> "NONE"
                                1 -> "BUSY"
                                2 -> "TIMEOUT"
                                3 -> "UNKNOWN_KEY_ID"
                                4 -> "INACTIVE_KEY"
                                5 -> "INVALID_SIGNATURE"
                                6 -> "INVALID_TOKEN_OR_COUNTER"
                                7 -> "INSUFFICIENT_PRIVILEGES"
                                8 -> "INVALID_DOMAINS"
                                9 -> "INVALID_COMMAND"
                                10 -> "DECODING"
                                11 -> "INTERNAL"
                                12 -> "WRONG_PERSONALIZATION"
                                13 -> "BAD_PARAMETER"
                                14 -> "KEYCHAIN_IS_FULL"
                                15 -> "INCORRECT_EPOCH"
                                16 -> "IV_INCORRECT_LENGTH"
                                17 -> "TIME_EXPIRED"
                                18 -> "NOT_PROVISIONED_WITH_IDENTITY"
                                19 -> "COULD_NOT_HASH_METADATA"
                                20 -> "TIME_TO_LIVE_TOO_LONG"
                                21 -> "REMOTE_ACCESS_DISABLED"
                                22 -> "REMOTE_SERVICE_ACCESS_DISABLED"
                                23 -> "COMMAND_REQUIRES_ACCOUNT_CREDENTIALS"
                                else -> "UNKNOWN($fault)"
                            }
                            // Auto-reset session on session-related errors and retry once
                            // 6=INVALID_TOKEN_OR_COUNTER, 15=INCORRECT_EPOCH, 17=TIME_EXPIRED
                            if (fault in listOf(6, 15, 17)) {
                                invalidateSession(domain)
                                android.util.Log.d("TeslaApi", "Session reset due to $faultName, retrying...")
                                val retrySession = getOrCreateSession(domain, vin, getAccessToken())
                                val rc = retrySession.nextCounter()
                                val re = retrySession.expiresAt()
                                android.util.Log.d("TeslaApi", "Retry VCP: counter=$rc expiresAt=$re")
                                val rt = retrySession.sign(payload, vin, rc, re)
                                val ru = UUID.randomUUID().toBytes()
                                val retryMsg = VcpMessages.buildSignedRoutableMessage(
                                    domain, payload, pubKey, retrySession.epoch, rc, re, rt, ru,
                                )
                                val retryB64 = Base64.encodeToString(retryMsg, Base64.NO_WRAP)
                                val retryJson = """{"routable_message":"$retryB64"}"""
                                val retryResp = client.newCall(
                                    Request.Builder()
                                        .url("$FLEET_URL/api/1/vehicles/$vin/signed_command")
                                        .header("Authorization", "Bearer ${getAccessToken()}")
                                        .header("Content-Type", "application/json")
                                        .post(retryJson.toRequestBody("application/json".toMediaType()))
                                        .build(),
                                ).execute()
                                val retryBody = retryResp.body?.string() ?: ""
                                android.util.Log.d("TeslaApi", "Retry response (${retryResp.code}): ${retryBody.take(300)}")
                                if (!retryResp.isSuccessful) {
                                    return@withContext Result.failure(
                                        Exception("Retry failed (${retryResp.code}): ${retryBody.take(200)}")
                                    )
                                }
                                // Parse retry response for faults
                                try {
                                    val rj = json.decodeFromString<JsonObject>(retryBody)
                                    val rb = rj["response"]?.jsonPrimitive?.content
                                    if (rb != null) {
                                        val rbytes = Base64.decode(rb, Base64.DEFAULT)
                                        val rFields = ProtobufDecoder(rbytes).readAllFields()
                                        val rStatus = rFields[12] as? ByteArray
                                        if (rStatus != null) {
                                            val rsf = ProtobufDecoder(rStatus).readAllFields()
                                            val rOp = (rsf[1] as? Long)?.toInt() ?: 0
                                            val rFault = (rsf[2] as? Long)?.toInt() ?: 0
                                            android.util.Log.d("TeslaApi", "Retry vehicle response: opStatus=$rOp, fault=$rFault")
                                            if (rOp == 2) {
                                                return@withContext Result.failure(
                                                    Exception("Vehicle rejected after retry: fault=$rFault ($faultName)")
                                                )
                                            }
                                        }
                                    }
                                } catch (re: Exception) {
                                    android.util.Log.w("TeslaApi", "Could not parse retry response: ${re.message}")
                                }
                                return@withContext Result.success(CommandResult(result = true, reason = ""))
                            } else {
                                return@withContext Result.failure(
                                    Exception("Vehicle rejected: $faultName")
                                )
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("TeslaApi", "Could not parse vehicle response: ${e.message}")
            }
            Result.success(CommandResult(result = true, reason = ""))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Get or establish a VCP session for the given domain.
     */
    private suspend fun getOrCreateSession(
        domain: Int,
        vin: String,
        token: String,
    ): VcpSession {
        val existing = if (domain == VcpMessages.DOMAIN_VEHICLE_SECURITY) securitySession
        else infotainmentSession
        if (existing?.isEstablished == true) return existing

        val privKey = clientPrivateKey ?: throw Exception("No VCP key loaded")
        val pubKey = clientPublicKeyBytes ?: throw Exception("No VCP key loaded")
        val session = VcpSession(domain, privKey, pubKey)
        val uuid = UUID.randomUUID().toBytes()
        val requestBytes = VcpMessages.buildSessionInfoRequest(domain, pubKey, uuid)
        val b64 = Base64.encodeToString(requestBytes, Base64.NO_WRAP)
        val jsonBody = """{"routable_message":"$b64"}"""

        val request = Request.Builder()
            .url("$FLEET_URL/api/1/vehicles/$vin/signed_command")
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .post(jsonBody.toRequestBody("application/json".toMediaType()))
            .build()

        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: ""

        android.util.Log.d("TeslaApi", "session_info response (${response.code}): ${body.take(300)}")

        if (!response.isSuccessful) {
            throw Exception("Session handshake failed (${response.code}): ${body.take(200)}")
        }

        // Parse response — extract the base64 routable message
        val responseJson = json.decodeFromString<JsonObject>(body)
        val responseB64 = responseJson["response"]?.jsonPrimitive?.content
            ?: throw Exception("No response in session info reply")
        val responseBytes = Base64.decode(responseB64, Base64.DEFAULT)

        val sessionInfo = VcpMessages.parseSessionInfoResponse(responseBytes)
            ?: throw Exception("Failed to parse session info")

        if (sessionInfo.status == 1) {
            throw Exception("Vehicle reports key not on whitelist — re-enroll via /_ak/ flow")
        }
        session.processSessionInfo(sessionInfo)

        // Store session
        if (domain == VcpMessages.DOMAIN_VEHICLE_SECURITY) securitySession = session
        else infotainmentSession = session

        return session
    }

    private fun invalidateSession(domain: Int) {
        if (domain == VcpMessages.DOMAIN_VEHICLE_SECURITY) securitySession = null
        else infotainmentSession = null
    }

    private fun UUID.toBytes(): ByteArray {
        val bb = java.nio.ByteBuffer.allocate(16)
        bb.putLong(mostSignificantBits)
        bb.putLong(leastSignificantBits)
        return bb.array()
    }

    // ── Vehicle state ─────────────────────────────────────

    /**
     * Fetch current vehicle state (lock, climate, drive).
     * Uses Fleet API directly (read-only, no VCP needed).
     */
    suspend fun getVehicleState(vin: String): Result<VehicleState> = withContext(Dispatchers.IO) {
        try {
            var token = getAccessToken()
            val endpoints = "vehicle_state%3Bclimate_state%3Bdrive_state%3Bcharge_state"
            val url = "$FLEET_URL/api/1/vehicles/$vin/vehicle_data?endpoints=$endpoints"

            var response = client.newCall(
                Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer $token")
                    .get()
                    .build()
            ).execute()
            var body = response.body?.string() ?: ""

            if (response.code == 401) {
                token = refreshAccessToken().getOrThrow()
                response = client.newCall(
                    Request.Builder()
                        .url(url)
                        .header("Authorization", "Bearer $token")
                        .get()
                        .build()
                ).execute()
                body = response.body?.string() ?: ""
            }

            if (!response.isSuccessful) {
                return@withContext Result.failure(
                    Exception("State fetch failed (${response.code}): ${body.take(200)}")
                )
            }

            val parsed = json.decodeFromString<VehicleDataResponse>(body)
            val r = parsed.response

            // Tesla returns "Off", "On" (AC mode), or "FanOnly"
            val overheat = when (r.climateState?.cabinOverheatProtection) {
                "On" -> OverheatMode.AC
                "FanOnly" -> OverheatMode.FanOnly
                else -> OverheatMode.Off
            }

            val anyWindowOpen = listOfNotNull(
                r.vehicleState?.fdWindow,
                r.vehicleState?.fpWindow,
                r.vehicleState?.rdWindow,
                r.vehicleState?.rpWindow,
            ).any { it != 0 }

            Result.success(
                VehicleState(
                    locked = r.vehicleState?.locked ?: true,
                    climateOn = r.climateState?.isClimateOn ?: false,
                    defrostOn = r.climateState?.defrostMode != null && r.climateState.defrostMode > 0,
                    overheatMode = overheat,
                    insideTemp = r.climateState?.insideTemp,
                    outsideTemp = r.climateState?.outsideTemp,
                    batteryLevel = r.chargeState?.batteryLevel,
                    batteryRange = r.chargeState?.batteryRange,
                    chargingState = r.chargeState?.chargingState,
                    chargeRate = r.chargeState?.chargeRate,
                    timeToFullCharge = r.chargeState?.timeToFullCharge,
                    chargePortOpen = r.chargeState?.chargePortDoorOpen ?: false,
                    trunkOpen = (r.vehicleState?.rt ?: 0) != 0,
                    frunkOpen = (r.vehicleState?.ft ?: 0) != 0,
                    sentryMode = r.vehicleState?.sentryMode ?: false,
                    windowsOpen = anyWindowOpen,
                    driveState = r.driveState?.shiftState,
                    timestamp = System.currentTimeMillis(),
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ── Convenience methods (all route through sendSignedCommand) ──

    suspend fun lock(vin: String) = sendSignedCommand(vin, TeslaCommand.Lock)
    suspend fun unlock(vin: String) = sendSignedCommand(vin, TeslaCommand.Unlock)
    suspend fun remoteStart(vin: String) = sendSignedCommand(vin, TeslaCommand.RemoteStart)
    suspend fun climateOn(vin: String) = sendSignedCommand(vin, TeslaCommand.ClimateOn)
    suspend fun climateOff(vin: String) = sendSignedCommand(vin, TeslaCommand.ClimateOff)
    suspend fun defrostOn(vin: String) = sendSignedCommand(vin, TeslaCommand.DefrostOn)
    suspend fun defrostOff(vin: String) = sendSignedCommand(vin, TeslaCommand.DefrostOff)
    suspend fun overheatOff(vin: String) = sendSignedCommand(vin, TeslaCommand.OverheatOff)
    suspend fun overheatFanOnly(vin: String) = sendSignedCommand(vin, TeslaCommand.OverheatFan)
    suspend fun overheatAcOn(vin: String) = sendSignedCommand(vin, TeslaCommand.OverheatAC)
    suspend fun openTrunk(vin: String) = sendSignedCommand(vin, TeslaCommand.OpenTrunk)
    suspend fun closeTrunk(vin: String) = sendSignedCommand(vin, TeslaCommand.CloseTrunk)
    suspend fun openFrunk(vin: String) = sendSignedCommand(vin, TeslaCommand.OpenFrunk)
    suspend fun chargePortOpen(vin: String) = sendSignedCommand(vin, TeslaCommand.ChargePortOpen)
    suspend fun chargePortClose(vin: String) = sendSignedCommand(vin, TeslaCommand.ChargePortClose)
    suspend fun sentryOn(vin: String) = sendSignedCommand(vin, TeslaCommand.SentryOn)
    suspend fun sentryOff(vin: String) = sendSignedCommand(vin, TeslaCommand.SentryOff)
    suspend fun flashLights(vin: String) = sendSignedCommand(vin, TeslaCommand.FlashLights)
    suspend fun honkHorn(vin: String) = sendSignedCommand(vin, TeslaCommand.HonkHorn)
    suspend fun ventWindows(vin: String) = sendSignedCommand(vin, TeslaCommand.VentWindows)
    suspend fun closeWindows(vin: String) = sendSignedCommand(vin, TeslaCommand.CloseWindows)
}

// ── Data models ──────────────────────────────────────────

@Serializable
data class TokenResponse(
    @kotlinx.serialization.SerialName("access_token")
    val accessToken: String,
    @kotlinx.serialization.SerialName("refresh_token")
    val refreshToken: String? = null,
    @kotlinx.serialization.SerialName("expires_in")
    val expiresIn: Int = 0,
)

@Serializable
data class Vehicle(
    val id: Long = 0,
    @kotlinx.serialization.SerialName("vehicle_id")
    val vehicleId: Long = 0,
    val vin: String = "",
    @kotlinx.serialization.SerialName("display_name")
    val displayName: String? = null,
    val state: String = "",
)

@Serializable
data class VehiclesResponse(
    val response: List<Vehicle>,
    val count: Int = 0,
)

@Serializable
data class CommandResult(
    val result: Boolean = false,
    val reason: String = "",
)

@Serializable
data class CommandResponse(
    val response: CommandResult,
)

// ── Vehicle data models ─────────────────────────────────

enum class OverheatMode { Off, FanOnly, AC }

data class VehicleState(
    val locked: Boolean = true,
    val climateOn: Boolean = false,
    val defrostOn: Boolean = false,
    val overheatMode: OverheatMode = OverheatMode.Off,
    val insideTemp: Double? = null,
    val outsideTemp: Double? = null,
    val batteryLevel: Int? = null,
    val batteryRange: Double? = null,
    val chargingState: String? = null,
    val chargeRate: Double? = null,
    val timeToFullCharge: Double? = null,
    val chargePortOpen: Boolean = false,
    val trunkOpen: Boolean = false,
    val frunkOpen: Boolean = false,
    val sentryMode: Boolean = false,
    val windowsOpen: Boolean = false,
    val driveState: String? = null,
    val timestamp: Long = 0L,
)

@Serializable
data class VehicleDataResponse(
    val response: VehicleDataInner,
)

@Serializable
data class VehicleDataInner(
    @kotlinx.serialization.SerialName("vehicle_state")
    val vehicleState: VehicleStateData? = null,
    @kotlinx.serialization.SerialName("climate_state")
    val climateState: ClimateStateData? = null,
    @kotlinx.serialization.SerialName("drive_state")
    val driveState: DriveStateData? = null,
    @kotlinx.serialization.SerialName("charge_state")
    val chargeState: ChargeStateData? = null,
)

@Serializable
data class VehicleStateData(
    val locked: Boolean? = null,
    val rt: Int? = null, // rear trunk: 0=closed, non-zero=open
    val ft: Int? = null, // front trunk: 0=closed, non-zero=open
    @kotlinx.serialization.SerialName("sentry_mode")
    val sentryMode: Boolean? = null,
    @kotlinx.serialization.SerialName("fd_window")
    val fdWindow: Int? = null, // front driver window
    @kotlinx.serialization.SerialName("fp_window")
    val fpWindow: Int? = null, // front passenger window
    @kotlinx.serialization.SerialName("rd_window")
    val rdWindow: Int? = null, // rear driver window
    @kotlinx.serialization.SerialName("rp_window")
    val rpWindow: Int? = null, // rear passenger window
)

@Serializable
data class ClimateStateData(
    @kotlinx.serialization.SerialName("is_climate_on")
    val isClimateOn: Boolean? = null,
    @kotlinx.serialization.SerialName("defrost_mode")
    val defrostMode: Int? = null,
    @kotlinx.serialization.SerialName("inside_temp")
    val insideTemp: Double? = null,
    @kotlinx.serialization.SerialName("outside_temp")
    val outsideTemp: Double? = null,
    @kotlinx.serialization.SerialName("cabin_overheat_protection")
    val cabinOverheatProtection: String? = null, // "On" or "Off"
    @kotlinx.serialization.SerialName("cabin_overheat_protection_actively_cooling")
    val cabinOverheatProtectionActivelyCooling: Boolean? = null,
    @kotlinx.serialization.SerialName("cop_activation_temperature")
    val copActivationTemperature: String? = null, // "High", "Medium", "Low"
)

@Serializable
data class DriveStateData(
    @kotlinx.serialization.SerialName("shift_state")
    val shiftState: String? = null,
)

@Serializable
data class ChargeStateData(
    @kotlinx.serialization.SerialName("battery_level")
    val batteryLevel: Int? = null,
    @kotlinx.serialization.SerialName("battery_range")
    val batteryRange: Double? = null,
    @kotlinx.serialization.SerialName("charging_state")
    val chargingState: String? = null,
    @kotlinx.serialization.SerialName("charge_rate")
    val chargeRate: Double? = null, // miles per hour
    @kotlinx.serialization.SerialName("time_to_full_charge")
    val timeToFullCharge: Double? = null, // hours
    @kotlinx.serialization.SerialName("charge_port_door_open")
    val chargePortDoorOpen: Boolean? = null,
)
