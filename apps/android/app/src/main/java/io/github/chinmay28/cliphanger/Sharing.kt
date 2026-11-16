package io.github.chinmay28.cliphanger

import android.app.Activity
import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LabeledIntent
import androidx.core.content.FileProvider
import io.github.chinmay28.cliphanger.core.FileNames
import io.github.chinmay28.cliphanger.core.Origin
import java.io.File

/** A clip fetched for the share sheet. */
data class StagedFile(val file: File, val name: String, val mime: String)

/**
 * The share sheet, for a clip: the player's Share button.
 *
 * The clip is fetched from the server into the app's cache first (another
 * app cannot read an address on a home server the way this one can), then
 * offered through the app's FileProvider. The sheet always leads with "Save
 * to Downloads", for when the clip is not on its way to anybody.
 */
object Sharing {
    private const val MAX_AGE_MS = 60 * 60 * 1000L

    fun dir(context: Context) = File(context.cacheDir, "shared")

    /**
     * Fetches [url] (on [origin] only) as [name] and opens the share sheet
     * with it. Returns at once; the sheet opens when the clip has arrived.
     */
    fun shareFromServer(activity: Activity, origin: Origin, url: String, name: String) {
        val app = activity.applicationContext
        Downloads.toast(app, app.getString(R.string.player_preparing_share, name))
        Downloads.background {
            try {
                sweep(app)
                val folder = File(dir(app), System.nanoTime().toString()).apply { mkdirs() }
                val conn = Downloads.open(origin, url)
                val staged = try {
                    val target = File(folder, FileNames.forDownload(name, conn.getHeaderField("Content-Disposition"), conn.url.toString()))
                    conn.inputStream.use { input -> target.outputStream().use { input.copyTo(it, 256 * 1024) } }
                    StagedFile(target, target.name, Downloads.mimeFor(target.name, conn.contentType))
                } finally {
                    conn.disconnect()
                }
                activity.runOnUiThread {
                    if (!activity.isFinishing && !activity.isDestroyed) open(activity, staged)
                }
            } catch (e: Exception) {
                Downloads.toast(app, app.getString(R.string.share_failed, name, e.message ?: e.javaClass.simpleName))
            }
        }
    }

    private fun open(activity: Activity, file: StagedFile) {
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.files", file.file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = file.mime
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri(file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val save = Intent(send).setComponent(ComponentName(activity, SaveToDownloadsActivity::class.java))
        val chooser = Intent.createChooser(send, activity.getString(R.string.share_with)).putExtra(
            Intent.EXTRA_INITIAL_INTENTS,
            arrayOf(LabeledIntent(save, activity.packageName, activity.getString(R.string.save_to_downloads), R.mipmap.ic_launcher)),
        )
        activity.startActivity(chooser)
    }

    /** Clips shared more than an hour ago: the receiving app has had them. */
    private fun sweep(context: Context) {
        val cutoff = System.currentTimeMillis() - MAX_AGE_MS
        dir(context).listFiles().orEmpty().filter { it.lastModified() < cutoff }.forEach { it.deleteRecursively() }
    }
}
