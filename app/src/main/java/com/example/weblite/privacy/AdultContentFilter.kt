package com.example.weblite.privacy

import android.content.Context

/**
 * Blocks known adult/pornography domains, similar in spirit to the
 * existing ad/tracker blocklist in AppWebView but as its own independent
 * toggle — a user might want ads blocked but not adult content, or vice
 * versa, so this is deliberately not tied to the per-tab "shield" switch.
 *
 * This is host-based filtering (like OpenDNS FamilyShield / most parental
 * control apps), not a content classifier — it can't catch every adult
 * site on the internet, only ones on this list or user-added ones. It's
 * a reasonable baseline, not a guarantee.
 */
class AdultContentFilter(context: Context) {

    private val prefs = context.getSharedPreferences("weblite_content_filter_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_ENABLED = "adult_block_enabled"
        private const val KEY_CUSTOM_DOMAINS = "adult_block_custom_domains"

        // A starter set of well-known adult site domains. Not exhaustive —
        // see isBlockedHost() below for the custom-domain extension point,
        // and the settings UI lets the user add more.
        private val BUILT_IN_BLOCKLIST = setOf(
            "pornhub.com", "xvideos.com", "xnxx.com", "xhamster.com",
            "redtube.com", "youporn.com", "tube8.com", "spankbang.com",
            "chaturbate.com", "livejasmin.com", "onlyfans.com",
            "brazzers.com", "stripchat.com", "bongacams.com",
            "motherless.com", "eporner.com", "txxx.com", "hqporner.com",
            "porntrex.com", "thumbzilla.com", "beeg.com", "fapdu.com",
            "porn.com", "sex.com", "xhamster2.com", "xxvideos.com",
            "hentaihaven.xxx", "rule34.xxx", "e-hentai.org"
        )

        // Keyword-based fallback: catches many adult sites not in the
        // fixed list above (mirror/clone domains especially), at the cost
        // of occasional false positives on unrelated sites that happen to
        // share a word in their domain name. Kept short and specific on
        // purpose to limit that.
        private val HOST_KEYWORDS = listOf("porn", "xxx", "xvideos", "hentai")
    }

    fun isEnabled(): Boolean = prefs.getBoolean(KEY_ENABLED, true) // safe default: on

    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun customDomains(): Set<String> = prefs.getStringSet(KEY_CUSTOM_DOMAINS, emptySet()) ?: emptySet()

    fun addCustomDomain(domain: String) {
        val cleaned = domain.trim().lowercase().removePrefix("www.")
        if (cleaned.isBlank()) return
        val updated = customDomains() + cleaned
        prefs.edit().putStringSet(KEY_CUSTOM_DOMAINS, updated).apply()
    }

    fun removeCustomDomain(domain: String) {
        val updated = customDomains() - domain.trim().lowercase().removePrefix("www.")
        prefs.edit().putStringSet(KEY_CUSTOM_DOMAINS, updated).apply()
    }

    /** @param host the request's hostname, e.g. request.url.host, already lowercased by the caller */
    fun isBlockedHost(host: String): Boolean {
        if (host.isBlank()) return false
        if (!isEnabled()) return false

        val bare = host.removePrefix("www.")

        if (BUILT_IN_BLOCKLIST.any { bare == it || bare.endsWith(".$it") }) return true
        if (customDomains().any { bare == it || bare.endsWith(".$it") }) return true
        if (HOST_KEYWORDS.any { bare.contains(it) }) return true

        return false
    }
}
