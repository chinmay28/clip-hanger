package io.github.chinmay28.cliphanger.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeTest {
    @Test
    fun parsesEachRequest() {
        assertEquals(BridgeRequest.Hello(1), BridgeRequest.parse("""{"t":"hello","re":1}""").getOrThrow())
        assertEquals(BridgeRequest.OpenSettings(2), BridgeRequest.parse("""{"t":"settings","re":2}""").getOrThrow())
        assertEquals(
            BridgeRequest.Fetch(3, "http://h:8124/api/clip?x", "a.dav"),
            BridgeRequest.parse("""{"t":"fetch","re":3,"url":"http://h:8124/api/clip?x","name":"a.dav"}""").getOrThrow(),
        )
        val play = BridgeRequest.parse("""{"t":"play","re":4,"playlist":{"index":0,"clips":[{"name":"a"}]}}""").getOrThrow()
        play as BridgeRequest.Play
        assertEquals(4, play.re)
        assertEquals("a", JSONObject(play.playlist).getJSONArray("clips").getJSONObject(0).getString("name"))
    }

    @Test
    fun refusesWhatItDoesNotKnow() {
        for (bad in listOf("", "{}", """{"t":"begin","re":1}""", """{"t":"fetch","re":1}""", """{"t":"play","re":1}""", """{"t":"play","playlist":"x"}""")) {
            assertTrue("accepted $bad", BridgeRequest.parse(bad).isFailure)
        }
        assertEquals(7, BridgeRequest.reOf("""{"t":"what","re":7}"""))
        assertEquals(0, BridgeRequest.reOf("garbage"))
    }

    @Test
    fun replies() {
        val ok = JSONObject(BridgeReply.ok(5, "version" to "dev", "list" to listOf(1, 2)))
        assertEquals(5, ok.getInt("re"))
        assertTrue(ok.getBoolean("ok"))
        assertEquals("dev", ok.getString("version"))
        assertEquals(2, ok.getJSONArray("list").length())
        val err = JSONObject(BridgeReply.error(6, "no"))
        assertFalse(err.getBoolean("ok"))
        assertEquals("no", err.getString("error"))
    }
}
