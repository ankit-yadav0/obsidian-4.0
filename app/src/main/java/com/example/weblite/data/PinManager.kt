package com.example.weblite.data

import android.content.Context
import java.security.MessageDigest

/**
 * Stores a 6-digit PIN used to lock hidden tabs. Only a salted hash of the
 * PIN is ever written to disk — the PIN itself is never stored in plain
 * text.
 */
class PinManager(context: Context) {

    private val prefs = context.getSharedPreferences("weblite_pin_prefs", Context.MODE_PRIVATE)

    fun isPinSet(): Boolean = prefs.contains(KEY_HASH)

    fun setPin(pin: String) {
        val salt = generateSalt()
        prefs.edit()
            .putString(KEY_SALT, salt)
            .putString(KEY_HASH, hash(pin, salt))
            .apply()
    }

    fun verifyPin(pin: String): Boolean {
        val salt = prefs.getString(KEY_SALT, null) ?: return false
        val storedHash = prefs.getString(KEY_HASH, null) ?: return false
        return hash(pin, salt) == storedHash
    }

    fun clearPin() {
        prefs.edit().remove(KEY_HASH).remove(KEY_SALT).apply()
    }

    private fun generateSalt(): String {
        val bytes = ByteArray(16)
        java.security.SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun hash(pin: String, salt: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest((salt + pin).toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val KEY_HASH = "pin_hash"
        private const val KEY_SALT = "pin_salt"
    }
}
