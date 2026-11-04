package com.developer27.holocube.mobile.videoprocessing

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.opencv.android.Utils
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.roundToInt

// ----------------- Data / Settings -----------------

/** One detected object: its class, confidence, and rectangle in frame pixels (top-left origin). */
data class DetectionResult(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val confidence: Float,
    val classId: Int
)

/** The annotated output of one frame plus what was found and how long inference took. */
class FrameResult(
    val frame: Bitmap,
    val detections: List<DetectionResult>,
    val inferenceMillis: Long
)

object Settings {
    object Inference {
        /** Minimum confidence: 0.35 means 35% (Quest default). */
        @Volatile var confidenceThreshold: Float = 0.35f
        /** Background CPU threads used by PyTorch. */
        @Volatile var cpuThreads: Int = 4
    }
    object BoundingBox {
        var enableBoundingBox = true

        // RGBA palette for OpenCV: Michigan Maize boxes with Michigan Blue label chips,
        // the same colours as the headset overlay.
        val boxColor = Scalar(255.0, 203.0, 5.0, 255.0)
        val labelBackground = Scalar(0.0, 39.0, 76.0, 255.0)
        val labelText = Scalar(255.0, 203.0, 5.0, 255.0)
        var boxThickness = 2
    }
}

/**
 * Frame processor for transmitter recognition, optimized to:
 *  - Reuse CoroutineScope, Mats, and the model input buffer
 *  - Run one frame at a time (the Python worker holds one model)
 *  - Guard OpenCV init
 */
class VideoProcessor(private val context: Context) {

    private val tag = "VideoProcessor"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val processingMutex = Mutex()
    @Volatile private var closed = false
    @Volatile private var loggedFirstDetection = false

    // --- Reusable OpenCV buffers ---
    // Create Mats only after the native lib is loaded
    private lateinit var frameMat: Mat
    private lateinit var inputMat: Mat
    private lateinit var overlayMat: Mat
    private var inputBytes = ByteArray(TransmitterHelper.INPUT_SIZE * TransmitterHelper.INPUT_SIZE * 4)

    // Guard against OpenCV init crash loops
    private val opencvLoaded = AtomicBoolean(false)

    init {
        try {
            System.loadLibrary("opencv_java4")
            opencvLoaded.set(true)
            frameMat = Mat()
            inputMat = Mat()
            overlayMat = Mat()
        } catch (e: UnsatisfiedLinkError) {
            Log.e(tag, "OpenCV failed to load: ${e.message}", e)
        }
    }

    val isOpenCvLoaded: Boolean get() = opencvLoaded.get()

    /**
     * Starts loading the checkpoint on the PyTorch thread. [onReady] runs on the
     * main thread with null on success or the error message otherwise.
     */
    fun warmUp(onReady: (String?) -> Unit) {
        if (closed) return
        scope.launch {
            val error = try {
                TransmitterHelper.ensureRuntime(context).awaitReady()
                TransmitterHelper.labels(context)
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(tag, "Model failed to load: ${e.message}", e)
                e.message ?: e.toString()
            }
            withContext(Dispatchers.Main) {
                if (!closed) onReady(error)
            }
        }
    }

