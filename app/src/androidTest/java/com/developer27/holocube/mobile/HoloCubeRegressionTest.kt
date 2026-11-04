package com.developer27.holocube.mobile

import android.Manifest
import android.graphics.Bitmap
import android.graphics.Color
import android.view.View
import android.widget.Button
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.developer27.holocube.mobile.videoprocessing.BitmapToRgbaTensor
import com.developer27.holocube.mobile.videoprocessing.FrameResult
import com.developer27.holocube.mobile.videoprocessing.VideoProcessor
import com.developer27.holocube.mobile.videoprocessing.YOLO26Helper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class HoloCubeRegressionTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun letterboxUsesGrayPaddingAndTopFirstRgb() {
        System.loadLibrary("opencv_java4")
        val bitmap = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(255, 128, 0))
        val rgba = BitmapToRgbaTensor.fromBitmap(bitmap)
        assertEquals(320 * 320 * 4, rgba.size)
        // Top-left pixel lies in the padding band (the image is wider than tall).
        assertEquals(114, rgba[0].toInt() and 0xFF)
        assertEquals(114, rgba[1].toInt() and 0xFF)
        assertEquals(114, rgba[2].toInt() and 0xFF)
        // Centre pixel carries the image colour in R, G, B order.
        val centre = (160 * 320 + 160) * 4
        assertEquals(255, rgba[centre].toInt() and 0xFF)
        assertEquals(128, rgba[centre + 1].toInt() and 0xFF)
        assertEquals(0, rgba[centre + 2].toInt() and 0xFF)
        bitmap.recycle()
    }

    @Test fun labelsMatchTheCocoClassOrder() {
        val labels = YOLO26Helper.labels(context)
        assertEquals(80, labels.size)
        assertEquals("person", labels.first())
        assertEquals("toothbrush", labels.last())
    }

    @Test fun bundledCheckpointRunsOnDeviceAndDoesNotMutateEarlierFrames() {
        val processor = VideoProcessor(context)
        val ready = CountDownLatch(1)
        var loadError: String? = "not reported"
        instrumentation.runOnMainSync {
            processor.warmUp { error -> loadError = error; ready.countDown() }
        }
        assertTrue("Model load timed out", ready.await(180, TimeUnit.SECONDS))
        assertNull(loadError, loadError)

        fun process(color: Int, annotate: Boolean): FrameResult {
            val bitmap = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(color)
            val latch = CountDownLatch(1)
            var output: FrameResult? = null
            instrumentation.runOnMainSync {
                processor.processFrame(bitmap, annotate) {
                    output = it
                    latch.countDown()
                }
            }
            assertTrue("Inference timed out", latch.await(120, TimeUnit.SECONDS))
            return requireNotNull(output) { "Inference returned no frame" }
        }
        try {
            val first = process(Color.BLACK, true)
            val before = first.frame.getPixel(320, 240)
            val second = process(Color.WHITE, false)
            assertNotSame(first.frame, second.frame)
            assertEquals(before, first.frame.getPixel(320, 240))
            assertEquals(640, second.frame.width)
            assertEquals(480, second.frame.height)
            assertTrue(second.inferenceMillis >= 0)
            first.frame.recycle()
            second.frame.recycle()
        } finally {
            processor.close()
        }
    }

    @Test fun cameraSurvivesSwitchingAndPausing() {
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.CAMERA)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            repeat(3) {
                scenario.onActivity { activity ->
                    activity.findViewById<View>(R.id.switchCameraButton).performClick()
                    activity.findViewById<View>(R.id.clearButton).performClick()
                }
                scenario.moveToState(Lifecycle.State.CREATED)
                scenario.moveToState(Lifecycle.State.RESUMED)
            }
            scenario.onActivity { activity ->
                activity.findViewById<Button>(R.id.startInferenceButton).performClick()
            }
            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.moveToState(Lifecycle.State.RESUMED)
            scenario.onActivity { activity ->
                assertFalse(activity.isFinishing)
                assertEquals("Start Inference", activity.findViewById<Button>(R.id.startInferenceButton).text.toString())
                assertTrue(activity.findViewById<Button>(R.id.TakeSnapshotButton).isEnabled)
            }
        }
    }
}
