package com.developer27.holocube.mobile.videoprocessing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalInferenceModelTest {
    @Test fun transmitterIsTheDefaultAndTheOnlyModelToday() {
        assertEquals(listOf(LocalInferenceModel.TRANSMITTER), LocalInferenceModel.entries)
        assertEquals(LocalInferenceModel.TRANSMITTER, LocalInferenceModel.DEFAULT)
        assertEquals("Transmitter Detection Model", LocalInferenceModel.TRANSMITTER.displayName)
        assertTrue(LocalInferenceModel.TRANSMITTER.description.contains(TransmitterHelper.CHECKPOINT_FILE))
    }

    @Test fun unknownIdsFallBackToTheDefault() {
        assertEquals(LocalInferenceModel.TRANSMITTER, LocalInferenceModel.fromId("transmitter"))
        assertEquals(LocalInferenceModel.DEFAULT, LocalInferenceModel.fromId("not-a-model"))
        assertEquals(LocalInferenceModel.DEFAULT, LocalInferenceModel.fromId(null))
    }

    @Test fun entriesHaveUniqueIdsAndNames() {
        val entries = LocalInferenceModel.entries
        assertEquals(entries.size, entries.map { it.id }.toSet().size)
        assertEquals(entries.size, entries.map { it.displayName }.toSet().size)
    }
}
