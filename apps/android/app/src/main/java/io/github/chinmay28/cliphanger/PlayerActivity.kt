package io.github.chinmay28.cliphanger

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.webkit.CookieManager
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import io.github.chinmay28.cliphanger.core.Ladder
import io.github.chinmay28.cliphanger.core.Origin
import io.github.chinmay28.cliphanger.core.PlayerText
import io.github.chinmay28.cliphanger.core.Playlist
import java.time.ZoneId
import java.util.Locale
import java.util.UUID

/**
 * Plays clips natively, with Media3 — instead of the page's own <video>.
 *
 * Why native: ExoPlayer hands the streams to the phone's hardware decoders,
 * scrubs a seekable MP4 without the web view's buffering stalls, goes
 * landscape full screen for a landscape clip, and steps to the previous and
 * next recording of the hour (the page hands the whole hour over; see
 * core/Playlist). At the end of a clip the next one starts on its own, so an
 * hour of ten-second segments plays as the hour it was.
 *
 * Each clip climbs the same ladder the page's player does (core/Ladder): the
 * file as-is, then the server's ffmpeg remux, then its H.264 transcode, with
 * the raw file first for the containers Media3 reads and browsers do not. A
 * clip that fails every rung says so, with Save beside it.
 *
 * Every address it plays was checked to be on the server's origin
 * (Playlist.parse), and the web view's cookies go with each request, so a
 * server behind a signing-in reverse proxy plays here as it does in the page.
 */
@OptIn(UnstableApi::class)
class PlayerActivity : AppCompatActivity() {

    private lateinit var origin: Origin
    private lateinit var playlist: Playlist
    private val ladders = HashMap<Int, Ladder>()

    private var player: ExoPlayer? = null
    private lateinit var view: PlayerView
    private lateinit var topBar: View
    private lateinit var title: TextView
    private lateinit var subtitle: TextView
    private lateinit var message: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val o = intent.getStringExtra(EXTRA_ORIGIN)?.let { Origin.parse(it).getOrNull() }
        // The playlist travels in memory (an hour of clips can outgrow an
        // intent); after the process was killed it comes back, trimmed, from
        // the saved state instead.
        val p = savedInstanceState?.getString(STATE_PLAYLIST)?.let { json -> o?.let { Playlist.parse(json, it).getOrNull() } }
            ?: intent.getStringExtra(EXTRA_TOKEN)?.let { synchronized(pending) { pending.remove(it) } }
        if (o == null || p == null) {
            finish()
            return
        }
        origin = o
        playlist = p
        val startAt = savedInstanceState?.getLong(STATE_POSITION, 0L) ?: 0L

        setContentView(R.layout.activity_player)
        view = findViewById(R.id.player)
        topBar = findViewById(R.id.top_bar)
        title = findViewById(R.id.title)
        subtitle = findViewById(R.id.subtitle)
        message = findViewById(R.id.message)
        findViewById<View>(R.id.close).setOnClickListener { finish() }
        findViewById<View>(R.id.save).setOnClickListener { save() }
        findViewById<View>(R.id.share).setOnClickListener { share() }

        // The picture runs under every bar; only the top bar keeps clear of
        // the status bar and any cutout.
        ViewCompat.setOnApplyWindowInsetsListener(topBar) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(bars.left + dp(4), bars.top, bars.right + dp(8), 0)
            insets
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        immersive()

        view.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility ->
            topBar.visibility = visibility
        })

        // The web view's cookies, for a server behind a signing-in proxy.
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent("ClipHangerApp/" + BuildConfig.VERSION_NAME)
            .setAllowCrossProtocolRedirects(false)
        CookieManager.getInstance().getCookie(origin.url("/"))?.let {
            http.setDefaultRequestProperties(mapOf("Cookie" to it))
        }
        val exo = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(http))
            .build()
        player = exo
        view.player = exo
        exo.addListener(Events())

        exo.setMediaItems(playlist.clips.indices.map { item(it) }, playlist.index, startAt)
        exo.prepare()
        exo.playWhenReady = true
        describe(playlist.index)
    }

    private fun ladder(i: Int): Ladder = ladders.getOrPut(i) { Ladder.of(playlist.clips[i]) }

    private fun item(i: Int): MediaItem {
        val clip = playlist.clips[i]
        return MediaItem.Builder()
            .setMediaId(i.toString())
            .setUri(ladder(i).current)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(PlayerText.title(clip, ZoneId.systemDefault(), Locale.getDefault())).build())
            .build()
    }

    private inner class Events : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val i = mediaItem?.mediaId?.toIntOrNull() ?: return
            playlist = Playlist(playlist.clips, i)
            message.visibility = View.GONE
            describe(i)
        }

        override fun onPlayerError(error: PlaybackException) {
            val exo = player ?: return
            val i = exo.currentMediaItemIndex
            val down = ladder(i).fallBack()
            if (down == null) {
                message.setText(R.string.player_failed)
                message.visibility = View.VISIBLE
                view.showController()
                return
            }
            // One rung down: the server's remux, or its transcode.
            ladders[i] = down
            exo.replaceMediaItem(i, item(i))
            exo.prepare()
            exo.play()
            describe(i)
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            // A landscape clip — which is nearly every camera's — turns the
            // phone's picture landscape; a portrait one leaves it free.
            if (videoSize.width <= 0 || videoSize.height <= 0) return
            val landscape = videoSize.width * videoSize.pixelWidthHeightRatio > videoSize.height
            requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    private fun describe(i: Int) {
        val clip = playlist.clips[i]
        title.text = PlayerText.title(clip, ZoneId.systemDefault(), Locale.getDefault())
        subtitle.text = PlayerText.subtitle(Playlist(playlist.clips, i), ladder(i))
        findViewById<View>(R.id.save).isEnabled = clip.download.isNotEmpty()
        findViewById<View>(R.id.share).isEnabled = clip.download.isNotEmpty()
    }

    private fun save() {
        val clip = playlist.current
        if (clip.download.isNotEmpty()) Downloads.fetch(this, origin, clip.download, clip.name)
    }

    private fun share() {
        val clip = playlist.current
        if (clip.download.isNotEmpty()) Sharing.shareFromServer(this, origin, clip.download, clip.name)
    }

    private fun immersive() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) immersive()
    }

    override fun onStop() {
        super.onStop()
        // Footage is not music: leaving the player stops it.
        player?.pause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (!::playlist.isInitialized) return
        outState.putString(STATE_PLAYLIST, playlist.window(SAVED_WINDOW).toJson())
        outState.putLong(STATE_POSITION, player?.currentPosition ?: 0L)
    }

    override fun onDestroy() {
        player?.release()
        player = null
        super.onDestroy()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val EXTRA_ORIGIN = "origin"
        private const val EXTRA_TOKEN = "playlist"
        private const val STATE_PLAYLIST = "playlist"
        private const val STATE_POSITION = "position"
        private const val SAVED_WINDOW = 60

        private val pending = HashMap<String, Playlist>()

        fun intent(context: Context, origin: Origin, playlist: Playlist): Intent {
            val token = UUID.randomUUID().toString()
            synchronized(pending) {
                // Anything not picked up by now never will be.
                if (pending.size > 8) pending.clear()
                pending[token] = playlist
            }
            return Intent(context, PlayerActivity::class.java)
                .putExtra(EXTRA_ORIGIN, origin.toString())
                .putExtra(EXTRA_TOKEN, token)
        }
    }
}
