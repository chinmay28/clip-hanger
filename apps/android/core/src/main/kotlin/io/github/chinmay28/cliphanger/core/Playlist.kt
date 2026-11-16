package io.github.chinmay28.cliphanger.core

import org.json.JSONArray
import org.json.JSONObject
import java.time.OffsetDateTime

/**
 * One recording, as the native player needs it.
 *
 * [urls] are absolute addresses on the server, in the order worth trying
 * (see [Ladder]); [download] is the recording's own bytes, for Save and
 * Share; [start] is when it started recording, when the server knew.
 */
data class PlaylistClip(
    val name: String,
    val title: String,
    val start: OffsetDateTime?,
    val size: Long,
    val urls: List<String>,
    val download: String,
)

/**
 * The clips the native player can step through — the hour the chosen clip
 * was recorded in, oldest first, so "next" is later footage — and which one
 * is playing.
 *
 * Immutable: [next] and [previous] answer a new playlist, or null at either
 * end, so the player's state is one value it can save and restore.
 */
class Playlist(val clips: List<PlaylistClip>, val index: Int) {
    init {
        require(clips.isNotEmpty()) { "an empty playlist" }
        require(index in clips.indices) { "index $index outside ${clips.size} clips" }
    }

    val current: PlaylistClip get() = clips[index]
    val hasNext: Boolean get() = index < clips.lastIndex
    val hasPrevious: Boolean get() = index > 0

    fun next(): Playlist? = if (hasNext) Playlist(clips, index + 1) else null
    fun previous(): Playlist? = if (hasPrevious) Playlist(clips, index - 1) else null

    /**
     * At most [size] clips around the current one — what is worth keeping
     * across a restart of the app's process, where every byte of saved state
     * counts against a hard limit.
     */
    fun window(size: Int): Playlist {
        require(size > 0) { "a window of $size" }
        if (clips.size <= size) return this
        val from = (index - size / 2).coerceIn(0, clips.size - size)
        return Playlist(clips.subList(from, from + size).toList(), index - from)
    }

    /** "3 of 12", or nothing for a playlist of one. */
    val position: String get() = if (clips.size > 1) "${index + 1} of ${clips.size}" else ""

    /** The playlist as JSON [parse] reads back — for an intent or a saved state. */
    fun toJson(): String = JSONObject()
        .put("index", index)
        .put("clips", JSONArray(clips.map { c ->
            JSONObject()
                .put("name", c.name)
                .put("title", c.title)
                .put("start", c.start?.toString() ?: "")
                .put("size", c.size)
                .put("urls", JSONArray(c.urls))
                .put("download", c.download)
        }))
        .toString()

    override fun equals(other: Any?): Boolean = other is Playlist && other.index == index && other.clips == clips
    override fun hashCode(): Int = 31 * clips.hashCode() + index

    companion object {
        /**
         * More than any hour of any camera — eight channels writing ten-second
         * segments make 2,880. Past it the page is not sending an hour.
         */
        const val MAX_CLIPS = 5_000

        /**
         * Reads the page's playlist (see web/src/nativeApp.js) against the
         * server's [origin].
         *
         * Every address is resolved on the origin — a relative one onto it,
         * an absolute one only if it is already there — and anything that
         * would leave it is dropped, so the page can never point the player
         * (or a download carrying the web view's cookies) anywhere else. A
         * clip left with nothing to play is dropped too; if that is the one
         * asked for, the request fails.
         */
        fun parse(json: String, origin: Origin): Result<Playlist> = runCatching {
            val o = JSONObject(json)
            val raw = o.getJSONArray("clips")
            require(raw.length() in 1..MAX_CLIPS) { "a playlist of ${raw.length()} clips" }
            val wanted = o.optInt("index", 0)
            require(wanted in 0 until raw.length()) { "index $wanted outside ${raw.length()} clips" }

            var chosen: PlaylistClip? = null
            val kept = ArrayList<PlaylistClip>()
            for (i in 0 until raw.length()) {
                val c = raw.getJSONObject(i)
                val urls = c.optJSONArray("urls")
                    ?.let { a -> List(a.length()) { a.optString(it) } }
                    .orEmpty()
                    .mapNotNull { resolve(origin, it) }
                    .distinct()
                if (urls.isEmpty()) continue
                val clip = PlaylistClip(
                    name = c.optString("name"),
                    title = c.optString("title"),
                    start = runCatching { OffsetDateTime.parse(c.optString("start")) }.getOrNull(),
                    size = c.optLong("size", 0).coerceAtLeast(0),
                    urls = urls,
                    download = resolve(origin, c.optString("download")) ?: "",
                )
                kept += clip
                if (i == wanted) chosen = clip
            }
            val start = chosen ?: throw IllegalArgumentException("that clip has no address on ${origin}")

            // The page sends them in recording order already; sorting again
            // is what makes "next" mean later footage whatever sent them.
            // Only when every clip has a time — otherwise the order given is
            // the best one there is.
            val ordered = if (kept.all { it.start != null }) kept.sortedBy { it.start!!.toInstant() } else kept
            Playlist(ordered, ordered.indexOfFirst { it === start })
        }

        /** [url] as an absolute address on [origin], or null when it is not on it. */
        fun resolve(origin: Origin, url: String): String? {
            val u = url.trim()
            return when {
                u.startsWith("/") && !u.startsWith("//") -> origin.url(u)
                origin.contains(u) -> u
                else -> null
            }
        }
    }
}
