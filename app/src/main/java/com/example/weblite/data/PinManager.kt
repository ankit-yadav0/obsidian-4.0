package com.example.weblite.data

import android.content.Context
import com.example.weblite.util.PinHasher

/**
 * Stores the 6-digit PIN that locks hidden tabs. Only a salted PBKDF2 hash is ever written to disk,
 * and repeated wrong guesses lock the PIN entry for an increasing amount of time (the counters live in
 * SharedPreferences, so restarting the app does not reset them).
 */
class PinManager(context: Context) {

    sealed class VerifyResult {
        object Success : VerifyResult()
        data class Wrong(val attemptsLeft: Int) : VerifyResult()
        data class Locked(val remainingMs: Long) : VerifyResult()
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isPinSet(): Boolean = prefs.contains(KEY_HASH)

    fun setPin(pin: String) {
        val salt = PinHasher.newSalt()
        prefs.edit()
            .putInt(KEY_VERSION, VERSION_PBKDF2)
            .putString(KEY_SALT, PinHasher.toHex(salt))
            .putString(KEY_HASH, PinHasher.toHex(PinHasher.hash(pin, salt)))
            .putInt(KEY_FAILS, 0)
            .putLong(KEY_LOCK_UNTIL, 0L)
            .apply()
    }

    /** Milliseconds until PIN entry is allowed again (0 = not locked). */
    fun lockRemainingMs(now: Long = System.currentTimeMillis()): Long {
        val until = prefs.getLong(KEY_LOCK_UNTIL, 0L)
        // coerceAtMost guards against the system clock having been moved backwards.
        return (until - now).coerceIn(0L, PinHasher.MAX_LOCK_MS)
    }

    fun verifyPin(pin: String, now: Long = System.currentTimeMillis()): VerifyResult {
        val remaining = lockRemainingMs(now)
        if (remaining > 0) return VerifyResult.Locked(remaining)

        val saltHex = prefs.getString(KEY_SALT, null)
        val storedHex = prefs.getString(KEY_HASH, null)
        if (saltHex == null || storedHex == null || pin.isEmpty()) return registerFailure(now)

        val isPbkdf2 = prefs.getInt(KEY_VERSION, VERSION_LEGACY) == VERSION_PBKDF2
        val matches = try {
            if (isPbkdf2) {
                PinHasher.constantTimeEquals(
                    PinHasher.fromHex(storedHex),
                    PinHasher.hash(pin, PinHasher.fromHex(saltHex))
                )
            } else {
                PinHasher.legacySha256Hex(pin, saltHex) == storedHex
            }
        } catch (e: Exception) {
            false
        }
        if (!matches) return registerFailure(now)

        if (isPbkdf2) {
            prefs.edit().putInt(KEY_FAILS, 0).putLong(KEY_LOCK_UNTIL, 0L).apply()
        } else {
            setPin(pin) // transparently upgrade an old SHA-256 PIN to PBKDF2
        }
        return VerifyResult.Success
    }

    private fun registerFailure(now: Long): VerifyResult {
        val fails = prefs.getInt(KEY_FAILS, 0) + 1
        val lockMs = PinHasher.lockDurationMs(fails)
        prefs.edit()
            .putInt(KEY_FAILS, fails)
            .putLong(KEY_LOCK_UNTIL, if (lockMs > 0) now + lockMs else 0L)
            .apply()
        return if (lockMs > 0) VerifyResult.Locked(lockMs)
        else VerifyResult.Wrong(PinHasher.attemptsLeftInRound(fails))
    }

    /** Removes the PIN completely (used by "Forgot PIN", which also deletes the hidden tabs). */
    fun clearPin() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val PREFS_NAME = "weblite_pin_prefs"
        private const val KEY_HASH = "pin_hash"
        private const val KEY_SALT = "pin_salt"
        private const val KEY_VERSION = "pin_version"
        private const val KEY_FAILS = "pin_failed_attempts"
        private const val KEY_LOCK_UNTIL = "pin_lock_until"
        private const val VERSION_LEGACY = 1
        private const val VERSION_PBKDF2 = 2
    }
}
