package com.example.weblite.util

/**
 * Ad / tracker matching, kept free of Android classes so it can be unit tested.
 *
 * Matching is done on whole host labels (host == entry or host ends with ".entry"),
 * never on a raw substring - the old `host.contains(entry)` also blocked unrelated
 * sites such as "bigsegment.com" or "myadvertising.com".
 */
object TrackerBlocklist {

    private val BLOCKED_HOSTS = listOf(
    "doubleclick.net",
    "google-analytics.com",
    "googlesyndication.com",
    "googletagservices.com",
    "googletagmanager.com",
    "adnxs.com",
    "popads.net",
    "popcash.net",
    "propellerads.com",
    "exoclick.com",
    "adroll.com",
    "scorecardresearch.com",
    "taboola.com",
    "outbrain.com",
    // Analytics / behavioral trackers
    "connect.facebook.net",
    "hotjar.com",
    "mixpanel.com",
    "segment.io",
    "segment.com",
    "amplitude.com",
    "fullstory.com",
    "clarity.ms",
    "quantserve.com",
    "criteo.com",
    "criteo.net",
    "moatads.com",
    "adsystem.com",
    "adservice.google.com",
    "amazon-adsystem.com",
    "advertising.com",
    "adcolony.com",
    "mopub.com",
    "chartboost.com",
    "unityads.unity3d.com",
    "popunder.net",
    "juicyads.com",
    "adsterra.com",
    "revcontent.com",
    "mgid.com"
    )

    /** Tracking endpoints that live on otherwise legitimate hosts: (host suffix, path). */
    private val BLOCKED_ENDPOINTS = listOf(
        "facebook.com" to "/tr",
        "bing.com" to "/bat.js",
        "yandex.ru" to "/metrika"
    )

    /** Ad-serving path segments, checked against the URL *path* only (never the query string). */
    private val BLOCKED_PATH_SEGMENTS = listOf("/adservice/", "/pagead/")

    private fun hostMatches(host: String, suffix: String): Boolean =
        host == suffix || host.endsWith(".$suffix")

    /** True if navigating to this host is an ad / tracker redirect. */
    fun isBlockedHost(host: String): Boolean {
        val h = host.trim().trimEnd('.').lowercase()
        if (h.isEmpty()) return false
        return BLOCKED_HOSTS.any { hostMatches(h, it) }
    }

    /** True if a sub-resource request (script, pixel, XHR...) should be blocked. */
    fun isBlockedRequest(host: String, path: String): Boolean {
        val h = host.trim().trimEnd('.').lowercase()
        if (h.isEmpty()) return false
        if (BLOCKED_HOSTS.any { hostMatches(h, it) }) return true
        val p = path.lowercase()
        if (BLOCKED_ENDPOINTS.any { (hs, ep) -> hostMatches(h, hs) && (p == ep || p.startsWith("$ep/")) }) return true
        return BLOCKED_PATH_SEGMENTS.any { p.contains(it) }
    }
}
