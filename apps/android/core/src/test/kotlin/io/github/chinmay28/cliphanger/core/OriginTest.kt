package io.github.chinmay28.cliphanger.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OriginTest {

    private fun parsed(input: String) = Origin.parse(input).getOrThrow().toString()

    @Test
    fun aBareHostIsClipHangersDefaultPortOverHttp() {
        assertEquals("http://nvr.local:8124", parsed("nvr.local"))
        assertEquals("http://192.168.1.20:8124", parsed(" 192.168.1.20 "))
        assertEquals("http://192.168.1.20:9000", parsed("192.168.1.20:9000"))
    }

    @Test
    fun aSchemeSpelledOutKeepsItsOwnDefaultPort() {
        assertEquals("http://nvr.local", parsed("http://nvr.local"))
        assertEquals("https://clips.example.com", parsed("https://clips.example.com"))
        assertEquals("https://clips.example.com:8443", parsed("HTTPS://Clips.Example.com:8443/"))
    }

    @Test
    fun pathQueryAndFragmentAreDropped() {
        assertEquals("https://clips.example.com", parsed("https://clips.example.com/some/folder?x=1#y"))
    }

    @Test
    fun ipv6IsBracketed() {
        val o = Origin.parse("http://[fd00::20]:8124").getOrThrow()
        assertEquals("fd00::20", o.host)
        assertEquals("http://[fd00::20]:8124", o.toString())
        assertEquals("http://[fd00::20]:8124/api/health", o.url("api/health"))
    }

    @Test
    fun badAddressesAreRefusedWithAReason() {
        for (bad in listOf("", "   ", "ftp://nvr.local", "https://user:pw@clips.example.com", "http://", "http://nvr.local:99999")) {
            assertTrue("accepted $bad", Origin.parse(bad).isFailure)
        }
        assertTrue(Origin.parse("").exceptionOrNull()!!.message!!.isNotBlank())
    }

    @Test
    fun containsIsSameOrigin() {
        val o = Origin.parse("http://nvr.local:8124").getOrThrow()
        assertTrue(o.contains("http://nvr.local:8124/api/clips?day=2026-11-16"))
        assertTrue(o.contains("http://NVR.local:8124"))
        assertFalse(o.contains("http://nvr.local:8125/"))
        assertFalse(o.contains("https://nvr.local:8124/"))
        assertFalse(o.contains("http://nvr.local.evil.test:8124/"))
        assertFalse(o.contains("http://evil.test/?http://nvr.local:8124"))
        assertFalse(o.contains("not a url"))
    }

    @Test
    fun ofReadsDefaultPorts() {
        assertEquals(Origin("https", "a.test", 443), Origin.of("https://a.test/x"))
        assertEquals(Origin("http", "a.test", 80), Origin.of("http://a.test"))
        assertNull(Origin.of("blob:http://a.test/123"))
        assertNull(Origin.of("mailto:me@a.test"))
    }
}
