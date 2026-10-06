package com.example.weblite.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackerBlocklistTest {

    @Test
    fun blocksKnownTrackersAndTheirSubdomains() {
        assertTrue(TrackerBlocklist.isBlockedRequest("www.google-analytics.com", "/analytics.js"))
        assertTrue(TrackerBlocklist.isBlockedRequest("connect.facebook.net", "/en_US/sdk.js"))
        assertTrue(TrackerBlocklist.isBlockedRequest("ad.doubleclick.net", "/x"))
        assertTrue(TrackerBlocklist.isBlockedHost("segment.com"))
        assertTrue(TrackerBlocklist.isBlockedHost("cdn.segment.com"))
    }

    @Test
    fun blocksTrackingEndpointsOnLegitimateHosts() {
        assertTrue(TrackerBlocklist.isBlockedRequest("www.facebook.com", "/tr"))
        assertTrue(TrackerBlocklist.isBlockedRequest("www.facebook.com", "/tr/"))
        assertTrue(TrackerBlocklist.isBlockedRequest("www.bing.com", "/bat.js"))
        assertTrue(TrackerBlocklist.isBlockedRequest("mc.yandex.ru", "/metrika/watch.js"))
        assertTrue(TrackerBlocklist.isBlockedRequest("example.com", "/pagead/ads"))
    }

    @Test
    fun doesNotBlockLegitimateLookalikes() {
        assertFalse(TrackerBlocklist.isBlockedRequest("www.facebook.com", "/trending/"))
        assertFalse(TrackerBlocklist.isBlockedRequest("www.facebook.com", "/translations/"))
        assertFalse(TrackerBlocklist.isBlockedRequest("bigsegment.com", "/home"))
        assertFalse(TrackerBlocklist.isBlockedRequest("myadvertising.com", "/"))
        assertFalse(TrackerBlocklist.isBlockedRequest("blog.example.com", "/docs/googleanalytics-setup-guide"))
        assertFalse(TrackerBlocklist.isBlockedRequest("www.wikipedia.org", "/"))
        assertFalse(TrackerBlocklist.isBlockedRequest("www.youtube.com", "/watch"))
        assertFalse(TrackerBlocklist.isBlockedHost("bigsegment.com"))
        assertFalse(TrackerBlocklist.isBlockedHost(""))
    }
}
