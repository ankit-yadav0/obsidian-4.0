package com.example.weblite.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PinHasherTest {

    private val salt = "salt".toByteArray()

    @Test
    fun pbkdf2MatchesPublishedVectors() {
        assertEquals(
            "120fb6cffcf8b32c43e7225256c4f837a86548c92ccc35480805987cb70be17b",
            PinHasher.toHex(PinHasher.hash("password", salt, 1))
        )
        assertEquals(
            "ae4d0c95af6b46d32d0adff928f06dd02a303f8ef3c251dfd6e2d85a95474c43",
            PinHasher.toHex(PinHasher.hash("password", salt, 2))
        )
        assertEquals(
            "c5e478d59288c841aa530db6845c4c8d962893a001ce4e11a4963873aa98134a",
            PinHasher.toHex(PinHasher.hash("password", salt, 4096))
        )
    }

    @Test
    fun hashDependsOnPinAndSalt() {
        val s1 = PinHasher.newSalt()
        val s2 = PinHasher.newSalt()
        val a = PinHasher.hash("123456", s1)
        assertTrue(PinHasher.constantTimeEquals(a, PinHasher.hash("123456", s1)))
        assertFalse(PinHasher.constantTimeEquals(a, PinHasher.hash("123457", s1)))
        assertFalse(PinHasher.constantTimeEquals(a, PinHasher.hash("123456", s2)))
    }

    @Test
    fun hexRoundTrip() {
        val bytes = PinHasher.newSalt()
        assertEquals(PinHasher.toHex(bytes), PinHasher.toHex(PinHasher.fromHex(PinHasher.toHex(bytes))))
    }

    @Test
    fun legacyHashStillVerifiable() {
        assertEquals(
            "f8e4263540dca52a6c03c32982c6afe5876b5507c101ad79f1b77223a951695a",
            PinHasher.legacySha256Hex("123456", "00ff")
        )
    }

    @Test
    fun lockoutGrowsAndIsCapped() {
        assertEquals(0L, PinHasher.lockDurationMs(0))
        assertEquals(0L, PinHasher.lockDurationMs(4))
        assertEquals(30_000L, PinHasher.lockDurationMs(5))
        assertEquals(0L, PinHasher.lockDurationMs(7))
        assertEquals(60_000L, PinHasher.lockDurationMs(10))
        assertEquals(120_000L, PinHasher.lockDurationMs(15))
        assertEquals(240_000L, PinHasher.lockDurationMs(20))
        assertEquals(PinHasher.MAX_LOCK_MS, PinHasher.lockDurationMs(30))
        assertEquals(PinHasher.MAX_LOCK_MS, PinHasher.lockDurationMs(500))
    }

    @Test
    fun attemptsLeftCountsDownPerRound() {
        assertEquals(5, PinHasher.attemptsLeftInRound(0))
        assertEquals(4, PinHasher.attemptsLeftInRound(1))
        assertEquals(1, PinHasher.attemptsLeftInRound(4))
        assertEquals(5, PinHasher.attemptsLeftInRound(5))
    }
}
