package com.developer27.holocube.mobile.videoprocessing

import org.junit.Assert.*
import org.junit.Test

class YoloParserTest {
    private val letterbox = ImageLetterbox(320, 320)

    private fun rows(vararg entries: FloatArray): FloatArray {
        val output = FloatArray(TransmitterHelper.OUTPUT_ROWS * TransmitterHelper.OUTPUT_COLUMNS)
        entries.forEachIndexed { index, row -> row.copyInto(output, index * TransmitterHelper.OUTPUT_COLUMNS) }
        return output
    }

    @Test fun keepsOnlyTheStrongestValidTransmitter() {
        // Deliberately unsorted candidates, as in the Quest validation.
        val output = rows(
            floatArrayOf(10f, 10f, 50f, 50f, 0.80f, 0f),   // valid, weaker
            floatArrayOf(60f, 60f, 90f, 90f, 0.95f, 1f),   // stronger but wrong class
            floatArrayOf(100f, 100f, 40f, 140f, 0.99f, 0f), // stronger but inverted box
            floatArrayOf(150f, 150f, 190f, 190f, 0.90f, 0f), // valid, strongest
            floatArrayOf(200f, 200f, 240f, 240f, 0.20f, 0f)  // below threshold
        )
        val detections = TransmitterHelper.parseRows(output, letterbox, 0.35f)
        assertEquals(1, detections.size)
        assertEquals(0, detections.single().classId)
        assertEquals(0.90f, detections.single().confidence, 0f)
        assertEquals(150f, detections.single().left, 0.001f)
    }

    @Test fun noConfidentTransmitterMeansNoBox() {
        val output = rows(floatArrayOf(10f, 10f, 50f, 50f, 0.30f, 0f))
        assertTrue(TransmitterHelper.parseRows(output, letterbox, 0.35f).isEmpty())
        assertTrue(TransmitterHelper.parseRows(output, letterbox, 0.35f, maxDetections = 0).isEmpty())
    }

    @Test fun limitAndOrderingApplyWhenMoreBoxesAreRequested() {
        val output = rows(
            floatArrayOf(10f, 10f, 50f, 50f, 0.40f, 0f),
            floatArrayOf(60f, 60f, 90f, 90f, 0.90f, 5f),
            floatArrayOf(100f, 100f, 140f, 140f, 0.70f, 2f),
            floatArrayOf(150f, 150f, 190f, 190f, 0.20f, 7f)
        )
        val detections = TransmitterHelper.parseRows(output, letterbox, 0.35f, maxDetections = 2, expectedClasses = 80)
        assertEquals(listOf(5, 2), detections.map { it.classId })
        assertEquals(3, TransmitterHelper.parseRows(output, letterbox, 0.35f, maxDetections = 80, expectedClasses = 80).size)
    }

    @Test fun overlappingBoxesAreNotSuppressedAgain() {
        // The one-to-one head already selects objects: both rows survive when two are allowed.
        val output = rows(
            floatArrayOf(10f, 10f, 100f, 100f, 0.9f, 0f),
            floatArrayOf(12f, 12f, 102f, 102f, 0.8f, 0f)
        )
        assertEquals(2, TransmitterHelper.parseRows(output, letterbox, 0.35f, maxDetections = 24).size)
    }

    @Test fun invalidRowsAreSkipped() {
        val output = rows(
            floatArrayOf(10f, 10f, 50f, 50f, Float.NaN, 0f),          // non-finite confidence
            floatArrayOf(10f, 10f, 50f, 50f, 0.9f, 1f),               // class out of range for one class
            floatArrayOf(10f, 10f, 50f, 50f, 0.9f, 0.5f),             // fractional class
            floatArrayOf(50f, 50f, 10f, 10f, 0.9f, 0f),               // inverted box
            floatArrayOf(10f, 10f, Float.POSITIVE_INFINITY, 50f, 0.9f, 0f), // non-finite box
            floatArrayOf(10f, 10f, 50f, 50f, 1.5f, 0f),               // confidence above 1
            floatArrayOf(10f, 10f, 50f, 50f, 0.6f, 0f)                // valid
        )
        val detections = TransmitterHelper.parseRows(output, letterbox, 0.35f, maxDetections = 24)
        assertEquals(1, detections.size)
        assertEquals(0.6f, detections.single().confidence, 0f)
    }

    @Test fun boxesAreMappedThroughTheLetterbox() {
        val wide = ImageLetterbox(1280, 720)
        val output = rows(floatArrayOf(80f, 115f, 160f, 160f, 0.9f, 0f))
        val detection = TransmitterHelper.parseRows(output, wide, 0.35f).single()
        assertEquals(320f, detection.left, 0.001f)
        assertEquals(180f, detection.top, 0.001f)
        assertEquals(640f, detection.right, 0.001f)
        assertEquals(360f, detection.bottom, 0.001f)
    }

    @Test(expected = IllegalStateException::class)
    fun wrongOutputShapeIsRejected() {
        TransmitterHelper.parseRows(FloatArray(6), letterbox, 0.35f)
    }
}
