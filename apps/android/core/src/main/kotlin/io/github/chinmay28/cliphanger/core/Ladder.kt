package io.github.chinmay28.cliphanger.core

import java.util.Locale

/**
 * The addresses the native player tries for one clip, in order, and where it
 * has got to.
 *
 * The page's ladder (web/src/nativeApp.js) is the starting point: the file
 * as-is when a browser takes the container, then the server's ffmpeg remux,
 * then its H.264 transcode. The player tries one, and steps down a rung when
 * it fails; past the last there is nothing left but the download.
 *
 * One rung is added on the phone. Media3 reads Matroska, MPEG-TS, FLV and AVI
 * containers itself, which no browser does — so for those the recording's own
 * bytes go first, straight to the phone's hardware decoders, and the server's
 * ffmpeg is asked only if that fails (an MJPEG .avi, say). That spares the
 * server the remux, and HEVC in an .mkv plays at the camera's own quality.
 */
class Ladder private constructor(val urls: List<String>, val rung: Int) {

    val current: String get() = urls[rung]

    /** The next rung down, or null when this was the last. */
    fun fallBack(): Ladder? = if (rung < urls.lastIndex) Ladder(urls, rung + 1) else null

    /** "1 of 3": which address is playing, for the player's Info line. */
    val attempt: String get() = "${rung + 1} of ${urls.size}"

    companion object {
        /** Containers Media3 extracts on the phone that a browser will not take. */
        val NATIVE_CONTAINERS = setOf("mkv", "ts", "flv", "avi")

        fun of(clip: PlaylistClip): Ladder {
            val ext = clip.name.substringAfterLast('.', "").lowercase(Locale.ROOT)
            val urls = if (ext in NATIVE_CONTAINERS && clip.download.isNotEmpty()) {
                (listOf(clip.download) + clip.urls).distinct()
            } else {
                clip.urls
            }
            require(urls.isNotEmpty()) { "nothing to play" }
            return Ladder(urls, 0)
        }
    }
}
