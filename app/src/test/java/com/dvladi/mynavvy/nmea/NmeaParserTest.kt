package com.dvladi.mynavvy.nmea

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for [NmeaParser]. Fixture sentences come from boatsim's --nmea-server
 * output plus hand-built edge cases (checksums computed per the standard XOR).
 */
class NmeaParserTest {

    // Recompute a sentence's checksum so hand-edited fixtures stay valid.
    private fun cs(body: String): String {
        var x = 0
        for (ch in body) x = x xor ch.code
        return "$" + body + "*" + String.format("%02X", x)
    }

    // --- checksum gate --------------------------------------------------------

    @Test fun `valid boatsim RMC parses`() {
        val u = NmeaParser.parse("\$GPRMC,193535.00,A,3240.3206,N,11714.1000,W,1.6,3.6,160726,,,A*43")
        val fix = u as NavUpdate.Fix
        assertEquals(32.0 + 40.3206 / 60.0, fix.lat, 1e-9)
        assertEquals(-(117.0 + 14.1000 / 60.0), fix.lon, 1e-9)
        assertEquals(1.6, fix.sogKn!!, 1e-9)
        assertEquals(3.6, fix.cogTrue!!, 1e-9)
    }

    @Test fun `wrong checksum rejected`() {
        assertNull(NmeaParser.parse("\$GPRMC,193535.00,A,3240.3206,N,11714.1000,W,1.6,3.6,160726,,,A*44"))
    }

    @Test fun `missing checksum rejected`() {
        assertNull(NmeaParser.parse("\$GPRMC,193535.00,A,3240.3206,N,11714.1000,W,1.6,3.6,160726,,,A"))
    }

    @Test fun `lowercase hex checksum accepted`() {
        val s = cs("SDDPT,7.5,0.0")
        val lower = s.dropLast(2) + s.takeLast(2).lowercase()
        assertEquals(7.5, (NmeaParser.parse(lower) as NavUpdate.Depth).meters, 1e-9)
    }

    @Test fun `surrounding whitespace and CRLF tolerated`() {
        val u = NmeaParser.parse(cs("SDDPT,7.5,0.0") + "\r\n")
        assertEquals(7.5, (u as NavUpdate.Depth).meters, 1e-9)
    }

    @Test fun `garbage and empty rejected`() {
        assertNull(NmeaParser.parse(""))
        assertNull(NmeaParser.parse("not nmea at all"))
        assertNull(NmeaParser.parse("\$*00"))
        assertNull(NmeaParser.parse(cs("GPRMC"))) // type known but no fields
    }

    @Test fun `unknown sentence type ignored`() {
        assertNull(NmeaParser.parse(cs("GPZDA,201530.00,04,07,2002,00,00")))
    }

    @Test fun `proprietary sentence ignored`() {
        assertNull(NmeaParser.parse(cs("PSRF103,00,6,00,0")))
    }

    // --- RMC -------------------------------------------------------------------

    @Test fun `void RMC rejected`() {
        assertNull(NmeaParser.parse(cs("GPRMC,193535.00,V,3240.3206,N,11714.1000,W,,,160726,,,N")))
    }

    @Test fun `RMC with empty SOG-COG fields gives null values`() {
        val fix = NmeaParser.parse(cs("GPRMC,193535.00,A,3240.3206,N,11714.1000,W,,,160726,,,A")) as NavUpdate.Fix
        assertNull(fix.sogKn)
        assertNull(fix.cogTrue)
    }

    @Test fun `RMC southern-eastern hemispheres sign correctly`() {
        val fix = NmeaParser.parse(cs("GPRMC,000000.00,A,3351.0000,S,15112.0000,E,0.0,0.0,010120,,,A")) as NavUpdate.Fix
        assertEquals(-(33.0 + 51.0 / 60.0), fix.lat, 1e-9)
        assertEquals(151.0 + 12.0 / 60.0, fix.lon, 1e-9)
    }

    @Test fun `RMC UTC time and date decode`() {
        val fix = NmeaParser.parse(cs("GPRMC,193535.50,A,3240.3206,N,11714.1000,W,1.6,3.6,160726,,,A")) as NavUpdate.Fix
        // 2026-07-16 19:35:35.500 UTC
        assertEquals(1784230535500L, fix.utcMs)
    }

    @Test fun `mixed talker GNRMC parses like GPRMC`() {
        val fix = NmeaParser.parse(cs("GNRMC,193535.00,A,3240.3206,N,11714.1000,W,1.6,3.6,160726,,,A"))
        assertTrue(fix is NavUpdate.Fix)
    }

    // --- GLL -------------------------------------------------------------------

