package com.developer27.holocube.mobile

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.ListPreference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SeekBarPreference

/** The same two detection settings as the headset's Settings page, plus PyTorch CPU threads. */
class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.settings_activity)

        supportFragmentManager
            .beginTransaction()
            .replace(R.id.settings_container, SettingsFragment())
            .commit()
    }

    class SettingsFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.root_preferences, rootKey)

            findPreference<SeekBarPreference>(MainActivity.KEY_CONFIDENCE)?.setOnPreferenceChangeListener { _, newValue ->
                val percent = (newValue as Int).coerceIn(5, 100)
                Toast.makeText(context, "Confidence threshold set to ${"%.2f".format(percent / 100f)}", Toast.LENGTH_SHORT).show()
                true
            }

            findPreference<SeekBarPreference>(MainActivity.KEY_MAX_DETECTIONS)?.setOnPreferenceChangeListener { _, newValue ->
                val boxes = (newValue as Int).coerceIn(1, 80)
                Toast.makeText(context, "Number of boxes set to $boxes", Toast.LENGTH_SHORT).show()
                true
            }

            findPreference<ListPreference>(MainActivity.KEY_CPU_THREADS)?.setOnPreferenceChangeListener { _, newValue ->
                Toast.makeText(context, "PyTorch will use $newValue CPU threads", Toast.LENGTH_SHORT).show()
                true
            }
        }
    }
}
