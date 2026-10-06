package com.example.weblite.util

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * PIN hashing (PBKDF2-HMAC-SHA256) and the brute-force lockout policy, free of Android classes.
 * A plain salted SHA-256 of a 6-digit PIN can be brute-forced in well under a second; PBKDF2 makes
 * every guess expensive, and the lockout slows down guessing on the device itself.
 */
object PinHasher {

    const val ITERATIONS = 30_000
    private const val ALGORITHM = "HmacSHA256"

    fun newSalt(): ByteArray = ByteArray(16).also { SecureRandom().nextBytes(it) }

    /** PBKDF2-HMAC-SHA256, one 32-byte block (RFC 8018). */
    fun hash(pin: String, salt: ByteArray, iterations: Int = ITERATIONS): ByteArray {
        require(pin.isNotEmpty()) { "PIN must not be empty" }
        require(iterations >= 1) { "iterations must be >= 1" }
        val mac = Mac.getInstance(ALGORITHM)
        mac.init(SecretKeySpec(pin.toByteArray(Charsets.UTF_8), ALGORITHM))
        mac.update(salt)
        mac.update(byteArrayOf(0, 0, 0, 1))
        var u = mac.doFinal()
        val t = u.copyOf()
        for (i in 1 until iterations) {
            u = mac.doFinal(u)
            for (j in t.indices) t[j] = (t[j].toInt() xor u[j].toInt()).toByte()
        }
        return t
    }

    /** Hash scheme used before PBKDF2 (salt string + pin, SHA-256). Only used to verify and then upgrade old PINs. */
    fun legacySha256Hex(pin: String, saltHex: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest((saltHex + pin).toByteArray(Charsets.UTF_8))
        return toHex(bytes)
    }

    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)

    fun toHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    fun fromHex(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "odd hex length" }
        return ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }

    // --- brute-force lockout -------------------------------------------------------------

    const val ATTEMPTS_PER_ROUND = 5
    private const val BASE_LOCK_MS = 30_000L
    const val MAX_LOCK_MS = 15 * 60_000L

    /** Lock duration applied right after the given total number of consecutive failures (0 = no lock). */
    fun lockDurationMs(failedAttempts: Int): Long {
        if (failedAttempts <= 0 || failedAttempts % ATTEMPTS_PER_ROUND != 0) return 0L
        val round = failedAttempts / ATTEMPTS_PER_ROUND          // 1, 2, 3, ...
        val shift = (round - 1).coerceAtMost(10)
        return (BASE_LOCK_MS shl shift).coerceAtMost(MAX_LOCK_MS)
    }

    fun attemptsLeftInRound(failedAttempts: Int): Int =
        ATTEMPTS_PER_ROUND - (failedAttempts % ATTEMPTS_PER_ROUND)
}
