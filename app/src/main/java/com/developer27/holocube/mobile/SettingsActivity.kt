package com.developer27.holocube.mobile

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SeekBarPreference

/**
 * The headset's Settings page: adjustable confidence, a fixed box count of 1, plus
 * PyTorch CPU threads, and the phone-only Model Testing entry (In-Device Local File Inference).
 */
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

            findPreference<ListPreference>(MainActivity.KEY_CPU_THREADS)?.setOnPreferenceChangeListener { _, newValue ->
                Toast.makeText(context, "PyTorch will use $newValue CPU threads", Toast.LENGTH_SHORT).show()
                true
            }

            // Model testing: open an image from this phone and run a model on it.
            findPreference<Preference>(KEY_LOCAL_FILE_INFERENCE)?.setOnPreferenceClickListener {
                startActivity(Intent(requireContext(), LocalFileInferenceActivity::class.java))
                true
            }
        }
    }

    companion object {
        const val KEY_LOCAL_FILE_INFERENCE = "local_file_inference"
    }
}
