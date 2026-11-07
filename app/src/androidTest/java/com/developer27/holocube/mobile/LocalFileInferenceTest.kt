package com.developer27.holocube.mobile

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.SystemClock
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.developer27.holocube.mobile.videoprocessing.FrameResult
import com.developer27.holocube.mobile.videoprocessing.TransmitterHelper
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Settings → In-Device Local File Inference, end to end: the angled validation fixture is
 * saved as a PNG on the phone, opened by the screen, and must produce exactly one
 * Transmitter box at the reference position, with the status line reporting it.
 */
@RunWith(AndroidJUnit4::class)
class LocalFileInferenceTest {
    @Test fun openingTheAngledFixtureShowsOneTransmitterBox() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val assets = instrumentation.context.assets
        val reference = JSONObject(assets.open("fixtures/angled.json").bufferedReader().readText())
        val size = TransmitterHelper.INPUT_SIZE
        val floats = ByteBuffer.wrap(assets.open("fixtures/angled.f32").readBytes())
            .order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val pixels = IntArray(size * size)
        for (y in 0 until size) for (x in 0 until size) {
            fun channel(c: Int) = Math.round(floats.get(c * size * size + y * size + x) * 255f).coerceIn(0, 255)
            pixels[y * size + x] = Color.rgb(channel(0), channel(1), channel(2))
        }
        val file = File(instrumentation.targetContext.cacheDir, "local-inference-angled.png")
        Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888).also { bitmap ->
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }

        ActivityScenario.launch(LocalFileInferenceActivity::class.java).use { scenario ->
            scenario.onActivity { it.openImage(Uri.fromFile(file)) }
            val deadline = SystemClock.uptimeMillis() + 180_000
            var result: FrameResult? = null
            while (result == null && SystemClock.uptimeMillis() < deadline) {
                Thread.sleep(250)
                scenario.onActivity { result = it.lastResult }
            }
            val detections = requireNotNull(result) { "The screen produced no result in time" }.detections
            assertEquals(1, detections.size)
            val detection = detections.single()
            val expected = reference.getJSONArray("detections").getJSONObject(0)
            val box = expected.getJSONArray("box")
            assertEquals(0, detection.classId)
            assertEquals(expected.getDouble("confidence").toFloat(), detection.confidence, 0.001f)
            // The fixture is already 320 × 320, so model and image coordinates coincide.
            assertEquals(box.getDouble(0).toFloat(), detection.left, 1f)
            assertEquals(box.getDouble(1).toFloat(), detection.top, 1f)
            assertEquals(box.getDouble(2).toFloat(), detection.right, 1f)
            assertEquals(box.getDouble(3).toFloat(), detection.bottom, 1f)
            scenario.onActivity { activity ->
                val status = activity.findViewById<TextView>(R.id.statusText).text.toString()
                assertTrue(status, status.contains("1 transmitter") && status.contains("320 × 320"))
                val detail = activity.findViewById<TextView>(R.id.detailText).text.toString()
                assertTrue(detail, detail.startsWith("Transmitter ${(detection.confidence * 100f + 0.5f).toInt()}%"))
            }
        }
    }
}
