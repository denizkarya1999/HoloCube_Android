package com.developer27.holocube.mobile.videoprocessing

import android.content.Context

/**
 * One detected orientation: the compass direction the pyramid is seen from, its
 * confidence, its box, and the triangle over the face showing that direction's cube
 * (top-left A, top-right B, bottom tip C), all in frame pixels with a top-left origin.
 */
data class OrientationResult(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val confidence: Float,
    val classId: Int,
    val ax: Float,
    val ay: Float,
    val bx: Float,
    val by: Float,
    val cx: Float,
    val cy: Float
)

/**
 * Helper for the Orientation Detection Model (YOLO26n-seg, eight classes), beside
 * [TransmitterHelper]. The model shares the transmitter's Python runtime and worker
 * thread; holocube_runtime.py reduces its mask to the triangle the labels were drawn
 * as, so Kotlin only reads rows.
 */
object OrientationHelper {

    /** The model was trained at 640 × 640; the cube pattern is unreadable at 320. */
    const val INPUT_SIZE = 640
    const val OUTPUT_ROWS = 5
    const val OUTPUT_COLUMNS = 12
    const val CHECKPOINT_FILE = "holocube_orientation.pt"
    const val CHECKPOINT_SHA256 = "71326fcb52f994cf7373120ee42abe5693dc0051a78fc735e2f45e2c540b4579"

    /** Class IDs 0–7, clockwise from North. */
    val CLASS_NAMES: List<String> = listOf(
        "North", "Northeast", "East", "Southeast", "South", "Southwest", "West", "Northwest"
    )

    /** One hologram is in view, so only the strongest valid orientation is kept. */
    const val MAX_DETECTIONS = 1

    /**
     * An orientation is shown only when its box centre lies inside the transmitter box
     * grown by this fraction of its width and height on every side.
     */
    const val TRANSMITTER_BOX_MARGIN = 0.25f
    private const val LABELS_ASSET_NAME = "orientation.names.txt"

    @Volatile
    private var labels: Array<String>? = null

    /** One class name per line, in class-ID order; must be exactly [CLASS_NAMES]. */
    @Synchronized
    fun labels(context: Context): Array<String> {
        labels?.let { return it }
        val names = context.assets.open(LABELS_ASSET_NAME).bufferedReader().useLines { lines ->
            lines.map { it.trim() }.filter { it.isNotEmpty() }.toList()
        }
        check(names == CLASS_NAMES) {
            "The Orientation Detection Model requires the classes ${CLASS_NAMES.joinToString()}."
        }
        return names.toTypedArray().also { labels = it }
    }

    fun classNameForId(id: Int): String = CLASS_NAMES.getOrElse(id) { "N/A" }

    /** True when the centre of [orientation]'s box lies inside [transmitter] grown by [margin]. */
    fun isInsideTransmitter(
        orientation: OrientationResult,
        transmitter: DetectionResult,
        margin: Float = TRANSMITTER_BOX_MARGIN
    ): Boolean {
        val growX = (transmitter.right - transmitter.left) * margin
        val growY = (transmitter.bottom - transmitter.top) * margin
        val x = (orientation.left + orientation.right) / 2f
        val y = (orientation.top + orientation.bottom) / 2f
        return x >= transmitter.left - growX && x <= transmitter.right + growX &&
            y >= transmitter.top - growY && y <= transmitter.bottom + growY
    }

    /**
     * The transmitter comes first: the strongest of [candidates] (already sorted by
     * confidence) that lies inside [transmitter], or nothing when there is no transmitter.
     */
    fun insideTransmitter(candidates: List<OrientationResult>, transmitter: DetectionResult?): List<OrientationResult> {
        if (transmitter == null) return emptyList()
        return candidates.filter { isInsideTransmitter(it, transmitter) }.take(MAX_DETECTIONS)
    }

    /**
     * Reads the runtime's rows. Each row is [left, top, right, bottom, confidence,
     * class ID, ax, ay, bx, by, cx, cy] in model-input pixels, strongest first. Boxes
     * and triangle corners are mapped back to the original frame through [letterbox],
     * and at most [maxDetections] valid rows are kept.
     */
    fun parseRows(
        rows: FloatArray,
        letterbox: ImageLetterbox,
        confidenceThreshold: Float,
        maxDetections: Int = MAX_DETECTIONS
    ): List<OrientationResult> {
        check(rows.size == OUTPUT_ROWS * OUTPUT_COLUMNS) {
            "Expected orientation rows [$OUTPUT_ROWS, $OUTPUT_COLUMNS]."
        }
        if (maxDetections <= 0) return emptyList()

        val results = ArrayList<OrientationResult>()
        for (row in 0 until OUTPUT_ROWS) {
            val base = row * OUTPUT_COLUMNS
            val confidence = rows[base + 4]
            val rawClass = rows[base + 5]
            if (!confidence.isFinite() || confidence < confidenceThreshold || confidence > 1f) continue
            if (!rawClass.isFinite() || rawClass < 0f || rawClass >= CLASS_NAMES.size ||
                rawClass != rawClass.toInt().toFloat()) continue
            if ((0 until OUTPUT_COLUMNS).any { !rows[base + it].isFinite() }) continue

            val box = letterbox.toOriginalImage(rows[base], rows[base + 1], rows[base + 2], rows[base + 3])
            if (box[2] <= box[0] || box[3] <= box[1]) continue
            val a = letterbox.toOriginalPoint(rows[base + 6], rows[base + 7])
            val b = letterbox.toOriginalPoint(rows[base + 8], rows[base + 9])
            val c = letterbox.toOriginalPoint(rows[base + 10], rows[base + 11])
            results.add(
                OrientationResult(
                    box[0], box[1], box[2], box[3], confidence, rawClass.toInt(),
                    a[0], a[1], b[0], b[1], c[0], c[1]
                )
            )
        }

        results.sortByDescending { it.confidence }
        return if (results.size > maxDetections) results.subList(0, maxDetections).toList() else results
    }
}
