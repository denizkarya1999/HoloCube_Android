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
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import com.developer27.holocube.mobile.videoprocessing.TransmitterHelper
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

    @Test fun labelsAreExactlyTheTransmitterClass() {
        val labels = TransmitterHelper.labels(context)
        assertEquals(1, labels.size)
        assertEquals("Transmitter", labels.single())
    }

    /**
     * Pushes the angled and low-light validation frames through the app's real
     * RGBA path (bytes → Python → rows) and compares with the independent
     * Ultralytics boxes, as Tools/validate-pt.py does on the desktop.
     */
    @Test fun transmitterParityHoldsForAngledAndLowLightFrames() {
        val worker = TransmitterHelper.ensureRuntime(context)
        worker.awaitReady()
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        for (name in listOf("angled", "low_light")) {
            val reference = JSONObject(assets.open("fixtures/$name.json").bufferedReader().readText())
            assertEquals(TransmitterHelper.CHECKPOINT_SHA256, reference.getString("checkpoint_sha256"))
            val floats = ByteBuffer.wrap(assets.open("fixtures/$name.f32").readBytes())
                .order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            val size = TransmitterHelper.INPUT_SIZE
            assertEquals(3 * size * size, floats.remaining())
            // NCHW floats in 0..1 → the same RGBA bytes OpenCV would hand to Python.
            val rgba = ByteArray(size * size * 4)
            for (y in 0 until size) for (x in 0 until size) {
                val o = (y * size + x) * 4
                for (c in 0 until 3) rgba[o + c] = Math.round(floats.get(c * size * size + y * size + x) * 255f).toByte()
                rgba[o + 3] = -1
            }
            val rows = worker.detect(rgba)
            val expected = reference.getJSONArray("detections")
            val threshold = reference.getDouble("threshold").toFloat()
            val scale = maxOf(reference.getInt("width"), reference.getInt("height")) / size.toFloat()
            val found = (0 until rows.size / 6).filter { rows[it * 6 + 4] >= threshold }
            assertEquals("$name: detection count", expected.length(), found.size)
            found.forEachIndexed { index, row ->
                val other = expected.getJSONObject(index)
                assertEquals("$name: class", other.getInt("classId"), rows[row * 6 + 5].toInt())
                assertEquals("$name: confidence", other.getDouble("confidence").toFloat(), rows[row * 6 + 4], 0.001f)
                val box = other.getJSONArray("box")
                var squared = 0f
                for (i in 0 until 4) {
                    val d = (rows[row * 6 + i] - box.getDouble(i).toFloat()) * scale
                    squared += d * d
                }
                assertTrue("$name: box differs by ${Math.sqrt(squared.toDouble())} px", squared < 1f)
            }
        }
    }

    @Test fun bundledTransmitterCheckpointRunsOnDeviceAndDoesNotMutateEarlierFrames() {
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

    /**
     * End-to-end check of the app path with a real transmitter frame: the angled
     * validation fixture becomes a bitmap, goes through OpenCV letterboxing, the Python
     * runtime, the single-box parser, and the OpenCV drawing, and must yield exactly one
     * Transmitter box at the reference position with a maize outline drawn on the frame.
     */
    @Test fun videoProcessorDrawsOneTransmitterBoxOnTheAngledFrame() {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val reference = JSONObject(assets.open("fixtures/angled.json").bufferedReader().readText())
        val size = TransmitterHelper.INPUT_SIZE
        val floats = ByteBuffer.wrap(assets.open("fixtures/angled.f32").readBytes())
            .order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val pixels = IntArray(size * size)
        for (y in 0 until size) for (x in 0 until size) {
            fun channel(c: Int) = Math.round(floats.get(c * size * size + y * size + x) * 255f).coerceIn(0, 255)
            pixels[y * size + x] = Color.rgb(channel(0), channel(1), channel(2))
        }
        val frame = Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)

        val processor = VideoProcessor(context)
        val ready = CountDownLatch(1)
        instrumentation.runOnMainSync { processor.warmUp { ready.countDown() } }
        assertTrue(ready.await(180, TimeUnit.SECONDS))
        val latch = CountDownLatch(1)
        var output: FrameResult? = null
        instrumentation.runOnMainSync {
            processor.processFrame(frame, annotateFrame = true) { output = it; latch.countDown() }
        }
        assertTrue("Inference timed out", latch.await(120, TimeUnit.SECONDS))
        val result = requireNotNull(output) { "Inference returned no frame" }
        try {
            val expected = reference.getJSONArray("detections").getJSONObject(0)
            val box = expected.getJSONArray("box")
            assertEquals(1, result.detections.size)
            val detection = result.detections.single()
            assertEquals(0, detection.classId)
            assertEquals(expected.getDouble("confidence").toFloat(), detection.confidence, 0.001f)
            // The fixture is already 320 × 320, so model and frame coordinates coincide.
            assertEquals(box.getDouble(0).toFloat(), detection.left, 1f)
            assertEquals(box.getDouble(1).toFloat(), detection.top, 1f)
            assertEquals(box.getDouble(2).toFloat(), detection.right, 1f)
            assertEquals(box.getDouble(3).toFloat(), detection.bottom, 1f)
            // A maize outline (255, 203, 5) was drawn along the box's left edge.
            val x = detection.left.toInt() + 1
            val y = ((detection.top + detection.bottom) / 2).toInt()
            val outline = result.frame.getPixel(x, y)
            assertTrue("Expected a maize outline, got #%08X".format(outline),
                Color.red(outline) > 200 && Color.green(outline) > 150 && Color.blue(outline) < 80)
            // Keep the annotated frame for inspection: adb shell run-as <package> cat files/annotated-angled.png
            java.io.File(context.filesDir, "annotated-angled.png").outputStream().use {
                result.frame.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        } finally {
            result.frame.recycle()
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
                assertTrue(activity.findViewById<Button>(R.id.recordButton).isEnabled)
            }
        }
    }
}
