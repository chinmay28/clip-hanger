package io.github.chinmay28.cliphanger.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * The messages the server's page and the app send each other.
 *
 * A script the app puts into the page (assets/cliphanger-bridge.js) turns
 * what a web view cannot do on its own into these: a clip to play in the
 * native player, a download on the server's own address, the app's settings.
 *
 * Every request carries `re`, a number the page picked; the answer carries
 * the same one. Messages arrive only from the configured server's origin —
 * the web view enforces that, not this code — and a [Play] is still checked
 * against it address by address ([Playlist.parse]).
 */
sealed class BridgeRequest {
    abstract val re: Int

    /** The page asking what it is running in. */
    data class Hello(override val re: Int) : BridgeRequest()

    /** Open the app's own settings. */
    data class OpenSettings(override val re: Int) : BridgeRequest()

    /** Download an address on the server's origin straight to Downloads. */
    data class Fetch(override val re: Int, val url: String, val name: String) : BridgeRequest()

    /**
     * Play a clip in the native player. [playlist] is the page's playlist
     * object as JSON — the clip's hour, and which of it to start on — for
     * [Playlist.parse] to read against the origin.
     */
    data class Play(override val re: Int, val playlist: String) : BridgeRequest()

    companion object {
        fun parse(message: String): Result<BridgeRequest> = runCatching {
            val m = JSONObject(message)
            val re = m.optInt("re", 0)
            when (val t = m.optString("t")) {
                "hello" -> Hello(re)
                "settings" -> OpenSettings(re)
                "fetch" -> Fetch(re, m.getString("url"), m.optString("name"))
                "play" -> Play(re, m.getJSONObject("playlist").toString())
                else -> throw IllegalArgumentException("unknown message ${if (t.isEmpty()) "(no type)" else t}")
            }
        }

        /** The `re` of a message that may not parse, so even a refusal can be answered. */
        fun reOf(message: String): Int = runCatching { JSONObject(message).optInt("re", 0) }.getOrDefault(0)
    }
}

/** An answer to a [BridgeRequest]. */
object BridgeReply {
    fun ok(re: Int, vararg extra: Pair<String, Any?>): String {
        val o = JSONObject().put("re", re).put("ok", true)
        for ((k, v) in extra) o.put(k, if (v is List<*>) JSONArray(v) else v)
        return o.toString()
    }

    fun error(re: Int, message: String): String =
        JSONObject().put("re", re).put("ok", false).put("error", message).toString()
}
