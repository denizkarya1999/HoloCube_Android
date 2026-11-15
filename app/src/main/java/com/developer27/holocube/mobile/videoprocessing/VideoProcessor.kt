package com.developer27.holocube.mobile.videoprocessing

import android.content.Context
import android.content.SharedPreferences
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
import org.opencv.core.MatOfPoint
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

/**
 * The annotated output of one frame plus what was found and how long inference took.
 * [orientations] and [orientationMillis] are filled when the Orientation Detection Model
 * ran on the frame, which it does only after a transmitter was detected ([orientationRan]);
 * [orientationUnavailable] says why it could not when it was asked for.
 */
class FrameResult(
    val frame: Bitmap,
    val detections: List<DetectionResult>,
    val inferenceMillis: Long,
    val orientations: List<OrientationResult> = emptyList(),
    val orientationMillis: Long = 0,
    val orientationRan: Boolean = false,
    val orientationUnavailable: String? = null
)

/** Which models run on a frame. */
enum class FramePipeline {
    /** The camera screen: the transmitter, then, inside its box, the orientation when switched on. */
    CAMERA,
    /** Model testing: only the Transmitter Detection Model. */
    TRANSMITTER,
    /** Model testing: the transmitter, then the Orientation Detection Model inside its box. */
    ORIENTATION
}

object Settings {
    object Inference {
        /** Preference keys of the Settings page. */
        const val KEY_CONFIDENCE = "confidence_threshold"
        const val KEY_CPU_THREADS = "cpu_threads"
        const val KEY_ORIENTATION = "orientation_detection"

        /** Minimum confidence: 0.35 means 35% (Quest default). */
        @Volatile var confidenceThreshold: Float = 0.35f
        /** Background CPU threads used by PyTorch. */
        @Volatile var cpuThreads: Int = 4
        /** Whether the camera screen also runs the Orientation Detection Model on each frame. */
        @Volatile var orientationEnabled: Boolean = true

        /** Reads the Settings page values; a changed thread count reaches a running runtime. */
        fun applyPreferences(preferences: SharedPreferences) {
            confidenceThreshold = preferences.getInt(KEY_CONFIDENCE, 35).coerceIn(5, 100) / 100f
            orientationEnabled = preferences.getString(KEY_ORIENTATION, "on") != "off"
            val threads = preferences.getString(KEY_CPU_THREADS, "4")?.toIntOrNull()?.coerceIn(1, 8) ?: 4
            if (threads != cpuThreads) {
                cpuThreads = threads
                TransmitterHelper.setThreads(threads)
            }
        }
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
 * Frame processor for transmitter recognition and orientation detection, optimized to:
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
    @Volatile private var loggedFirstOrientation = false

    // --- Reusable OpenCV buffers ---
    // Create Mats only after the native lib is loaded
    private lateinit var frameMat: Mat
    private lateinit var inputMat: Mat
    private lateinit var overlayMat: Mat
    private lateinit var orientationInputMat: Mat
    private var inputBytes = ByteArray(TransmitterHelper.INPUT_SIZE * TransmitterHelper.INPUT_SIZE * 4)
    private var orientationBytes = ByteArray(0) // 640 × 640 RGBA; allocated on first use.

    // Guard against OpenCV init crash loops
    private val opencvLoaded = AtomicBoolean(false)

