package com.developer27.holocube.mobile.videoprocessing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The two Models to Run checkboxes. */
class ModelSelectionTest {
    @Test fun bothModelsMakeOrientationWaitForTheTransmitter() {
        assertTrue(ModelSelection.BOTH.orientationNeedsTransmitter)
        assertFalse(ModelSelection.BOTH.isEmpty)
    }

    @Test fun aModelTickedAloneRunsByItself() {
        val orientationOnly = ModelSelection(transmitter = false, orientation = true)
        assertFalse(orientationOnly.orientationNeedsTransmitter)
        assertFalse(orientationOnly.isEmpty)
        val transmitterOnly = ModelSelection(transmitter = true, orientation = false)
        assertFalse(transmitterOnly.orientationNeedsTransmitter)
        assertFalse(transmitterOnly.isEmpty)
    }

    @Test fun nothingTickedRunsNothing() {
        assertTrue(ModelSelection(transmitter = false, orientation = false).isEmpty)
    }

    @Test fun theSettingsValuesBecomeTheSelection() {
        val before = ModelSelection.fromSettings()
        try {
            Settings.Inference.runTransmitter = false
            Settings.Inference.runOrientation = true
            assertEquals(ModelSelection(transmitter = false, orientation = true), ModelSelection.fromSettings())
        } finally {
            Settings.Inference.runTransmitter = before.transmitter
            Settings.Inference.runOrientation = before.orientation
        }
    }
}
