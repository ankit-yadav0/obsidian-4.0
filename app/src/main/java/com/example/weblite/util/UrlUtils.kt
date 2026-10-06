package com.example.weblite.util

import java.net.URLEncoder

/**
 * URL helpers with no Android dependencies, so they can be unit tested on the JVM.
 */
object UrlUtils {

    /** Single place to change the default search engine. */
    const val SEARCH_URL = "https://www.google.com/search?q="

    // host[:port][/path|?query|#fragment] where host is localhost, an IPv4/IPv6 literal or a dotted
    // domain name (unicode letters allowed, punycode TLDs allowed).
    private val HOST_LIKE = Regex(
        "^(localhost|(\\d{1,3}\\.){3}\\d{1,3}|([\\p{L}\\p{N}][\\p{L}\\p{N}-]*\\.)+(\\p{L}{2,}|xn--[a-zA-Z0-9-]+)|\\[[0-9a-fA-F:]+\\])" +
            "(:\\d{1,5})?([/?#].*)?$"
    )
    private val IPV4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")

    /**
     * Turns whatever the user typed into a URL to load.
     *  - blank input             -> null (caller should ignore it)
     *  - http(s):// URLs         -> https:// (HTTPS-only: cleartext is blocked by Android anyway)
     *  - host / host:port / IP / host with path, query or fragment -> https://...
     *  - everything else (including javascript:, file:, data:, about:) -> a web search, never loaded as-is
     */
    fun normalizeInput(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null
        val lower = trimmed.lowercase()
        return when {
            lower.startsWith("https://") -> trimmed.substring(8).takeIf { it.isNotBlank() }?.let { "https://$it" }
            lower.startsWith("http://") -> trimmed.substring(7).takeIf { it.isNotBlank() }?.let { "https://$it" }
            trimmed.any { it.isWhitespace() } -> search(trimmed)
            HOST_LIKE.matches(trimmed) -> "https://$trimmed"
            else -> search(trimmed)
        }
    }

    private fun search(query: String): String = SEARCH_URL + URLEncoder.encode(query, "UTF-8")

    // Second-level labels used under two-letter country TLDs (example.co.uk, example.com.au, example.ac.in ...).
    private val SECOND_LEVEL_LABELS = setOf(
        "co", "com", "org", "net", "gov", "edu", "ac", "ne", "or", "go", "mil", "nic", "sch", "ltd", "plc", "me"
    )

    // Hosting platforms from the public-suffix list's private section: each customer subdomain is its own site.
    private val PRIVATE_SUFFIXES = listOf(
        "github.io", "gitlab.io", "blogspot.com", "herokuapp.com", "vercel.app", "netlify.app", "pages.dev",
        "workers.dev", "web.app", "firebaseapp.com", "azurewebsites.net", "cloudfront.net", "onrender.com",
        "fly.dev", "glitch.me", "myshopify.com", "wordpress.com", "tumblr.com", "weebly.com", "wixsite.com"
    )

    fun isIpLiteral(host: String): Boolean = IPV4.matches(host) || host.contains(':')

    /**
     * Approximate "site identity" of a host (registrable domain). Not a full public-suffix-list
     * implementation, but handles country second-level domains, private hosting suffixes and IPs.
     */
    fun registrableDomain(host: String): String {
        val h = host.trim().trimEnd('.').lowercase().removePrefix("www.")
        if (h.isEmpty()) return ""
        if (isIpLiteral(h)) return h

        PRIVATE_SUFFIXES.firstOrNull { h == it || h.endsWith(".$it") }?.let { suffix ->
            val head = h.removeSuffix(suffix).trimEnd('.')
            val owner = head.substringAfterLast('.', head)
            return if (owner.isEmpty()) suffix else "$owner.$suffix"
        }

        val labels = h.split('.').filter { it.isNotEmpty() }
        if (labels.size <= 2) return labels.joinToString(".")
        val tld = labels.last()
        val second = labels[labels.size - 2]
        return if (tld.length == 2 && second in SECOND_LEVEL_LABELS) {
            labels.takeLast(3).joinToString(".")
        } else {
            labels.takeLast(2).joinToString(".")
        }
    }

    fun isSameSite(hostA: String, hostB: String): Boolean {
        val a = registrableDomain(hostA)
        return a.isNotEmpty() && a == registrableDomain(hostB)
    }

    /** Removes the "; wv" WebView marker and the device model / build id (a near-unique fingerprint). */
    fun cleanUserAgent(userAgent: String): String =
        userAgent.replace("; wv", "").replace(Regex(";\\s*[^;]+ Build/[^;)]+"), "; K")
}
