package com.developer27.holocube.mobile.videoprocessing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Row parsing for the Orientation Detection Model: 5 rows of 12 values in 640 × 640 pixels. */
class OrientationParserTest {
    // A 1920 × 1080 frame: scale 1/3, 640 × 360 image, 140-pixel bands above and below.
    private val letterbox = ImageLetterbox(1920, 1080, OrientationHelper.INPUT_SIZE)

    private fun rows(vararg rows: FloatArray): FloatArray {
        val output = FloatArray(OrientationHelper.OUTPUT_ROWS * OrientationHelper.OUTPUT_COLUMNS)
        rows.forEachIndexed { index, row -> row.copyInto(output, index * OrientationHelper.OUTPUT_COLUMNS) }
        return output
    }

    // The bright reference frame's row: North, with its mask triangle.
    private fun north(confidence: Float = 0.917f, classId: Float = 0f) = floatArrayOf(
        319.2f, 331.7f, 354.5f, 374.3f, confidence, classId,
        318.5f, 330.5f, 357.5f, 330.5f, 339.0f, 372.5f
    )

    @Test fun eightClassesClockwiseFromNorth() {
        assertEquals(8, OrientationHelper.CLASS_NAMES.size)
        assertEquals("North", OrientationHelper.classNameForId(0))
        assertEquals("East", OrientationHelper.classNameForId(2))
        assertEquals("Northwest", OrientationHelper.classNameForId(7))
        assertEquals("N/A", OrientationHelper.classNameForId(8))
    }

    @Test fun mapsBoxAndTriangleBackToFramePixels() {
        val result = OrientationHelper.parseRows(rows(north()), letterbox, 0.35f).single()
        assertEquals(0, result.classId)
        assertEquals(0.917f, result.confidence, 1e-6f)
        assertEquals((319.2f - 0f) * 3f, result.left, 0.01f)
        assertEquals((331.7f - 140f) * 3f, result.top, 0.01f)
        assertEquals(354.5f * 3f, result.right, 0.01f)
        assertEquals((374.3f - 140f) * 3f, result.bottom, 0.01f)
        assertEquals(318.5f * 3f, result.ax, 0.01f)
        assertEquals((330.5f - 140f) * 3f, result.ay, 0.01f)
        assertEquals(357.5f * 3f, result.bx, 0.01f)
        assertEquals((330.5f - 140f) * 3f, result.by, 0.01f)
        assertEquals(339.0f * 3f, result.cx, 0.01f)
        assertEquals((372.5f - 140f) * 3f, result.cy, 0.01f)
        // Top-left, top-right, bottom tip.
        assertTrue(result.ax < result.bx && result.cy > result.ay && result.cy > result.by)
    }

    @Test fun keepsOnlyTheStrongestValidRow() {
        val weaker = north(confidence = 0.5f, classId = 4f)
        val results = OrientationHelper.parseRows(rows(weaker, north()), letterbox, 0.35f)
        assertEquals(1, results.size)
        assertEquals(0, results[0].classId)
        assertEquals(2, OrientationHelper.parseRows(rows(weaker, north()), letterbox, 0.35f, maxDetections = 2).size)
        assertTrue(OrientationHelper.parseRows(rows(north()), letterbox, 0.35f, maxDetections = 0).isEmpty())
    }

    @Test fun dropsWeakWrongClassAndInvalidRows() {
        assertTrue(OrientationHelper.parseRows(rows(north(confidence = 0.34f)), letterbox, 0.35f).isEmpty())
        assertTrue(OrientationHelper.parseRows(rows(north(classId = 8f)), letterbox, 0.35f).isEmpty())
        assertTrue(OrientationHelper.parseRows(rows(north(classId = 1.5f)), letterbox, 0.35f).isEmpty())
        assertTrue(OrientationHelper.parseRows(rows(north(confidence = 1.2f)), letterbox, 0.35f).isEmpty())
        val notFinite = north().also { it[10] = Float.NaN }
        assertTrue(OrientationHelper.parseRows(rows(notFinite), letterbox, 0.35f).isEmpty())
        val emptyBox = north().also { it[2] = it[0] }
        assertTrue(OrientationHelper.parseRows(rows(emptyBox), letterbox, 0.35f).isEmpty())
        // Unused rows are all zero and never count as detections.
        assertTrue(OrientationHelper.parseRows(rows(), letterbox, 0.05f).isEmpty())
    }

    @Test fun orientationCountsOnlyInsideTheTransmitterBox() {
        val north = OrientationHelper.parseRows(rows(north()), letterbox, 0.35f).single()
        // The North triangle's box centre is about (1011, 639) in frame pixels.
        val around = DetectionResult(940f, 560f, 1090f, 720f, 0.9f, 0)
        val elsewhere = DetectionResult(100f, 100f, 300f, 300f, 0.9f, 0)
        val justBeside = DetectionResult(1030f, 560f, 1180f, 720f, 0.9f, 0) // Centre 19 px left of it.
        assertTrue(OrientationHelper.isInsideTransmitter(north, around))
        assertTrue(!OrientationHelper.isInsideTransmitter(north, elsewhere))
        assertTrue(OrientationHelper.isInsideTransmitter(north, justBeside))                 // Within the 25% margin.
        assertTrue(!OrientationHelper.isInsideTransmitter(north, justBeside, margin = 0f))
        // No transmitter, no orientation; otherwise the strongest candidate inside the box.
        assertTrue(OrientationHelper.insideTransmitter(listOf(north), null).isEmpty())
        assertTrue(OrientationHelper.insideTransmitter(listOf(north), elsewhere).isEmpty())
        assertEquals(listOf(north), OrientationHelper.insideTransmitter(listOf(north), around))
        val weakerInside = north.copy(confidence = 0.5f, classId = 4)
        val strongerOutside = north.copy(left = 0f, right = 40f, top = 0f, bottom = 40f, confidence = 0.99f)
        assertEquals(listOf(weakerInside), OrientationHelper.insideTransmitter(listOf(strongerOutside, weakerInside), around))
    }

    @Test fun rejectsAnOutputOfTheWrongSize() {
        assertThrows(IllegalStateException::class.java) {
            OrientationHelper.parseRows(FloatArray(TransmitterHelper.OUTPUT_ROWS * TransmitterHelper.OUTPUT_COLUMNS), letterbox, 0.35f)
        }
    }

    @Test fun checkpointHashMatchesThePythonLoader() {
        val loader = java.io.File("src/main/python/checkpoint_runtime.py").readText()
        assertTrue(loader.contains("ORIENTATION_CHECKPOINT_SHA256 = '" + OrientationHelper.CHECKPOINT_SHA256 + "'"))
        val bytes = java.io.File("src/main/python/" + OrientationHelper.CHECKPOINT_FILE).readBytes()
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
        assertEquals(OrientationHelper.CHECKPOINT_SHA256, digest.joinToString("") { "%02x".format(it) })
    }
}
