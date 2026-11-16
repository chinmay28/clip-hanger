package io.github.chinmay28.cliphanger

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class ClipHangerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_DOWNLOADS, getString(R.string.channel_downloads), NotificationManager.IMPORTANCE_LOW),
        )
    }

    companion object {
        const val CHANNEL_DOWNLOADS = "downloads"
    }
}