    /**
     * Detects objects in [bitmap] (recycled afterwards). With [annotateFrame] false the
     * result is a transparent overlay of boxes sized like the frame; with true the boxes
     * are drawn onto a copy of the frame itself (used by Snapshot).
     */
    fun processFrame(bitmap: Bitmap, annotateFrame: Boolean = false, callback: (FrameResult?) -> Unit) {
        if (closed) {
            bitmap.recycle()
            return
        }
        scope.launch {
            val result = try {
                processingMutex.withLock {
                    if (closed) null else processFrameInternalYOLO(bitmap, annotateFrame)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(tag, "Error processing frame: ${e.message}", e)
                null
            } finally {
                bitmap.recycle()
            }
            withContext(Dispatchers.Main) {
                if (!closed) callback(result) else result?.frame?.recycle()
            }
        }
    }

    private suspend fun processFrameInternalYOLO(
        bitmap: Bitmap,
        annotateFrame: Boolean
    ): FrameResult? = withContext(Dispatchers.Default) {
        if (!opencvLoaded.get()) return@withContext null
        val worker = TransmitterHelper.ensureRuntime(context)
        if (!worker.isReady) return@withContext null // Still loading; try the next frame.
        if (bitmap.isRecycled) return@withContext null

        // 1) Frame → RGBA Mat, then the 320 × 320 letterboxed model input
        Utils.bitmapToMat(bitmap, frameMat)
        val letterbox = ImageLetterbox(frameMat.cols(), frameMat.rows())
        BitmapToRgbaTensor.letterbox(frameMat, letterbox, inputMat)
        inputBytes = BitmapToRgbaTensor.rgba(inputMat, inputBytes)

        // 2) Original checkpoint inference on the PyTorch thread
        val start = System.nanoTime()
        val rows = worker.detect(inputBytes)
        val inferenceMillis = (System.nanoTime() - start) / 1_000_000

        // 3) Decode the one-to-one head; no second NMS pass. Keep only the strongest
        //    valid Transmitter, regardless of UI settings (same rule as the headset).
        val detections = TransmitterHelper.parseRows(
            rows = rows,
            letterbox = letterbox,
            confidenceThreshold = Settings.Inference.confidenceThreshold,
            maxDetections = TransmitterHelper.MAX_DETECTIONS,
            expectedClasses = TransmitterHelper.labels(context).size
        )
        if (!loggedFirstDetection && detections.isNotEmpty()) {
            Log.i("HoloCube", "HOLOCUBE_TRANSMITTER_SINGLE_BOX: ${detections.size} detection selected; " +
                "highest confidence; limit ${TransmitterHelper.MAX_DETECTIONS}.")
            loggedFirstDetection = true
        }

        // 4) Drawing: onto the frame (snapshot) or a transparent overlay (live view)
        val canvas: Mat = if (annotateFrame) {
            frameMat
        } else {
            overlayMat.create(frameMat.rows(), frameMat.cols(), CvType.CV_8UC4)
            overlayMat.setTo(Scalar(0.0, 0.0, 0.0, 0.0))
            overlayMat
        }
        if (Settings.BoundingBox.enableBoundingBox) drawDetections(canvas, detections)

        // 5) Mat → Bitmap. The UI owns this bitmap; a later frame must not mutate it.
        val output = Bitmap.createBitmap(canvas.cols(), canvas.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(canvas, output)
        FrameResult(output, detections, inferenceMillis)
    }

    private fun drawDetections(canvas: Mat, detections: List<DetectionResult>) {
        val right = canvas.cols() - 1
        val bottom = canvas.rows() - 1
        val fontScale = (canvas.cols() / 640.0).coerceIn(0.5, 1.4)
        val textThickness = max(1, (fontScale * 2).roundToInt())
        val thickness = max(2, (Settings.BoundingBox.boxThickness * fontScale).roundToInt())
        val labelBuf = StringBuilder(48)
        val baseline = IntArray(1)

        for (det in detections) {
            val x1 = det.left.toInt().coerceIn(0, right)
            val y1 = det.top.toInt().coerceIn(0, bottom)
            val x2 = det.right.toInt().coerceIn(0, right)
            val y2 = det.bottom.toInt().coerceIn(0, bottom)

            Imgproc.rectangle(
                canvas,
                Point(x1.toDouble(), y1.toDouble()),
                Point(x2.toDouble(), y2.toDouble()),
                Settings.BoundingBox.boxColor,
                thickness,
                Imgproc.LINE_AA,
                0
            )

            // Same label as the headset: "Transmitter  98%"
            val confPct = (det.confidence * 100f + 0.5f).toInt()
            labelBuf.clear()
            labelBuf.append(TransmitterHelper.classNameForId(context, det.classId))
                .append("  ").append(confPct).append('%')
            val label = labelBuf.toString()

            val textSize = Imgproc.getTextSize(label, Imgproc.FONT_HERSHEY_SIMPLEX, fontScale, textThickness, baseline)
            val pad = (6 * fontScale).roundToInt()
            val chipHeight = textSize.height.toInt() + baseline[0] + pad * 2
            // Keep the chip inside the frame: above the box when possible, otherwise just inside it.
            val chipTop = if (y1 - chipHeight >= 0) y1 - chipHeight else y1
            val chipLeft = x1.coerceAtMost(max(0, right - textSize.width.toInt() - pad * 2))
            Imgproc.rectangle(
                canvas,
                Point(chipLeft.toDouble(), chipTop.toDouble()),
                Point((chipLeft + textSize.width + pad * 2).coerceAtMost(right.toDouble()), (chipTop + chipHeight).toDouble()),
                Settings.BoundingBox.labelBackground,
                Imgproc.FILLED,
                Imgproc.LINE_AA,
                0
            )
            Imgproc.putText(
                canvas,
                label,
                Point((chipLeft + pad).toDouble(), (chipTop + chipHeight - pad - baseline[0]).toDouble()),
                Imgproc.FONT_HERSHEY_SIMPLEX,
                fontScale,
                Settings.BoundingBox.labelText,
                textThickness,
                Imgproc.LINE_AA,
                false
            )
        }
    }

    fun close() {
        closed = true
        scope.launch {
            processingMutex.withLock {
                if (::frameMat.isInitialized) frameMat.release()
                if (::inputMat.isInitialized) inputMat.release()
                if (::overlayMat.isInitialized) overlayMat.release()
            }
            scope.cancel()
        }
    }
}
