package com.clipsync.app.crypto

import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM with a `nonce (12 bytes) || ciphertext` wire layout, and
 * HKDF-SHA256 key derivation with info = "clipsync-transport-v1" - both
 * chosen to match `crypto.rs` and `pairing.rs` on the PC exactly. If these
 * two constants ever drift from the Rust side, pairing will silently stop
 * working (each side derives a different key), so change them in both
 * places together or not at all.
 */
object CryptoUtils {
    private const val NONCE_LEN = 12
    private const val TRANSPORT_INFO = "clipsync-transport-v1"

    fun encrypt(key: ByteArray, plaintext: ByteArray): ByteArray {
        val nonce = ByteArray(NONCE_LEN).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        return nonce + cipher.doFinal(plaintext)
    }

    fun decrypt(key: ByteArray, blob: ByteArray): ByteArray {
        require(blob.size > NONCE_LEN) { "ciphertext too short" }
        val nonce = blob.copyOfRange(0, NONCE_LEN)
        val ciphertext = blob.copyOfRange(NONCE_LEN, blob.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        return cipher.doFinal(ciphertext)
    }

    fun deriveTransportKey(sharedSecret: ByteArray): ByteArray {
        val hkdf = HKDFBytesGenerator(SHA256Digest())
        hkdf.init(HKDFParameters(sharedSecret, null, TRANSPORT_INFO.toByteArray(Charsets.UTF_8)))
        val output = ByteArray(32)
        hkdf.generateBytes(output, 0, 32)
        return output
    }
}
