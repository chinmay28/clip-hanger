package io.github.chinmay28.cliphanger.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LadderTest {
    private fun clip(name: String, vararg urls: String, download: String = "http://h:8124/api/clip?path=$name") =
        PlaylistClip(name, "", null, 0, urls.toList(), download)

    @Test
    fun climbsThePagesLadderDownOneRungAtATime() {
        val l = Ladder.of(clip("a.dav", "remux", "transcode"))
        assertEquals("remux", l.current)
        assertEquals("1 of 2", l.attempt)
        val next = l.fallBack()!!
        assertEquals("transcode", next.current)
        assertEquals("2 of 2", next.attempt)
        assertNull(next.fallBack())
    }

    @Test
    fun containersTheWebCannotTakeButMedia3CanGoRawFirst() {
        for (ext in listOf("mkv", "MKV", "ts", "flv", "avi")) {
            val l = Ladder.of(clip("a.$ext", "remux", "transcode", download = "raw"))
            assertEquals(ext, listOf("raw", "remux", "transcode"), l.urls)
        }
    }

    @Test
    fun everythingElseKeepsThePagesOrder() {
        assertEquals(listOf("raw", "remux"), Ladder.of(clip("a.mp4", "raw", "remux", download = "raw")).urls)
        assertEquals(listOf("remux"), Ladder.of(clip("a.dav", "remux", download = "raw")).urls)
        // No download to go raw with: the page's ladder as it came.
        assertEquals(listOf("remux"), Ladder.of(clip("a.mkv", "remux", download = "")).urls)
    }
}
