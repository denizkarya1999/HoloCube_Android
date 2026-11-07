package com.developer27.holocube.mobile.videoprocessing

/**
 * The models offered on the Settings → In-Device Local File Inference screen.
 *
 * Only the Transmitter Detection Model exists today. To add another, add an entry
 * here and give it its own helper (checkpoint, class list, reference fixture) beside
 * [TransmitterHelper]; the screen builds its model list from [entries].
 */
enum class LocalInferenceModel(val id: String, val displayName: String, val description: String) {
    TRANSMITTER(
        id = "transmitter",
        displayName = "Transmitter Detection Model",
        description = "YOLO26n · one class: Transmitter (the central pyramid) · " +
            TransmitterHelper.CHECKPOINT_FILE
    );

    companion object {
        val DEFAULT: LocalInferenceModel = TRANSMITTER

        /** The model with [id], or the default when the id is unknown or missing. */
        fun fromId(id: String?): LocalInferenceModel = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}