    @Test fun `GLL parses position only`() {
        val fix = NmeaParser.parse(cs("GPGLL,3240.3206,N,11714.1000,W,193535.00,A,A")) as NavUpdate.Fix
        assertEquals(32.0 + 40.3206 / 60.0, fix.lat, 1e-9)
        assertNull(fix.sogKn)
    }

    @Test fun `void GLL rejected`() {
        assertNull(NmeaParser.parse(cs("GPGLL,3240.3206,N,11714.1000,W,193535.00,V,N")))
    }

    // --- VTG -------------------------------------------------------------------

    @Test fun `VTG parses cog and sog`() {
        val c = NmeaParser.parse(cs("GPVTG,54.7,T,34.4,M,5.5,N,10.2,K,A")) as NavUpdate.Course
        assertEquals(54.7, c.cogTrue!!, 1e-9)
        assertEquals(5.5, c.sogKn!!, 1e-9)
    }

    @Test fun `VTG with empty course fields (drifting) gives nulls`() {
        val c = NmeaParser.parse(cs("GPVTG,,T,,M,0.0,N,0.0,K,A")) as NavUpdate.Course
        assertNull(c.cogTrue)
        assertEquals(0.0, c.sogKn!!, 1e-9)
    }

    @Test fun `VTG with nothing usable rejected`() {
        assertNull(NmeaParser.parse(cs("GPVTG,,T,,M,,N,,K,N")))
    }

    // --- depth ------------------------------------------------------------------

    @Test fun `DPT parses metres and offset`() {
        val d = NmeaParser.parse(cs("SDDPT,7.6,0.4")) as NavUpdate.Depth
        assertEquals(7.6, d.meters, 1e-9)
        assertEquals(0.4, d.offsetM!!, 1e-9)
    }

    @Test fun `DPT without depth rejected`() {
        assertNull(NmeaParser.parse(cs("SDDPT,,")))
    }

    @Test fun `DBT prefers metres field`() {
        val d = NmeaParser.parse(cs("SDDBT,24.9,f,7.6,M,4.1,F")) as NavUpdate.Depth
        assertEquals(7.6, d.meters, 1e-9)
    }

    @Test fun `DBT falls back to feet when metres empty`() {
        val d = NmeaParser.parse(cs("SDDBT,10.0,f,,M,,F")) as NavUpdate.Depth
        assertEquals(3.048, d.meters, 1e-9)
    }

    // --- VHW ---------------------------------------------------------------------

    @Test fun `VHW parses stw and true heading`() {
        val w = NmeaParser.parse(cs("VWVHW,215.0,T,213.0,M,6.2,N,11.5,K")) as NavUpdate.WaterSpeed
        assertEquals(6.2, w.stwKn!!, 1e-9)
        assertEquals(215.0, w.headingTrue!!, 1e-9)
    }

    @Test fun `VHW with no heading still gives stw`() {
        val w = NmeaParser.parse(cs("VWVHW,,T,,M,6.2,N,11.5,K")) as NavUpdate.WaterSpeed
        assertEquals(6.2, w.stwKn!!, 1e-9)
        assertNull(w.headingTrue)
    }

    // --- wind (source B) -----------------------------------------------------------

    @Test fun `MWV relative knots`() {
        val w = NmeaParser.parse(cs("IIMWV,45.0,R,12.5,N,A")) as NavUpdate.Wind
        assertEquals(12.5, w.speedKn, 1e-9)
        assertEquals(45.0, w.angleDeg, 1e-9)
        assertTrue(w.relative)
    }

    @Test fun `MWV true in metres-per-second converts to knots`() {
        val w = NmeaParser.parse(cs("IIMWV,270.0,T,5.0,M,A")) as NavUpdate.Wind
        assertEquals(5.0 * 1.94384, w.speedKn, 1e-6)
        assertTrue(!w.relative)
    }

    @Test fun `invalid MWV rejected`() {
        assertNull(NmeaParser.parse(cs("IIMWV,45.0,R,12.5,N,V")))
    }

    @Test fun `MWD true direction`() {
        val w = NmeaParser.parse(cs("WIMWD,180.0,T,167.0,M,14.0,N,7.2,M")) as NavUpdate.Wind
        assertEquals(14.0, w.speedKn, 1e-9)
        assertEquals(180.0, w.angleDeg, 1e-9)
        assertTrue(!w.relative)
    }

    // --- malformed fields never throw ------------------------------------------------

    @Test fun `truncated and corrupt bodies return null, not throw`() {
        assertNull(NmeaParser.parse(cs("GPRMC,1935")))
        assertNull(NmeaParser.parse(cs("GPRMC,193535.00,A,GARBAGE,N,11714.1000,W,1.6,3.6,160726,,,A")))
        assertNull(NmeaParser.parse(cs("SDDPT,notanumber,0.0")))
    }
}
