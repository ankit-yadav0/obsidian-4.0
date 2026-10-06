package com.example.weblite.privacy

import android.content.Context

/**
 * Some sites' Cloudflare (or similar) bot-check fails inside this app's
 * privacy-hardened WebView — specifically, blocking third-party cookies
 * and WebRTC (both done deliberately for privacy, see WebViewSetup.kt and
 * PrivacyScripts.kt) can
 * make Cloudflare's Turnstile/managed-challenge widget fail to complete,
 * which blocks login on some sites.
 *
 * Rather than weakening that hardening app-wide, this lets specific
 * domains be handed off to the user's own external browser instead —
 * the user picks which one via Android's normal app chooser, which acts
 * as the "permission" grant for that handoff.
 */
class ExternalHandoffManager(context: Context) {

    private val prefs = context.getSharedPreferences("weblite_external_handoff_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_DOMAINS = "always_open_externally_domains"
    }

    fun alwaysOpenExternally(): Set<String> = prefs.getStringSet(KEY_DOMAINS, emptySet()) ?: emptySet()

    fun shouldOpenExternally(host: String): Boolean {
        if (host.isBlank()) return false
        val bare = host.trim().lowercase().removePrefix("www.")
        return alwaysOpenExternally().any { bare == it || bare.endsWith(".$it") }
    }

    fun addDomain(host: String) {
        val bare = host.trim().lowercase().removePrefix("www.")
        if (bare.isBlank()) return
        prefs.edit().putStringSet(KEY_DOMAINS, alwaysOpenExternally() + bare).apply()
    }

    fun removeDomain(host: String) {
        val bare = host.trim().lowercase().removePrefix("www.")
        prefs.edit().putStringSet(KEY_DOMAINS, alwaysOpenExternally() - bare).apply()
    }
}
