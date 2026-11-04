package com.developer27.holocube.mobile.videoprocessing

import org.junit.Assert.*
import org.junit.Test

class YoloParserTest {
    private val letterbox = ImageLetterbox(320, 320)

    private fun rows(vararg entries: FloatArray): FloatArray {
        val output = FloatArray(YOLO26Helper.OUTPUT_ROWS * YOLO26Helper.OUTPUT_COLUMNS)
        entries.forEachIndexed { index, row -> row.copyInto(output, index * YOLO26Helper.OUTPUT_COLUMNS) }
        return output
    }

    @Test fun keepsConfidentRowsSortedAndLimited() {
        val output = rows(
            floatArrayOf(10f, 10f, 50f, 50f, 0.40f, 0f),
            floatArrayOf(60f, 60f, 90f, 90f, 0.90f, 5f),
            floatArrayOf(100f, 100f, 140f, 140f, 0.70f, 2f),
            floatArrayOf(150f, 150f, 190f, 190f, 0.20f, 7f)
        )
        val detections = YOLO26Helper.parseRows(output, letterbox, 0.35f, 2)
        assertEquals(listOf(5, 2), detections.map { it.classId })
        assertEquals(0.90f, detections.first().confidence, 0f)
        assertEquals(3, YOLO26Helper.parseRows(output, letterbox, 0.35f, 80).size)
        assertTrue(YOLO26Helper.parseRows(output, letterbox, 0.35f, 0).isEmpty())
    }

    @Test fun overlappingBoxesAreNotSuppressedAgain() {
        // The one-to-one head already selects objects: both rows survive.
        val output = rows(
            floatArrayOf(10f, 10f, 100f, 100f, 0.9f, 0f),
            floatArrayOf(12f, 12f, 102f, 102f, 0.8f, 0f)
        )
        assertEquals(2, YOLO26Helper.parseRows(output, letterbox, 0.35f, 24).size)
    }

    @Test fun invalidRowsAreSkipped() {
        val output = rows(
            floatArrayOf(10f, 10f, 50f, 50f, Float.NaN, 0f),          // non-finite confidence
            floatArrayOf(10f, 10f, 50f, 50f, 0.9f, 80f),              // class out of range
            floatArrayOf(10f, 10f, 50f, 50f, 0.9f, 1.5f),             // fractional class
            floatArrayOf(50f, 50f, 10f, 10f, 0.9f, 1f),               // inverted box
            floatArrayOf(10f, 10f, Float.POSITIVE_INFINITY, 50f, 0.9f, 1f), // non-finite box
            floatArrayOf(10f, 10f, 50f, 50f, 1.5f, 1f),               // confidence above 1
            floatArrayOf(10f, 10f, 50f, 50f, 0.9f, 3f)                // valid
        )
        val detections = YOLO26Helper.parseRows(output, letterbox, 0.35f, 24)
        assertEquals(1, detections.size)
        assertEquals(3, detections.single().classId)
    }

    @Test fun boxesAreMappedThroughTheLetterbox() {
        val wide = ImageLetterbox(1280, 720)
        val output = rows(floatArrayOf(80f, 115f, 160f, 160f, 0.9f, 0f))
        val detection = YOLO26Helper.parseRows(output, wide, 0.35f, 24).single()
        assertEquals(320f, detection.left, 0.001f)
        assertEquals(180f, detection.top, 0.001f)
        assertEquals(640f, detection.right, 0.001f)
        assertEquals(360f, detection.bottom, 0.001f)
    }

    @Test(expected = IllegalStateException::class)
    fun wrongOutputShapeIsRejected() {
        YOLO26Helper.parseRows(FloatArray(6), letterbox, 0.35f, 24)
    }
}
