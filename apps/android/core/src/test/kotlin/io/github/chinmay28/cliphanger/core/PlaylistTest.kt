package io.github.chinmay28.cliphanger.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistTest {
    private val origin = Origin("http", "192.168.1.20", 8124)

    private fun clip(name: String, start: String, vararg urls: String, download: String = "/api/clip?source=s&path=$name") =
        JSONObject()
            .put("name", name)
            .put("title", "Front door")
            .put("start", start)
            .put("size", 1000)
            .put("urls", JSONArray(urls.toList()))
            .put("download", download)

    private fun page(index: Int, vararg clips: JSONObject) =
        JSONObject().put("index", index).put("clips", JSONArray(clips.toList())).toString()

    private val hour = page(
        1,
        clip("12.00.dav", "2026-11-16T12:00:00-08:00", "/api/clip/play?source=s&path=12.00.dav"),
        clip("12.20.mp4", "2026-11-16T12:20:00-08:00", "/api/clip?source=s&path=12.20.mp4", "/api/clip/play?source=s&path=12.20.mp4"),
        clip("12.40.dav", "2026-11-16T12:40:00-08:00", "/api/clip/play?source=s&path=12.40.dav"),
    )

    @Test
    fun readsThePagesPlaylistOntoTheOrigin() {
        val p = Playlist.parse(hour, origin).getOrThrow()
        assertEquals(3, p.clips.size)
        assertEquals("12.20.mp4", p.current.name)
        assertEquals(
            listOf("http://192.168.1.20:8124/api/clip?source=s&path=12.20.mp4", "http://192.168.1.20:8124/api/clip/play?source=s&path=12.20.mp4"),
            p.current.urls,
        )
        assertEquals("http://192.168.1.20:8124/api/clip?source=s&path=12.20.mp4", p.current.download)
        assertEquals("Front door", p.current.title)
        assertEquals(1000L, p.current.size)
        assertEquals("2 of 3", p.position)
    }

    @Test
    fun nextIsLaterFootageAndTheEndsStop() {
        val p = Playlist.parse(hour, origin).getOrThrow()
        assertEquals("12.40.dav", p.next()!!.current.name)
        assertEquals("12.00.dav", p.previous()!!.current.name)
        assertNull(p.next()!!.next())
        assertNull(p.previous()!!.previous())
        assertFalse(p.next()!!.hasNext)
        assertTrue(p.next()!!.hasPrevious)
    }

    @Test
    fun theOrderIsRecordingTimeWhateverTheOrderSent() {
        val newestFirst = page(
            0,
            clip("12.40.dav", "2026-11-16T12:40:00-08:00", "/a"),
            clip("12.20.dav", "2026-11-16T20:20:00Z", "/b"), // 12:20 PST, in another spelling
            clip("12.00.dav", "2026-11-16T12:00:00-08:00", "/c"),
        )
        val p = Playlist.parse(newestFirst, origin).getOrThrow()
        assertEquals(listOf("12.00.dav", "12.20.dav", "12.40.dav"), p.clips.map { it.name })
        assertEquals("12.40.dav", p.current.name)
        assertEquals(2, p.index)
    }

    @Test
    fun withoutTimesTheOrderGivenStands() {
        val p = Playlist.parse(page(0, clip("b.dav", "", "/b"), clip("a.dav", "2026-11-16T12:00:00-08:00", "/a")), origin).getOrThrow()
        assertEquals(listOf("b.dav", "a.dav"), p.clips.map { it.name })
        assertNull(p.current.start)
    }

    @Test
    fun addressesOffTheOriginAreDropped() {
        val p = Playlist.parse(
            page(
                0,
                clip(
                    "x.mp4", "2026-11-16T12:00:00-08:00",
                    "https://evil.test/x.mp4", "//evil.test/x", "javascript:alert(1)",
                    "http://192.168.1.20:8124/api/clip/play?source=s&path=x.mp4",
                    download = "http://evil.test/steal",
                ),
                clip("gone.mp4", "2026-11-16T12:10:00-08:00", "http://192.168.1.20:9999/x"),
            ),
            origin,
        ).getOrThrow()
        assertEquals(1, p.clips.size)
        assertEquals(listOf("http://192.168.1.20:8124/api/clip/play?source=s&path=x.mp4"), p.current.urls)
        assertEquals("", p.current.download)
    }

    @Test
    fun aChosenClipWithNothingToPlayFails() {
        val bad = page(0, clip("x.mp4", "", "https://evil.test/x"), clip("y.mp4", "", "/y"))
        assertTrue(Playlist.parse(bad, origin).isFailure)
    }

    @Test
    fun malformedPlaylistsFail() {
        for (bad in listOf("", "{}", "{\"clips\":[]}", page(5, clip("a", "", "/a")), page(-1, clip("a", "", "/a")), "[1,2]")) {
            assertTrue("accepted $bad", Playlist.parse(bad, origin).isFailure)
        }
    }

    @Test
    fun roundTripsThroughItsOwnJson() {
        val p = Playlist.parse(hour, origin).getOrThrow().next()!!
        assertEquals(p, Playlist.parse(p.toJson(), origin).getOrThrow())
    }

    @Test
    fun resolve() {
        assertEquals("http://192.168.1.20:8124/x", Playlist.resolve(origin, "/x"))
        assertEquals("http://192.168.1.20:8124/x", Playlist.resolve(origin, "http://192.168.1.20:8124/x"))
        assertNull(Playlist.resolve(origin, "//evil.test/x"))
        assertNull(Playlist.resolve(origin, "x"))
        assertNull(Playlist.resolve(origin, ""))
    }
}

class PlaylistWindowTest {
    private fun list(n: Int, at: Int) = Playlist(List(n) { PlaylistClip("c$it", "", null, 0, listOf("u$it"), "") }, at)

    @Test
    fun aShortPlaylistIsItsOwnWindow() {
        val p = list(5, 2)
        assertEquals(p, p.window(10))
    }

    @Test
    fun theWindowKeepsTheCurrentClipAndItsNeighbours() {
        val w = list(100, 50).window(10)
        assertEquals(10, w.clips.size)
        assertEquals("c50", w.current.name)
        assertEquals("c45", w.clips.first().name)
        val start = list(100, 1).window(10)
        assertEquals("c0", start.clips.first().name)
        assertEquals("c1", start.current.name)
        val end = list(100, 99).window(10)
        assertEquals("c99", end.clips.last().name)
        assertEquals("c99", end.current.name)
    }
}
