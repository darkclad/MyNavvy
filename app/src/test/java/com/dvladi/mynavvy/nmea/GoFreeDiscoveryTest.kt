package com.dvladi.mynavvy.nmea

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Pure-JVM tests for [GoFreeDiscovery.parseAnnounce] (the network side needs hardware). */
class GoFreeDiscoveryTest {

    // Shape observed from Navico GoFree announces / sent by boatsim --nmea-server.
    private val goodAnnounce = """
        {"Name":"GO7","IP":"192.168.0.1","Model":"GO7 XSE","BrandID":"Simrad",
         "Services":[
           {"Service":"nmea-0183","Version":"1","Port":10110},
           {"Service":"http","Version":"1","Port":80}
         ]}
    """.trimIndent()

    @Test fun `announce with nmea service parses`() {
        val s = GoFreeDiscovery.parseAnnounce(goodAnnounce, "192.168.0.99")
        assertEquals(NmeaSource("192.168.0.1", 10110), s)
    }

    @Test fun `missing IP field falls back to sender address`() {
        val json = """{"Name":"X","Services":[{"Service":"nmea-0183","Port":2053}]}"""
        assertEquals(NmeaSource("192.168.0.7", 2053), GoFreeDiscovery.parseAnnounce(json, "192.168.0.7"))
    }

    @Test fun `no sender and no IP rejected`() {
        val json = """{"Name":"X","Services":[{"Service":"nmea-0183","Port":2053}]}"""
        assertNull(GoFreeDiscovery.parseAnnounce(json, null))
    }

    @Test fun `nmea-2000 service does not count`() {
        val json = """{"IP":"192.168.0.1","Services":[
            {"Service":"nmea-2000","Port":2053},{"Service":"nmea2k","Port":2054}]}"""
        assertNull(GoFreeDiscovery.parseAnnounce(json, null))
    }

    @Test fun `picks the nmea service among others`() {
        val json = """{"IP":"192.168.4.1","Services":[
            {"Service":"http","Port":80},
            {"Service":"NMEA-0183","Port":10110}]}"""
        assertEquals(NmeaSource("192.168.4.1", 10110), GoFreeDiscovery.parseAnnounce(json, null))
    }

    @Test fun `garbage, non-json and empty rejected`() {
        assertNull(GoFreeDiscovery.parseAnnounce("", "1.2.3.4"))
        assertNull(GoFreeDiscovery.parseAnnounce("not json", "1.2.3.4"))
        assertNull(GoFreeDiscovery.parseAnnounce("{}", "1.2.3.4"))
        assertNull(GoFreeDiscovery.parseAnnounce("""{"Services":[]}""", "1.2.3.4"))
    }

    @Test fun `invalid port rejected`() {
        val json = """{"IP":"192.168.0.1","Services":[{"Service":"nmea-0183","Port":0}]}"""
        assertNull(GoFreeDiscovery.parseAnnounce(json, null))
        val json2 = """{"IP":"192.168.0.1","Services":[{"Service":"nmea-0183","Port":99999}]}"""
        assertNull(GoFreeDiscovery.parseAnnounce(json2, null))
    }
}
