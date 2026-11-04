package com.developer27.holocube.mobile.videoprocessing

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * Prepares the model input the same way the Quest app's GPU shader does:
 * fit the frame into 320 × 320 with RGB 114 gray padding, then hand the raw
 * RGBA bytes (top row first) to the Python runtime, which normalizes them to 0–1.
 */
object BitmapToRgbaTensor {
    /** Ultralytics' letterbox gray, with an opaque alpha channel. */
    val PADDING: Scalar = Scalar(114.0, 114.0, 114.0, 255.0)

    /** Letterboxes an RGBA frame [Mat] into [output] (modelSize × modelSize, CV_8UC4). */
    fun letterbox(frame: Mat, letterbox: ImageLetterbox, output: Mat) {
        require(frame.type() == CvType.CV_8UC4) { "Expected an RGBA frame." }
        val resized = Mat()
        try {
            Imgproc.resize(
                frame, resized,
                Size(letterbox.resizedWidth.toDouble(), letterbox.resizedHeight.toDouble()),
                0.0, 0.0, Imgproc.INTER_LINEAR
            )
            Core.copyMakeBorder(
                resized, output,
                letterbox.paddingTop, letterbox.paddingBottom,
                letterbox.paddingLeft, letterbox.paddingRight,
                Core.BORDER_CONSTANT, PADDING
            )
        } finally {
            resized.release()
        }
    }

    /** Copies a letterboxed RGBA [Mat] into the byte layout holocube_runtime.py expects. */
    fun rgba(input: Mat, reuse: ByteArray? = null): ByteArray {
        require(input.type() == CvType.CV_8UC4 && input.isContinuous) { "Expected a continuous RGBA Mat." }
        val length = input.rows() * input.cols() * 4
        val bytes = if (reuse != null && reuse.size == length) reuse else ByteArray(length)
        input.get(0, 0, bytes)
        return bytes
    }

    /** Convenience for tests and snapshots: a whole bitmap → letterboxed RGBA bytes. */
    fun fromBitmap(bitmap: Bitmap, modelSize: Int = TransmitterHelper.INPUT_SIZE): ByteArray {
        val source = if (bitmap.config == Bitmap.Config.ARGB_8888) bitmap else bitmap.copy(Bitmap.Config.ARGB_8888, false)
        val frame = Mat()
        val input = Mat()
        try {
            Utils.bitmapToMat(source, frame)
            letterbox(frame, ImageLetterbox(frame.cols(), frame.rows(), modelSize), input)
            return rgba(input)
        } finally {
            frame.release()
            input.release()
            if (source !== bitmap) source.recycle()
        }
    }
}
