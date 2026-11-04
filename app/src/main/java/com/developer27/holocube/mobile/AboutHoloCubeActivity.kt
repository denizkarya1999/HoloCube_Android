package com.developer27.holocube.mobile

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.developer27.holocube.mobile.databinding.ActivityAboutHolocubeBinding

/** The same research project details as the headset's About page, with tappable links. */
class AboutHoloCubeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAboutHolocubeBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAboutHolocubeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Tapping the HoloCube title opens the developer's website.
        binding.titleContainer.setOnClickListener { openUrl(getString(R.string.url_website)) }

        // The advisor and lab rows open the Trustworthy AIoT Lab site.
        binding.textViewProfessor.setOnClickListener { openUrl(getString(R.string.url_lab)) }
        binding.textViewLab.setOnClickListener { openUrl(getString(R.string.url_lab)) }

        // The UM logo opens the university site.
        binding.umLogo.setOnClickListener { openUrl(getString(R.string.url_university)) }
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
            Toast.makeText(this, url, Toast.LENGTH_LONG).show()
        }
    }
}
