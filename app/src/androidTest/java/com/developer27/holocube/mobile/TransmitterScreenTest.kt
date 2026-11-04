package com.developer27.holocube.mobile

import android.app.Instrumentation
import android.graphics.RectF
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.ImageView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.developer27.holocube.mobile.transmitter.TransmitterActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Port of the standalone HoloCube Transmitter's device tests. Views are driven with
 * performClick instead of Espresso event injection, which Android 17 no longer supports.
 */
@RunWith(AndroidJUnit4::class)
class TransmitterScreenTest {
    private val instrumentation: Instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun launchShowsWholeImageInLandscapeAndStaysAwakeAfterRecreation() {
        ActivityScenario.launch(TransmitterActivity::class.java).use { scenario ->
            assertImageWindow(scenario)
            scenario.recreate()
            assertImageWindow(scenario)
        }
    }

    private fun assertImageWindow(scenario: ActivityScenario<TransmitterActivity>) {
        instrumentation.waitForIdleSync()
        scenario.onActivity { activity ->
            val image = activity.findViewById<ImageView>(R.id.transmitter_image)
            assertTrue(image.isShown)
            assertEquals(View.GONE, activity.findViewById<View>(R.id.about_button).visibility)
            assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0)
            assertEquals(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL, activity.window.attributes.screenBrightness, 0f)
            assertTrue(image.width > image.height)
            assertEquals(1920, image.drawable.intrinsicWidth)
            assertEquals(1200, image.drawable.intrinsicHeight)
            val bounds = RectF(0f, 0f, 1920f, 1200f)
            image.imageMatrix.mapRect(bounds)
            assertTrue(bounds.left >= -1 && bounds.top >= -1)
            assertTrue(bounds.right <= image.width + 1)
            assertTrue(bounds.bottom <= image.height + 1)
            assertEquals(16f / 10f, bounds.width() / bounds.height(), 0.001f)
            assertEquals(activity.findViewById<View>(R.id.transmitter_root).width / 2f,
                image.left + bounds.centerX(), 1f)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                assertFalse(image.rootWindowInsets.isVisible(WindowInsets.Type.statusBars()))
                assertFalse(image.rootWindowInsets.isVisible(WindowInsets.Type.navigationBars()))
            }
        }
    }

    private fun click(scenario: ActivityScenario<TransmitterActivity>, id: Int) {
        scenario.onActivity { it.findViewById<View>(id).performClick() }
        instrumentation.waitForIdleSync()
    }

    @Test fun aboutUsOpensAndBackRestoresImageOnly() {
        val monitor = instrumentation.addMonitor(AboutHoloCubeActivity::class.java.name, null, false)
        try {
            ActivityScenario.launch(TransmitterActivity::class.java).use { scenario ->
                click(scenario, R.id.transmitter_image)
                click(scenario, R.id.about_button)
                val about = monitor.waitForActivityWithTimeout(10_000)
                assertNotNull("About Us did not open", about)
                instrumentation.waitForIdleSync()
                instrumentation.runOnMainSync {
                    assertTrue(about.findViewById<View>(R.id.details_container).isShown)
                    about.finish()
                }
                instrumentation.waitForIdleSync()
                SystemClock.sleep(1000)
                assertImageWindow(scenario)
            }
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

    @Test fun controlsAutoHideAndCanBeReopened() {
        ActivityScenario.launch(TransmitterActivity::class.java).use { scenario ->
            click(scenario, R.id.transmitter_image)
            scenario.onActivity { assertEquals(View.VISIBLE, it.findViewById<View>(R.id.about_button).visibility) }
            SystemClock.sleep(4500)
            instrumentation.waitForIdleSync()
            scenario.onActivity { assertEquals(View.GONE, it.findViewById<View>(R.id.about_button).visibility) }
            click(scenario, R.id.transmitter_image)
            scenario.onActivity { assertEquals(View.VISIBLE, it.findViewById<View>(R.id.about_button).visibility) }
            click(scenario, R.id.transmitter_image)
            assertImageWindow(scenario)
        }
    }
}
