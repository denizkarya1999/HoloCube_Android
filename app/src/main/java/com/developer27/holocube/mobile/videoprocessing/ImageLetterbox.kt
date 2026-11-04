package com.developer27.holocube.mobile.videoprocessing

import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Fits an image inside the square model input without stretching it.
 * Port of the Quest app's ImageLetterbox: the same scale and padding rules,
 * so boxes map back to the original frame identically on both devices.
 */
class ImageLetterbox(
    val imageWidth: Int,
    val imageHeight: Int,
    val modelSize: Int = YOLO26Helper.INPUT_SIZE
) {
    init {
        require(imageWidth > 0 && imageHeight > 0 && modelSize > 0) { "Image dimensions must be positive." }
    }

    val scale: Float = min(modelSize.toFloat() / imageWidth, modelSize.toFloat() / imageHeight)
    val resizedWidth: Int = (imageWidth * scale).roundToInt()
    val resizedHeight: Int = (imageHeight * scale).roundToInt()

    /** Left and top padding in model pixels; the right and bottom take the remainder. */
    val paddingLeft: Int = (modelSize - resizedWidth) / 2
    val paddingTop: Int = (modelSize - resizedHeight) / 2
    val paddingRight: Int get() = modelSize - resizedWidth - paddingLeft
    val paddingBottom: Int get() = modelSize - resizedHeight - paddingTop

    /** Maps a model-input rectangle (x1, y1, x2, y2) back to original image pixels, clamped to the image. */
    fun toOriginalImage(x1: Float, y1: Float, x2: Float, y2: Float): FloatArray = floatArrayOf(
        ((x1 - paddingLeft) / scale).coerceIn(0f, imageWidth.toFloat()),
        ((y1 - paddingTop) / scale).coerceIn(0f, imageHeight.toFloat()),
        ((x2 - paddingLeft) / scale).coerceIn(0f, imageWidth.toFloat()),
        ((y2 - paddingTop) / scale).coerceIn(0f, imageHeight.toFloat())
    )
}
