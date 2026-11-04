package com.developer27.holocube.mobile.videoprocessing

import android.content.Context

/**
 * Helper that:
 *  - Loads/caches the embedded PyTorch runtime holding the original yolo26n.pt
 *  - Loads the 80 COCO labels in class-ID order
 *  - Parses YOLO26's end-to-end output rows and removes weak or invalid results
 *
 * YOLO26's one-to-one head already selects objects, so no NMS is applied
 * (the same rule as the Quest app's DetectionFilter).
 */
object YOLO26Helper {

    const val INPUT_SIZE = 320
    const val OUTPUT_ROWS = 300
    const val OUTPUT_COLUMNS = 6
    const val CHECKPOINT_FILE = "yolo26n.pt"
    const val CHECKPOINT_SHA256 = "9b09cc8bf347f0fc8a5f7657480587f25db09b34bf33b0652110fb03a8ad4fef"
    const val DEFAULT_CLASS_COUNT = 80
    private const val LABELS_ASSET_NAME = "coco.names.txt"

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

    /** One object name per line, in class-ID order (same file as the Quest app). */
    @Synchronized
    fun labels(context: Context): Array<String> {
        labels?.let { return it }
        val names = context.assets.open(LABELS_ASSET_NAME).bufferedReader().useLines { lines ->
            lines.map { it.trim() }.filter { it.isNotEmpty() }.toList()
        }.toTypedArray()
        check(names.isNotEmpty()) { "The class names file is empty." }
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
     * original frame through [letterbox], sorted by confidence, and limited.
     */
    fun parseRows(
        rows: FloatArray,
        letterbox: ImageLetterbox,
        confidenceThreshold: Float,
        maxDetections: Int,
        expectedClasses: Int = DEFAULT_CLASS_COUNT
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
