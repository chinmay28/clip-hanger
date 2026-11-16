package io.github.chinmay28.cliphanger.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppConfigTest {
    @Test
    fun unconfiguredUntilThereIsAnAddress() {
        assertFalse(AppConfig().configured)
        assertFalse(AppConfig("  ").configured)
        assertTrue(AppConfig().origin().isFailure)
    }

    @Test
    fun theOriginIsTheAddressParsed() {
        assertEquals("http://nvr.local:8124", AppConfig("nvr.local").origin().getOrThrow().toString())
        assertTrue(AppConfig("ftp://nvr.local").origin().isFailure)
    }
}
