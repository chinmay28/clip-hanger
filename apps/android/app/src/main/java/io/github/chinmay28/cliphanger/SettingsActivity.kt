package io.github.chinmay28.cliphanger

import android.os.Bundle
import android.text.InputType
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.google.android.material.appbar.MaterialToolbar
import io.github.chinmay28.cliphanger.core.Origin
import kotlin.concurrent.thread

/**
 * The app's own settings — which server. Everything about the footage, the
 * sources and the quotas is in the server's web interface.
 *
 * Changes are saved as they are made; the main screen picks them up when it
 * comes back and reconnects.
 */
class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        setSupportActionBar(findViewById<MaterialToolbar>(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        // Edge to edge (always, from Android 15): the toolbar's background
        // runs up under the status bar while its contents stay below it, and
        // the list keeps clear of the navigation bar and any cutout.
        val appBar = findViewById<View>(R.id.app_bar)
        val container = findViewById<View>(R.id.settings_container)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
            appBar.setPadding(bars.left, bars.top, bars.right, 0)
            container.setPadding(bars.left, 0, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction().replace(R.id.settings_container, SettingsFragment()).commit()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    class SettingsFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            preferenceManager.sharedPreferencesName = ConfigStore.FILE
            setPreferencesFromResource(R.xml.preferences, rootKey)

            findPreference<EditTextPreference>(ConfigStore.KEY_ADDRESS)!!.apply {
                setOnBindEditTextListener { it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI }
                setOnPreferenceChangeListener { pref, v ->
                    Origin.parse(v as String).fold(
                        onSuccess = { o ->
                            // Stored the way it will be read back: the origin, spelled out.
                            (pref as EditTextPreference).text = o.toString()
                            false
                        },
                        onFailure = { message(it.message ?: "That is not an address."); false },
                    )
                }
            }
            findPreference<Preference>("find_servers")!!.setOnPreferenceClickListener { discover(); true }
            findPreference<Preference>("version")!!.summary = BuildConfig.VERSION_NAME
            findPreference<Preference>("developer")!!.setOnPreferenceClickListener { DevFlash.show(requireContext()); true }
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            // The last rows scroll up clear of the navigation bar, rather than
            // the list stopping short of it with a dead band underneath.
            listView.clipToPadding = false
        }

        private fun discover() {
            val ctx = requireContext()
            val waiting = AlertDialog.Builder(ctx).setMessage(R.string.setup_finding).show()
            thread(name = "clip-discovery") {
                val servers = LanDiscovery.find(ctx)
                activity?.runOnUiThread {
                    waiting.dismiss()
                    if (servers.isEmpty()) {
                        message(getString(R.string.setup_found_none))
                        return@runOnUiThread
                    }
                    AlertDialog.Builder(ctx)
                        .setTitle(R.string.pref_find)
                        .setItems(servers.map { "${it.name}\n${it.origin}" }.toTypedArray()) { _, i ->
                            findPreference<EditTextPreference>(ConfigStore.KEY_ADDRESS)!!.text = servers[i].origin.toString()
                        }
                        .show()
                }
            }
        }

        private fun message(text: String) {
            AlertDialog.Builder(requireContext()).setMessage(text).setPositiveButton(android.R.string.ok, null).show()
        }
    }
}
