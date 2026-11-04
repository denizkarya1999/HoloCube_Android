package com.developer27.holocube.mobile.transmitter

import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity

/**
 * Fullscreen display policy for the optical pattern screen: keep the screen on,
 * request maximum brightness for this window only, draw into display cutouts, and
 * hide the system bars. Port of the standalone HoloCube Transmitter's FullscreenActivity.
 */
abstract class FullscreenActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A visible-window flag needs no wake-lock permission and stops applying in background.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val attributes = window.attributes
        // Request full brightness only for this app's foreground window.
        // Android restores the user's normal policy when another window takes over.
        attributes.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            attributes.layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            else
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        window.attributes = attributes
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        // The content/decor must exist before asking Android for its insets controller.
        enterFullscreen()
    }

    protected fun protectContentFromCutout(content: View) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            content.setOnApplyWindowInsetsListener { view, insets ->
                val cutout = insets.displayCutout
                // Symmetric safe margins keep the optical pattern centered on the display.
                val horizontal = cutout?.let { maxOf(it.safeInsetLeft, it.safeInsetRight) } ?: 0
                val vertical = cutout?.let { maxOf(it.safeInsetTop, it.safeInsetBottom) } ?: 0
                view.setPadding(horizontal, vertical, horizontal, vertical)
                insets
            }
            content.requestApplyInsets()
        }
    }

    @Suppress("DEPRECATION")
    protected fun enterFullscreen() {
        val window = window
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let { controller ->
                controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller.hide(WindowInsets.Type.systemBars())
            }
        } else {
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterFullscreen()
    }
}
