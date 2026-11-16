package io.github.chinmay28.cliphanger.core

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.Locale

class PlayerTextTest {
    private val la = ZoneId.of("America/Los_Angeles")

    @Test
    fun titleIsTheChannelAndTheTimeInThePhonesZone() {
        val c = PlaylistClip("x.dav", "Front door", OffsetDateTime.parse("2026-11-16T20:20:05Z"), 0, listOf("u"), "")
        // A narrow no-break space before PM in newer JDKs; compare loosely.
        val t = PlayerText.title(c, la, Locale.US).replace(' ', ' ')
        assertEquals("Front door · 12:20:05 PM", t)
    }

    @Test
    fun withoutAChannelOrATimeTheFileNameStands() {
        assertEquals("x.dav", PlayerText.title(PlaylistClip("x.dav", "", null, 0, listOf("u"), ""), la, Locale.US))
        assertEquals("Driveway", PlayerText.title(PlaylistClip("x.dav", "Driveway", null, 0, listOf("u"), ""), la, Locale.US))
    }

    @Test
    fun subtitleSaysWhereAndWhichTry() {
        val clips = List(3) { PlaylistClip("c$it.dav", "", null, 0, listOf("a", "b"), "") }
        val p = Playlist(clips, 1)
        val l = Ladder.of(p.current)
        assertEquals("2 of 3 · c1.dav", PlayerText.subtitle(p, l))
        assertEquals("2 of 3 · c1.dav · try 2 of 2", PlayerText.subtitle(p, l.fallBack()!!))
        assertEquals("c0.dav", PlayerText.subtitle(Playlist(clips.take(1), 0), Ladder.of(clips[0])))
    }
}
