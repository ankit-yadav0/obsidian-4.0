package com.example.weblite.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlUtilsTest {

    private fun search(q: String) = UrlUtils.SEARCH_URL + q

    @Test
    fun blankInputIsIgnored() {
        assertNull(UrlUtils.normalizeInput(""))
        assertNull(UrlUtils.normalizeInput("   "))
        assertNull(UrlUtils.normalizeInput("https://"))
    }

    @Test
    fun bareDomainsGetHttps() {
        assertEquals("https://example.com", UrlUtils.normalizeInput("example.com"))
        assertEquals("https://www.example.co.uk/path", UrlUtils.normalizeInput("www.example.co.uk/path"))
        assertEquals("https://Example.COM", UrlUtils.normalizeInput("Example.COM"))
    }

    @Test
    fun urlsWithQueryPortFragmentAreNotTurnedIntoSearches() {
        assertEquals("https://example.com/search?q=cats", UrlUtils.normalizeInput("example.com/search?q=cats"))
        assertEquals("https://example.com:8080", UrlUtils.normalizeInput("example.com:8080"))
        assertEquals("https://example.com#top", UrlUtils.normalizeInput("example.com#top"))
        assertEquals("https://localhost:3000", UrlUtils.normalizeInput("localhost:3000"))
        assertEquals("https://192.168.1.1", UrlUtils.normalizeInput("192.168.1.1"))
        assertEquals("https://192.168.1.1:8000/admin", UrlUtils.normalizeInput("192.168.1.1:8000/admin"))
        assertEquals("https://m\u00fcnchen.de", UrlUtils.normalizeInput("m\u00fcnchen.de"))
    }

    @Test
    fun explicitHttpIsUpgradedAndSchemeCaseIsIgnored() {
        assertEquals("https://example.com/a", UrlUtils.normalizeInput("http://example.com/a"))
        assertEquals("https://EXAMPLE.COM", UrlUtils.normalizeInput("HTTP://EXAMPLE.COM"))
        assertEquals("https://example.com", UrlUtils.normalizeInput("https://example.com"))
    }

    @Test
    fun dangerousSchemesNeverLoadAsIs() {
        assertEquals(search("javascript%3Aalert%281%29"), UrlUtils.normalizeInput("javascript:alert(1)"))
        assertEquals(search("file%3A%2F%2F%2Fsdcard%2Fa.txt"), UrlUtils.normalizeInput("file:///sdcard/a.txt"))
        assertTrue(UrlUtils.normalizeInput("data:text/html,hi")!!.startsWith(UrlUtils.SEARCH_URL))
        assertTrue(UrlUtils.normalizeInput("about:blank")!!.startsWith(UrlUtils.SEARCH_URL))
    }

    @Test
    fun plainTextBecomesASearch() {
        assertEquals(search("cats+and+dogs"), UrlUtils.normalizeInput("cats and dogs"))
        assertEquals(search("cats"), UrlUtils.normalizeInput("cats"))
    }

    @Test
    fun registrableDomain() {
        assertEquals("bbc.co.uk", UrlUtils.registrableDomain("www.bbc.co.uk"))
        assertEquals("bbc.co.uk", UrlUtils.registrableDomain("news.bbc.co.uk"))
        assertEquals("youtube.com", UrlUtils.registrableDomain("m.youtube.com"))
        assertEquals("example.com.au", UrlUtils.registrableDomain("a.b.example.com.au"))
        assertEquals("iitb.ac.in", UrlUtils.registrableDomain("www.iitb.ac.in"))
        assertEquals("example.com", UrlUtils.registrableDomain("example.com."))
        assertEquals("alice.github.io", UrlUtils.registrableDomain("alice.github.io"))
        assertEquals("alice.github.io", UrlUtils.registrableDomain("docs.alice.github.io"))
        assertEquals("github.io", UrlUtils.registrableDomain("github.io"))
        assertEquals("192.168.1.1", UrlUtils.registrableDomain("192.168.1.1"))
        assertEquals("", UrlUtils.registrableDomain(""))
    }

    @Test
    fun sameSiteDecisions() {
        assertTrue(UrlUtils.isSameSite("m.youtube.com", "www.youtube.com"))
        assertTrue(UrlUtils.isSameSite("accounts.google.com", "www.google.com"))
        assertFalse(UrlUtils.isSameSite("youtu.be", "www.youtube.com"))
        assertFalse(UrlUtils.isSameSite("example.com", "example.org"))
        assertFalse(UrlUtils.isSameSite("alice.github.io", "bob.github.io"))
        assertFalse(UrlUtils.isSameSite("a.blogspot.com", "b.blogspot.com"))
        assertFalse(UrlUtils.isSameSite("10.0.1.1", "192.168.1.1"))
        assertFalse(UrlUtils.isSameSite("", ""))
    }

    @Test
    fun cleanUserAgent() {
        val ua = "Mozilla/5.0 (Linux; Android 11; RMX2185 Build/RP1A.200720.011; wv) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36"
        val cleaned = UrlUtils.cleanUserAgent(ua)
        assertFalse(cleaned.contains("wv"))
        assertFalse(cleaned.contains("RMX2185"))
        assertTrue(cleaned.contains("(Linux; Android 11; K)"))
    }
}
