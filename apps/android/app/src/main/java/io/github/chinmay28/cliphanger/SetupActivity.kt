package io.github.chinmay28.cliphanger

import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import io.github.chinmay28.cliphanger.core.DiscoveryProtocol
import io.github.chinmay28.cliphanger.core.Origin
import kotlin.concurrent.thread

/**
 * The first screen: where is your Clip Hanger server?
 *
 * Only an address — the app is a thin client, always. The footage lives on
 * the machine the cameras record to, and the server runs there beside it;
 * a phone has neither the directory nor the disk for it.
 */
class SetupActivity : AppCompatActivity() {
    private lateinit var addressLayout: TextInputLayout
    private lateinit var address: TextInputEditText
    private lateinit var found: LinearLayout
    private lateinit var find: MaterialButton
    private lateinit var go: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }

        addressLayout = findViewById(R.id.address_layout)
        address = findViewById(R.id.address)
        found = findViewById(R.id.found)
        find = findViewById(R.id.find)
        go = findViewById(R.id.go)
        findViewById<TextView>(R.id.version).text = getString(R.string.setup_version, BuildConfig.VERSION_NAME)
        DevFlash.attach(findViewById(R.id.dev_badge))

        val store = ConfigStore(this)
        address.setText(store.load().serverAddress)
        address.doAfterTextChanged {
            addressLayout.error = null
            go.isEnabled = !it.isNullOrBlank()
        }
        go.isEnabled = !address.text.isNullOrBlank()
        find.setOnClickListener { discover() }
        go.setOnClickListener {
            val o = Origin.parse(address.text.toString()).getOrElse {
                addressLayout.error = it.message
                return@setOnClickListener
            }
            store.save(store.load().copy(serverAddress = o.toString()))
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
            finish()
        }
    }

    private fun discover() {
        find.isEnabled = false
        find.setText(R.string.setup_finding)
        found.removeAllViews()
        thread(name = "clip-discovery") {
            val servers = LanDiscovery.find(this)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                find.isEnabled = true
                find.setText(R.string.setup_find)
                showFound(servers)
            }
        }
    }

    private fun showFound(servers: List<DiscoveryProtocol.Found>) {
        found.removeAllViews()
        if (servers.isEmpty()) {
            found.addView(TextView(this).apply {
                setText(R.string.setup_found_none)
                setTextColor(getColor(R.color.clip_muted))
            })
            return
        }
        for (s in servers) {
            found.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = getString(R.string.setup_found_item, s.name, s.origin.toString())
                isAllCaps = false
                setOnClickListener { address.setText(s.origin.toString()) }
            })
        }
    }
}
