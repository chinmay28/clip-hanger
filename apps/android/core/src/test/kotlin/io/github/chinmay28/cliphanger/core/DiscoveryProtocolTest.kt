package io.github.chinmay28.cliphanger.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DiscoveryProtocolTest {
    @Test
    fun readsAnAnswer() {
        val f = DiscoveryProtocol.parse("""{"service":"clip-hanger","name":"nvr","port":8124,"version":"v2026.11.12"}""", "/192.168.1.20")!!
        assertEquals("nvr", f.name)
        assertEquals("http://192.168.1.20:8124", f.origin.toString())
        assertEquals("v2026.11.12", f.version)
    }

    @Test
    fun aNamelessServerIsListedByItsAddressAndHttpsIsHonoured() {
        val f = DiscoveryProtocol.parse("""{"service":"clip-hanger","port":443,"secure":true}""", "fe80::1%wlan0")!!
        assertEquals("fe80::1", f.name)
        assertEquals("https://[fe80::1]", f.origin.toString())
    }

    @Test
    fun anythingElseIsIgnored() {
        for (body in listOf("", "nope", """{"service":"sand-vault","port":8123}""", """{"service":"clip-hanger","port":0}""", """{"service":"clip-hanger","port":70000}""")) {
            assertNull(body, DiscoveryProtocol.parse(body, "192.168.1.20"))
        }
        assertNull(DiscoveryProtocol.parse("""{"service":"clip-hanger","port":8124}""", ""))
    }

    @Test
    fun matchesTheServer() {
        // internal/discover: DefaultPort, Query, Service.
        assertEquals(8125, DiscoveryProtocol.PORT)
        assertEquals("CLIP-HANGER-DISCOVER/1", DiscoveryProtocol.QUERY)
        assertEquals("clip-hanger", DiscoveryProtocol.SERVICE)
    }
}
