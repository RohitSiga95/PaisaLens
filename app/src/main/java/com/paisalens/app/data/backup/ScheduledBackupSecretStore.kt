package com.paisalens.app.data.backup

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Stores only an Android-Keystore-encrypted backup passphrase in app-private preferences. */
class ScheduledBackupSecretStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    @Synchronized
    fun store(passphrase: CharArray) {
        try {
            require(passphrase.size >= MIN_PASSPHRASE_LENGTH) {
                "Backup passphrase must contain at least $MIN_PASSPHRASE_LENGTH characters"
            }
            val plain = passphrase.toUtf8Bytes()
            try {
                val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                    init(Cipher.ENCRYPT_MODE, getOrCreateKey())
                }
                val payload = cipher.iv + cipher.doFinal(plain)
                try {
                    check(preferences.edit()
                        .putString(KEY_ENCRYPTED_PASSPHRASE, Base64.getEncoder().encodeToString(payload))
                        .commit()) { "Could not securely save the scheduled backup password" }
                } finally {
                    payload.fill(0)
                }
            } finally {
                plain.fill(0)
            }
        } finally {
            passphrase.fill('\u0000')
        }
    }

    @Synchronized
    fun load(): CharArray? {
        val encoded = preferences.getString(KEY_ENCRYPTED_PASSPHRASE, null)
            ?.takeIf(String::isNotBlank)
            ?: return null
        val payload = Base64.getDecoder().decode(encoded)
        require(payload.size > IV_BYTES) { "Stored backup password is damaged" }
        val iv = payload.copyOfRange(0, IV_BYTES)
        val encrypted = payload.copyOfRange(IV_BYTES, payload.size)
        val plain = try {
            Cipher.getInstance(TRANSFORMATION).run {
                init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(TAG_BITS, iv))
                doFinal(encrypted)
            }
        } finally {
            payload.fill(0)
            iv.fill(0)
            encrypted.fill(0)
        }
        return try {
            plain.toUtf8Chars()
        } finally {
            plain.fill(0)
        }
    }

    fun hasSecret(): Boolean = !preferences.getString(KEY_ENCRYPTED_PASSPHRASE, null).isNullOrBlank()

    fun clear() {
        check(preferences.edit().remove(KEY_ENCRYPTED_PASSPHRASE).commit()) {
            "Could not clear the scheduled backup password"
        }
    }

    private fun getOrCreateKey(): SecretKey = synchronized(KEY_LOCK) {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return@synchronized it }
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            generateKey()
        }
    }

    private fun CharArray.toUtf8Bytes(): ByteArray {
        val buffer = Charsets.UTF_8.encode(CharBuffer.wrap(this))
        return ByteArray(buffer.remaining()).also(buffer::get)
    }

    private fun ByteArray.toUtf8Chars(): CharArray {
        val buffer = Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(this))
        return CharArray(buffer.remaining()).also(buffer::get)
    }

    private companion object {
        const val PREFERENCES_NAME = "paisalens.scheduled_backup_secret"
        const val KEY_ENCRYPTED_PASSPHRASE = "encrypted_passphrase_v1"
        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val KEY_ALIAS = "paisalens.scheduled_backup.passphrase.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
        const val MIN_PASSPHRASE_LENGTH = 8
        val KEY_LOCK = Any()
    }
}
