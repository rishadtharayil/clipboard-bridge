package com.example.clipboardbridge

import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object CryptoUtils {
    private const val SALT = "ClipboardBridgeSalt"
    private const val ALGORITHM = "AES"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val TAG_LENGTH_BITS = 128
    private const val IV_LENGTH_BYTES = 12

    fun deriveKey(pin: String): SecretKeySpec {
        val salt = SALT.toByteArray(Charsets.UTF_8)
        val spec = PBEKeySpec(pin.toCharArray(), salt, 100000, 256)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
        val keyBytes = factory.generateSecret(spec).encoded
        return SecretKeySpec(keyBytes, ALGORITHM)
    }

    fun encrypt(data: ByteArray, key: SecretKeySpec): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val iv = ByteArray(IV_LENGTH_BYTES)
        java.security.SecureRandom().nextBytes(iv)
        val spec = GCMParameterSpec(TAG_LENGTH_BITS, iv)
        cipher.init(Cipher.ENCRYPT_MODE, key, spec)
        
        val ciphertext = cipher.doFinal(data)
        return iv + ciphertext
    }

    fun decrypt(encryptedPayload: ByteArray, key: SecretKeySpec): ByteArray {
        if (encryptedPayload.size < IV_LENGTH_BYTES) {
            throw IllegalArgumentException("Payload too short")
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val iv = encryptedPayload.copyOfRange(0, IV_LENGTH_BYTES)
        val ciphertext = encryptedPayload.copyOfRange(IV_LENGTH_BYTES, encryptedPayload.size)
        val spec = GCMParameterSpec(TAG_LENGTH_BITS, iv)
        cipher.init(Cipher.DECRYPT_MODE, key, spec)
        return cipher.doFinal(ciphertext)
    }
}
