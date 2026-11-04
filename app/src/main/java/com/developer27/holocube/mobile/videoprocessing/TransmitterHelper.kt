package com.developer27.holocube.mobile.videoprocessing

import android.content.Context

/**
 * Helper that:
 *  - Loads/caches the embedded PyTorch runtime holding the trained Transmitter Recognition Model
 *  - Loads the single class name (`0: Transmitter`) and checks it
 *  - Parses YOLO26's end-to-end output rows and keeps only the strongest valid Transmitter
 *
 * YOLO26's one-to-one head already selects objects, so no NMS is applied, and the
 * transmitter pipeline keeps at most one box (the same rules as the Quest app's
 * DetectionFilter and TransmitterInference).
 */
object TransmitterHelper {

    const val INPUT_SIZE = 320
    const val OUTPUT_ROWS = 300
    const val OUTPUT_COLUMNS = 6
    const val CHECKPOINT_FILE = "holocube_transmitter.pt"
    const val CHECKPOINT_SHA256 = "3da988ae9e3aa5a66b175adb6275463d21f48828faac5ad77ebd0465570e7cf5"
    const val CLASS_NAME = "Transmitter"
    const val CLASS_COUNT = 1

    /** The pipeline always keeps only the highest-confidence valid Transmitter. */
    const val MAX_DETECTIONS = 1
    private const val LABELS_ASSET_NAME = "transmitter.names.txt"

    @Volatile
    private var worker: PyTorchWorker? = null

    @Volatile
    private var labels: Array<String>? = null

    /** Starts the runtime on first use; loading continues on its background thread. */
    @Synchronized
    fun ensureRuntime(context: Context, threads: Int = Settings.Inference.cpuThreads): PyTorchWorker {
        worker?.takeIf { !it.isClosed }?.let { return it }
        val created = PyTorchWorker(context.applicationContext, threads)
        worker = created
        return created
    }

    /** True when a runtime exists and finished its startup check without errors. */
    fun isRuntimeReady(): Boolean = try {
        worker?.isReady == true
    } catch (_: IllegalStateException) {
        false
    }

    fun setThreads(threads: Int) {
        worker?.setThreads(threads)
    }

    @Synchronized
    fun closeRuntime() {
        try {
            worker?.close()
        } finally {
            worker = null
        }
    }

    /** One object name per line, in class-ID order; the transmitter model has exactly one. */
    @Synchronized
    fun labels(context: Context): Array<String> {
        labels?.let { return it }
        val names = context.assets.open(LABELS_ASSET_NAME).bufferedReader().useLines { lines ->
            lines.map { it.trim() }.filter { it.isNotEmpty() }.toList()
        }.toTypedArray()
        check(names.size == CLASS_COUNT && names[0] == CLASS_NAME) {
            "The Transmitter Recognition Model requires class 0: $CLASS_NAME."
        }
        labels = names
        return names
    }

    fun classNameForId(context: Context, id: Int): String {
        val names = labels(context)
        return if (id in names.indices) names[id] else "N/A"
    }

    fun getConfidence(det: DetectionResult): Float = det.confidence

    /**
     * Reads YOLO26's final detections. Each row is [left, top, right, bottom,
     * confidence, class ID] in model-input pixels. Boxes are mapped back to the
     * original frame through [letterbox], sorted by confidence, and limited to
     * [maxDetections] (the transmitter pipeline passes [MAX_DETECTIONS]).
     */
    fun parseRows(
        rows: FloatArray,
        letterbox: ImageLetterbox,
        confidenceThreshold: Float,
        maxDetections: Int = MAX_DETECTIONS,
        expectedClasses: Int = CLASS_COUNT
    ): List<DetectionResult> {
        check(rows.size == OUTPUT_ROWS * OUTPUT_COLUMNS) {
            "Expected YOLO26 end-to-end output [1, $OUTPUT_ROWS, $OUTPUT_COLUMNS]."
        }
        if (maxDetections <= 0) return emptyList()

        val results = ArrayList<DetectionResult>()
        for (row in 0 until rows.size / OUTPUT_COLUMNS) {
            val base = row * OUTPUT_COLUMNS
            val confidence = rows[base + 4]
            val rawClass = rows[base + 5]
            if (!confidence.isFinite() || confidence < confidenceThreshold || confidence > 1f) continue
            if (!rawClass.isFinite() || rawClass < 0f || rawClass >= expectedClasses ||
                rawClass != rawClass.toInt().toFloat()) continue

            val x1 = rows[base]; val y1 = rows[base + 1]; val x2 = rows[base + 2]; val y2 = rows[base + 3]
            if (!x1.isFinite() || !y1.isFinite() || !x2.isFinite() || !y2.isFinite()) continue

            val box = letterbox.toOriginalImage(x1, y1, x2, y2)
            if (box[2] <= box[0] || box[3] <= box[1]) continue
            results.add(DetectionResult(box[0], box[1], box[2], box[3], confidence, rawClass.toInt()))
        }

        results.sortByDescending { it.confidence }
        return if (results.size > maxDetections) results.subList(0, maxDetections).toList() else results
    }
}
