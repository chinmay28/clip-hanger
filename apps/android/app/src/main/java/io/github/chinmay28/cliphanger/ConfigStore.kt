package io.github.chinmay28.cliphanger

import android.content.Context
import android.content.SharedPreferences
import io.github.chinmay28.cliphanger.core.AppConfig

/**
 * The settings, kept in the shared preferences the settings screen edits
 * directly — one store, so the screen and the rest of the app never disagree.
 */
class ConfigStore(context: Context) {
    val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun load(): AppConfig = AppConfig(serverAddress = prefs.getString(KEY_ADDRESS, "") ?: "")

    fun save(c: AppConfig) {
        prefs.edit().putString(KEY_ADDRESS, c.serverAddress).apply()
    }

    companion object {
        const val FILE = "clip"
        const val KEY_ADDRESS = "server_address"
    }
}
