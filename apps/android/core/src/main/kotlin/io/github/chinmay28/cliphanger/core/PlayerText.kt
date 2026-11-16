package io.github.chinmay28.cliphanger.core

import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** The words the native player puts on screen. */
object PlayerText {
    /**
     * The title bar: the channel's name and when the clip started, in the
     * phone's zone and locale — "Front door · 12:20:05 PM" — or the file
     * name when the page sent neither.
     */
    fun title(clip: PlaylistClip, zone: ZoneId, locale: Locale): String {
        val time = clip.start?.atZoneSameInstant(zone)
            ?.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.MEDIUM).withLocale(locale))
        return listOfNotNull(clip.title.ifBlank { null }, time).joinToString(" · ").ifEmpty { clip.name }
    }

    /**
     * The line under the title: where in the hour, the file, and — once the
     * player has had to step down the ladder — which address it is on:
     * "2 of 12 · 12.20.05.dav · try 2 of 3".
     */
    fun subtitle(playlist: Playlist, ladder: Ladder): String =
        listOf(
            playlist.position,
            playlist.current.name,
            if (ladder.rung > 0) "try ${ladder.attempt}" else "",
        ).filter { it.isNotEmpty() }.joinToString(" · ")
}
