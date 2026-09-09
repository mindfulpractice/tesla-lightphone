package com.thelightphone.tool.tesla

import android.util.Base64
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts and decrypts strings using the hardware-backed TeslaKeystore.
 * Modeled after the LP Authenticator's TotpSecretCipher.
 *
 * Encrypted output is Base64-encoded for safe storage in DataStore.
 */
internal class TeslaTokenCipher(
    private val keystore: TeslaKeystore = TeslaKeystore(),
) {
    init {
        keystore.ensureKey()
    }

    fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keystore.getSecretKey())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext.toByteArray(StandardCharsets.UTF_8))
        val blob = ByteBuffer.allocate(iv.size + ciphertext.size)
            .put(iv)
            .put(ciphertext)
            .array()
        return Base64.encodeToString(blob, Base64.NO_WRAP)
    }

    fun decrypt(encoded: String): String {
        val blob = Base64.decode(encoded, Base64.NO_WRAP)
        val buffer = ByteBuffer.wrap(blob)
        val iv = ByteArray(GCM_IV_LENGTH)
        buffer.get(iv)
        val ciphertext = ByteArray(buffer.remaining())
        buffer.get(ciphertext)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            keystore.getSecretKey(),
            GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv),
        )
        return String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8)
    }

    companion object {
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_LENGTH = 12
        private const val GCM_TAG_LENGTH_BITS = 128
    }
}