    init {
        try {
            System.loadLibrary("opencv_java4")
            opencvLoaded.set(true)
            frameMat = Mat()
            inputMat = Mat()
            orientationInputMat = Mat()
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
                OrientationHelper.labels(context)
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
     * Runs the models of [pipeline] on [bitmap] (recycled afterwards). With [annotateFrame]
     * false the result is a transparent overlay sized like the frame; with true the box and
     * triangle are drawn onto a copy of the frame itself (used by the device tests and tools).
     */
    fun processFrame(
        bitmap: Bitmap,
        annotateFrame: Boolean = false,
        pipeline: FramePipeline = FramePipeline.CAMERA,
        callback: (FrameResult?) -> Unit
    ) {
        if (closed) {
            bitmap.recycle()
            return
        }
        scope.launch {
            val result = try {
                processingMutex.withLock {
                    if (closed) null else processFrameInternalYOLO(bitmap, annotateFrame, pipeline)
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
        annotateFrame: Boolean,
        pipeline: FramePipeline
    ): FrameResult? = withContext(Dispatchers.Default) {
        if (!opencvLoaded.get()) return@withContext null
        val worker = TransmitterHelper.ensureRuntime(context)
        if (!worker.isReady) return@withContext null // Still loading; try the next frame.
        if (bitmap.isRecycled) return@withContext null

        Utils.bitmapToMat(bitmap, frameMat)
        var detections: List<DetectionResult> = emptyList()
        var inferenceMillis = 0L
        run {
            // 1) Frame → RGBA Mat, then the 320 × 320 letterboxed model input
            val letterbox = ImageLetterbox(frameMat.cols(), frameMat.rows())
            BitmapToRgbaTensor.letterbox(frameMat, letterbox, inputMat)
            inputBytes = BitmapToRgbaTensor.rgba(inputMat, inputBytes)

            // 2) Original checkpoint inference on the PyTorch thread
            val start = System.nanoTime()
            val rows = worker.detect(inputBytes)
            inferenceMillis = (System.nanoTime() - start) / 1_000_000

            // 3) Decode the one-to-one head; no second NMS pass. Keep only the strongest
            //    valid Transmitter, regardless of UI settings (same rule as the headset).
            detections = TransmitterHelper.parseRows(
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
        }

        // 3b) Orientation Detection Model: only after a transmitter was detected. It sees
        //     its own 640 × 640 letterbox of the whole frame (it was trained on whole
        //     frames and fails on a crop), and its result is kept only inside the
        //     transmitter's box.
        val wantOrientation = pipeline == FramePipeline.ORIENTATION ||
            (pipeline == FramePipeline.CAMERA && Settings.Inference.orientationEnabled)
        val transmitter = detections.firstOrNull()
        var orientations: List<OrientationResult> = emptyList()
        var orientationMillis = 0L
        val orientationRan = wantOrientation && worker.isOrientationReady && transmitter != null
        val orientationUnavailable =
            if (wantOrientation && !worker.isOrientationReady) worker.orientationError ?: "not loaded" else null
        if (orientationRan) {
            val letterbox = ImageLetterbox(frameMat.cols(), frameMat.rows(), OrientationHelper.INPUT_SIZE)
            BitmapToRgbaTensor.letterbox(frameMat, letterbox, orientationInputMat)
            orientationBytes = BitmapToRgbaTensor.rgba(orientationInputMat, orientationBytes)
            val start = System.nanoTime()
            val rows = worker.detectOrientation(orientationBytes)
            orientationMillis = (System.nanoTime() - start) / 1_000_000
            orientations = OrientationHelper.insideTransmitter(
                OrientationHelper.parseRows(
                    rows = rows,
                    letterbox = letterbox,
                    confidenceThreshold = Settings.Inference.confidenceThreshold,
                    maxDetections = OrientationHelper.OUTPUT_ROWS
                ),
                transmitter
            )
            if (!loggedFirstOrientation && orientations.isNotEmpty()) {
                Log.i("HoloCube", "HOLOCUBE_ORIENTATION_SINGLE_TRIANGLE: " +
                    OrientationHelper.classNameForId(orientations[0].classId) +
                    " selected inside the transmitter box; highest confidence; limit ${OrientationHelper.MAX_DETECTIONS}.")
                loggedFirstOrientation = true
            }
        }

        // 4) Drawing: onto the frame (tests) or a transparent overlay (live view)
        val canvas: Mat = if (annotateFrame) {
            frameMat
        } else {
            overlayMat.create(frameMat.rows(), frameMat.cols(), CvType.CV_8UC4)
            overlayMat.setTo(Scalar(0.0, 0.0, 0.0, 0.0))
            overlayMat
        }
        if (Settings.BoundingBox.enableBoundingBox) {
            drawDetections(canvas, detections)
            drawOrientations(canvas, orientations)
        }

        // 5) Mat → Bitmap. The UI owns this bitmap; a later frame must not mutate it.
        val output = Bitmap.createBitmap(canvas.cols(), canvas.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(canvas, output)
        FrameResult(output, detections, inferenceMillis, orientations, orientationMillis,
            orientationRan, orientationUnavailable)
    }

    /** The triangle over the face, with a "North  93%" chip under its tip. */
    private fun drawOrientations(canvas: Mat, orientations: List<OrientationResult>) {
        val right = canvas.cols() - 1
        val bottom = canvas.rows() - 1
        val fontScale = (canvas.cols() / 640.0).coerceIn(0.5, 1.4)
        val textThickness = max(1, (fontScale * 2).roundToInt())
        val thickness = max(2, (Settings.BoundingBox.boxThickness * fontScale).roundToInt())
        val baseline = IntArray(1)

        for (orientation in orientations) {
            val outline = MatOfPoint(
                Point(orientation.ax.toDouble(), orientation.ay.toDouble()),
                Point(orientation.bx.toDouble(), orientation.by.toDouble()),
                Point(orientation.cx.toDouble(), orientation.cy.toDouble())
            )
            try {
                Imgproc.polylines(canvas, listOf(outline), true, Settings.BoundingBox.boxColor, thickness, Imgproc.LINE_AA)
            } finally {
                outline.release()
            }

            val confPct = (orientation.confidence * 100f + 0.5f).toInt()
            val label = OrientationHelper.classNameForId(orientation.classId) + "  " + confPct + "%"
            val textSize = Imgproc.getTextSize(label, Imgproc.FONT_HERSHEY_SIMPLEX, fontScale, textThickness, baseline)
            val pad = (6 * fontScale).roundToInt()
            val chipWidth = textSize.width.toInt() + pad * 2
            val chipHeight = textSize.height.toInt() + baseline[0] + pad * 2
            // Under the tip so it does not cover the transmitter's chip above the box;
            // above the triangle when there is no room below.
            val tipY = orientation.cy.toInt().coerceIn(0, bottom)
            val topY = minOf(orientation.ay, orientation.by).toInt().coerceIn(0, bottom)
            val chipTop = if (tipY + pad + chipHeight <= bottom) tipY + pad else max(0, topY - chipHeight - pad)
            val chipLeft = (orientation.cx.toInt() - chipWidth / 2).coerceIn(0, max(0, right - chipWidth))
            Imgproc.rectangle(
                canvas,
                Point(chipLeft.toDouble(), chipTop.toDouble()),
                Point((chipLeft + chipWidth).coerceAtMost(right).toDouble(), (chipTop + chipHeight).toDouble()),
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
                if (::orientationInputMat.isInitialized) orientationInputMat.release()
                if (::overlayMat.isInitialized) overlayMat.release()
            }
            scope.cancel()
        }
    }
}
