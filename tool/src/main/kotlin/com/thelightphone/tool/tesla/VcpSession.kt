package com.thelightphone.tool.tesla

import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.X509EncodedKeySpec
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Manages a VCP session with a specific domain (VEHICLE_SECURITY or INFOTAINMENT).
 * Handles ECDH key exchange, session key derivation, and HMAC signing.
 *
 * Uses the client's enrolled private key for ECDH — the same key
 * whose public half is in the vehicle's keychain.
 */
class VcpSession(
    val domain: Int,
    private val clientPrivateKey: PrivateKey,
    /** Client's enrolled public key in uncompressed format (65 bytes). */
    val publicKeyBytes: ByteArray,
) {

    // Session state (populated after handshake)
    private var sessionKey: ByteArray? = null
    var epoch: ByteArray = ByteArray(0)
        private set
    var counter: Int = 0
        private set
    private var clockTime: Int = 0
    private var timeZero: Long = 0L // System.currentTimeMillis() when clockTime was received
    var isEstablished: Boolean = false
        private set

    /**
     * Process the vehicle's SessionInfo response.
     * Derives the shared session key via ECDH and verifies the session info HMAC.
     */
    fun processSessionInfo(info: VcpMessages.SessionInfoData) {
        // Reconstruct vehicle's public key from uncompressed bytes
        val vehiclePublicKey = uncompressedToECPublicKey(info.publicKey)

        // ECDH key agreement using enrolled private key
        val keyAgreement = KeyAgreement.getInstance("ECDH")
        keyAgreement.init(clientPrivateKey)
        keyAgreement.doPhase(vehiclePublicKey, true)
        val sharedSecret = keyAgreement.generateSecret()

        // Session key = SHA-1(shared_secret), truncated to 16 bytes
        val sha1 = MessageDigest.getInstance("SHA-1").digest(sharedSecret)
        sessionKey = sha1.copyOf(16)

        // Store session state
        epoch = info.epoch
        counter = info.counter
        clockTime = info.clockTime
        timeZero = System.currentTimeMillis() / 1000
        isEstablished = true
    }

    /** Increment counter and return the new value. */
    fun nextCounter(): Int {
        counter++
        return counter
    }

    /**
     * Compute expiration timestamp relative to the vehicle's epoch clock.
     * The vehicle's clock_time was captured at [timeZero]; we add elapsed
     * seconds plus a TTL (default 15 seconds).
     */
    fun expiresAt(ttlSeconds: Int = 15): Int {
        val elapsed = (System.currentTimeMillis() / 1000) - timeZero
        return clockTime + elapsed.toInt() + ttlSeconds
    }

    /**
     * Sign a command payload using HMAC-SHA256 with metadata TLV.
     *
     * The metadata is a tag-length-value structure:
     *   TAG_SIGNATURE_TYPE (0)  = SIGNATURE_TYPE_HMAC_PERSONALIZED (8)
     *   TAG_DOMAIN (1)          = domain
     *   TAG_PERSONALIZATION (2) = VIN bytes
     *   TAG_EPOCH (3)           = epoch bytes
     *   TAG_EXPIRES_AT (4)      = expiresAt (4 bytes BE)
     *   TAG_COUNTER (5)         = counter (4 bytes BE)
     *   TAG_FLAGS (7)           = flags
     *   TAG_END (255)           = end marker (1 byte only)
     *
     * HMAC key = HMAC-SHA256(sessionKey, "authenticated command")
     * HMAC input = metadata || payload
     */
    fun sign(
        payload: ByteArray,
        vin: String,
        counter: Int,
        expiresAt: Int,
        flags: Int = VcpMessages.FLAG_ENCRYPT_RESPONSE,
    ): ByteArray {
        val key = sessionKey ?: throw IllegalStateException("Session not established")

        // Derive the command signing key (label must match Go: "authenticated command")
        val commandKey = hmacSha256(key, "authenticated command".toByteArray())

        // Build metadata TLV
        val metadata = buildMetadataTlv(
            signatureType = 8, // SIGNATURE_TYPE_HMAC_PERSONALIZED
            domain = domain,
            personalization = vin.toByteArray(),
            epoch = epoch,
            expiresAt = expiresAt,
            counter = counter,
            flags = flags,
        )

        // HMAC-SHA256(commandKey, metadata || payload)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(commandKey, "HmacSHA256"))
        mac.update(metadata)
        return mac.doFinal(payload)
    }

    companion object {
        // ── TLV Tag values ──────────────────────────────
        private const val TAG_SIGNATURE_TYPE = 0
        private const val TAG_DOMAIN = 1
        private const val TAG_PERSONALIZATION = 2
        private const val TAG_EPOCH = 3
        private const val TAG_EXPIRES_AT = 4
        private const val TAG_COUNTER = 5
        private const val TAG_FLAGS = 7
        private const val TAG_END = 0xFF

        /**
         * Build a metadata TLV byte array for HMAC computation.
         * Format: [tag(1)] [length(1)] [value(length)]...  [0xFF]
         *
         * Matches Tesla's Go reference: 1-byte length, big-endian uint32s,
         * single-byte end marker (0xFF).
         */
        private fun buildMetadataTlv(
            signatureType: Int,
            domain: Int,
            personalization: ByteArray,
            epoch: ByteArray,
            expiresAt: Int,
            counter: Int,
            flags: Int,
        ): ByteArray {
            val out = java.io.ByteArrayOutputStream()

            fun writeTlv(tag: Int, value: ByteArray) {
                out.write(tag)
                out.write(value.size and 0xFF) // 1-byte length
                out.write(value)
            }

            fun writeTlvUint32(tag: Int, v: Int) {
                // Big-endian 4-byte encoding (matches Go binary.BigEndian.PutUint32)
                val bytes = byteArrayOf(
                    ((v shr 24) and 0xFF).toByte(),
                    ((v shr 16) and 0xFF).toByte(),
                    ((v shr 8) and 0xFF).toByte(),
                    (v and 0xFF).toByte(),
                )
                writeTlv(tag, bytes)
            }

            writeTlv(TAG_SIGNATURE_TYPE, byteArrayOf(signatureType.toByte()))
            writeTlv(TAG_DOMAIN, byteArrayOf(domain.toByte()))
            writeTlv(TAG_PERSONALIZATION, personalization)
            writeTlv(TAG_EPOCH, epoch)
            writeTlvUint32(TAG_EXPIRES_AT, expiresAt)
            writeTlvUint32(TAG_COUNTER, counter)
            if (flags > 0) {
                writeTlvUint32(TAG_FLAGS, flags)
            }

            // End marker — single byte, no length
            out.write(TAG_END)

            return out.toByteArray()
        }

        private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(key, "HmacSHA256"))
            return mac.doFinal(data)
        }

        /**
         * Convert an ECPublicKey to uncompressed point format (65 bytes).
         * Format: 0x04 || x (32 bytes) || y (32 bytes)
         */
        fun ecPublicKeyToUncompressed(key: ECPublicKey): ByteArray {
            val x = key.w.affineX.toByteArray().takeLast(32).toByteArray().padStart32()
            val y = key.w.affineY.toByteArray().takeLast(32).toByteArray().padStart32()
            return byteArrayOf(0x04) + x + y
        }

        /**
         * Convert uncompressed point bytes (65 bytes) back to an ECPublicKey.
         */
        fun uncompressedToECPublicKey(bytes: ByteArray): ECPublicKey {
            // Build X.509 SubjectPublicKeyInfo for P-256 uncompressed point
            val header = byteArrayOf(
                0x30, 0x59, // SEQUENCE, length 89
                0x30, 0x13, // SEQUENCE, length 19
                0x06, 0x07, // OID, length 7
                0x2A, 0x86.toByte(), 0x48, 0xCE.toByte(), 0x3D, 0x02, 0x01, // 1.2.840.10045.2.1 (EC)
                0x06, 0x08, // OID, length 8
                0x2A, 0x86.toByte(), 0x48, 0xCE.toByte(), 0x3D, 0x03, 0x01, 0x07, // 1.2.840.10045.3.1.7 (P-256)
                0x03, 0x42, // BIT STRING, length 66
                0x00, // no unused bits
            )
            val encoded = header + bytes
            val keySpec = X509EncodedKeySpec(encoded)
            return KeyFactory.getInstance("EC", "AndroidOpenSSL").generatePublic(keySpec) as ECPublicKey
        }

        private fun ByteArray.padStart32(): ByteArray {
            return if (size >= 32) this
            else ByteArray(32 - size) + this
        }
    }
}
