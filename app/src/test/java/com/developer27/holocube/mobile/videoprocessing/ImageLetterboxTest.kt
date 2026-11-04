package com.developer27.holocube.mobile.videoprocessing

import org.junit.Assert.*
import org.junit.Test

class ImageLetterboxTest {
    @Test fun wideFrameIsPaddedTopAndBottom() {
        val letterbox = ImageLetterbox(1280, 720)
        assertEquals(0.25f, letterbox.scale, 0f)
        assertEquals(320, letterbox.resizedWidth)
        assertEquals(180, letterbox.resizedHeight)
        assertEquals(0, letterbox.paddingLeft)
        assertEquals(70, letterbox.paddingTop)
        assertEquals(0, letterbox.paddingRight)
        assertEquals(70, letterbox.paddingBottom)
    }

    @Test fun tallReferenceImageIsPaddedLeftAndRight() {
        // The bus fixture used by the startup check is 810 × 1080.
        val letterbox = ImageLetterbox(810, 1080)
        assertEquals(240, letterbox.resizedWidth)
        assertEquals(320, letterbox.resizedHeight)
        assertEquals(40, letterbox.paddingLeft)
        assertEquals(0, letterbox.paddingTop)
        assertEquals(40, letterbox.paddingRight)
    }

    @Test fun modelBoxMapsBackToOriginalPixelsAndClamps() {
        val letterbox = ImageLetterbox(1280, 720)
        val full = letterbox.toOriginalImage(0f, 70f, 320f, 250f)
        assertArrayEquals(floatArrayOf(0f, 0f, 1280f, 720f), full, 0.001f)
        val clamped = letterbox.toOriginalImage(-10f, 0f, 400f, 320f)
        assertArrayEquals(floatArrayOf(0f, 0f, 1280f, 720f), clamped, 0.001f)
        val inner = letterbox.toOriginalImage(80f, 115f, 160f, 160f)
        assertArrayEquals(floatArrayOf(320f, 180f, 640f, 360f), inner, 0.001f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsEmptyImages() {
        ImageLetterbox(0, 720)
    }
}
