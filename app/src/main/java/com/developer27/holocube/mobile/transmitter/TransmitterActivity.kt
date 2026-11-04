package com.developer27.holocube.mobile.transmitter

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.accessibility.AccessibilityManager
import com.developer27.holocube.mobile.AboutHoloCubeActivity
import com.developer27.holocube.mobile.databinding.ActivityTransmitterBinding

/**
 * The HoloCube Transmitter: shows the supplied 1920 × 1200 optical pattern on a black,
 * full-screen, landscape canvas at maximum brightness and keeps the display awake.
 * Tap the pattern to reveal About Us; it hides again after four seconds.
 * Formerly the standalone HoloCube Transmitter app, now part of this app.
 */
class TransmitterActivity : FullscreenActivity() {
    companion object {
        const val CONTROLS_TIMEOUT_MS = 4000L
    }

    private lateinit var binding: ActivityTransmitterBinding
    private val handler = Handler(Looper.getMainLooper())
    private val hideControls = Runnable { binding.aboutButton.visibility = View.GONE }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTransmitterBinding.inflate(layoutInflater)
        setContentView(binding.root)
        protectContentFromCutout(binding.transmitterRoot)
        binding.transmitterImage.setOnClickListener { toggleControls() }
        binding.aboutButton.setOnClickListener {
            hideControlsNow()
            startActivity(Intent(this, AboutHoloCubeActivity::class.java))
        }
    }

    private fun toggleControls() {
        handler.removeCallbacks(hideControls)
        if (binding.aboutButton.visibility == View.VISIBLE) {
            hideControlsNow()
            return
        }
        binding.aboutButton.visibility = View.VISIBLE
        val accessibility = getSystemService(AccessibilityManager::class.java)
        // Screen-reader users can take as long as they need to reach the button.
        if (accessibility == null || !accessibility.isTouchExplorationEnabled) {
            handler.postDelayed(hideControls, CONTROLS_TIMEOUT_MS)
        }
    }

    private fun hideControlsNow() {
        handler.removeCallbacks(hideControls)
        binding.aboutButton.visibility = View.GONE
    }

    override fun onPause() {
        hideControlsNow()
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacks(hideControls)
        super.onDestroy()
    }
}
