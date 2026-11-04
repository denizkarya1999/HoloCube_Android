package com.developer27.holocube.mobile

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.preference.PreferenceManager
import com.developer27.holocube.mobile.camera.CameraHelper
import com.developer27.holocube.mobile.camera.VideoRecorder
import com.developer27.holocube.mobile.databinding.ActivityMainBinding
import com.developer27.holocube.mobile.videoprocessing.Settings
import com.developer27.holocube.mobile.videoprocessing.VideoProcessor
import com.developer27.holocube.mobile.videoprocessing.YOLO26Helper
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Start here: camera frame → YOLO26 detections → boxes over the live preview.
 *
 * Phone edition of the headset's MainApp. The controller buttons map to on-screen buttons:
 * A → Start/Stop Inference, hold B → Record/Stop, X → About, Y → Settings, Menu → Guide.
 */
class MainActivity : AppCompatActivity() {
    private lateinit var viewBinding: ActivityMainBinding
    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var cameraHelper: CameraHelper
    private lateinit var videoRecorder: VideoRecorder
    private var videoProcessor: VideoProcessor? = null
    private var isInferring = false
    private var isProcessingFrame = false
    private var activityResumed = false
    private var permissionRequested = false
    private var modelReady = false
    private var snapshotShown = false
    private var frameGeneration = 0
    private var lastDetectionFinishedAt = 0L
    private var statusMessage = ""
    private val handler = Handler(Looper.getMainLooper())
    private val restoreStatus = Runnable { viewBinding.statusText.text = statusMessage }
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key in setOf(KEY_CONFIDENCE, KEY_MAX_DETECTIONS, KEY_CPU_THREADS)) applyDetectionSettings()
    }

    private val CAMERA_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    private val RECORDING_PERMISSIONS: Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) arrayOf(Manifest.permission.RECORD_AUDIO)
        else arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.WRITE_EXTERNAL_STORAGE)

    private lateinit var requestPermissionLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var requestRecordingPermissionLauncher: ActivityResultLauncher<Array<String>>

    companion object {
        const val KEY_CONFIDENCE = "confidence_threshold"
        const val KEY_MAX_DETECTIONS = "max_detections"
        const val KEY_CPU_THREADS = "cpu_threads"

        /** "Inference Mode: Off" stays visible this long, as on the headset. */
        private const val INFERENCE_OFF_NOTICE_MS = 3000L
        /** Saved-file and recording-error messages stay visible this long, as on the headset. */
        private const val RECORDING_FEEDBACK_MS = 6000L
        /** Minimum pause between detections, as on the headset (Detection Interval). */
        private const val DETECTION_INTERVAL_MS = 100L
        /** Frames are captured with their long edge limited to this many pixels. */
        private const val MAX_FRAME_EDGE = 960
    }

    private val textureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
            cameraHelper.configureTransform(width, height)
            if (activityResumed && allPermissionsGranted() && !snapshotShown) cameraHelper.openCamera()
        }
        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
            cameraHelper.configureTransform(width, height)
        }
        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
            cameraHelper.closeCamera()
            return true
        }
        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
            if (isInferring) processFrameWithVideoProcessor()
        }
    }

    // Portrait is intentional: the overlay geometry and LiFind's layout assume it.
    @SuppressLint("SourceLockedOrientationActivity")
    override fun onCreate(savedInstanceState: Bundle?) {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        installSplashScreen()
        super.onCreate(savedInstanceState)
        viewBinding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(viewBinding.root)

        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        cameraHelper = CameraHelper(this, viewBinding, sharedPreferences)
        videoRecorder = VideoRecorder(this)
        videoProcessor = VideoProcessor(this)

        viewBinding.processedFrameView.visibility = View.GONE

        viewBinding.titleContainer.setOnClickListener { openUrl(getString(R.string.url_website)) }

        requestPermissionLauncher =
            registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
                if (permissions[Manifest.permission.CAMERA] == true) {
                    if (activityResumed && viewBinding.viewFinder.isAvailable) cameraHelper.openCamera()
                } else {
                    Toast.makeText(this, R.string.camera_permission_required, Toast.LENGTH_SHORT).show()
                    showStatus(getString(R.string.status_waiting_permission))
                }
            }
        requestRecordingPermissionLauncher =
            registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
                if (permissions.values.all { it }) startRecording()
                else Toast.makeText(this, R.string.recording_permission_required, Toast.LENGTH_LONG).show()
            }
        viewBinding.viewFinder.surfaceTextureListener = textureListener

        // Switch camera button
        viewBinding.switchCameraButton.setOnClickListener {
            clearResults()
            if (allPermissionsGranted()) {
                cameraHelper.toggleCameraFacing()
            } else {
                requestPermissionLauncher.launch(CAMERA_PERMISSIONS)
            }
        }

        viewBinding.startInferenceButton.setOnClickListener {
            if (isInferring) stopInference() else startInference()
        }

        viewBinding.TakeSnapshotButton.setOnClickListener {
            takeSnapshotAndProcessOnce()
        }

        viewBinding.recordButton.setOnClickListener {
            if (videoRecorder.isRecording) stopRecording() else startRecording()
        }

        viewBinding.clearButton.setOnClickListener {
            clearResults()
            restartCamera()
        }

        viewBinding.guideButton.setOnClickListener { showGuideDialog() }
        viewBinding.aboutButton.setOnClickListener {
            startActivity(Intent(this, AboutHoloCubeActivity::class.java))
        }
        viewBinding.settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        cameraHelper.setupZoomControls()
        sharedPreferences.registerOnSharedPreferenceChangeListener(preferenceListener)
        applyDetectionSettings()
        warmUpModel()
    }

    // ----------------------------
    // Model
    // ----------------------------
    private fun warmUpModel() {
        showStatus(getString(R.string.status_loading))
        videoProcessor?.warmUp { error ->
            modelReady = error == null
            if (error != null) {
                showStatus(getString(R.string.status_detection_stopped, error))
            } else if (!isInferring && !snapshotShown) {
                showStatus(getString(R.string.status_ready))
            }
        }
    }

    private fun applyDetectionSettings() {
        Settings.Inference.confidenceThreshold =
            sharedPreferences.getInt(KEY_CONFIDENCE, 35).coerceIn(5, 100) / 100f
        Settings.Inference.maxDetections =
            sharedPreferences.getInt(KEY_MAX_DETECTIONS, 24).coerceIn(1, 80)
        val threads = sharedPreferences.getString(KEY_CPU_THREADS, "4")?.toIntOrNull()?.coerceIn(1, 8) ?: 4
        if (threads != Settings.Inference.cpuThreads) {
            Settings.Inference.cpuThreads = threads
            YOLO26Helper.setThreads(threads)
        }
    }

    // ----------------------------
    // Live inference (headset button A)
    // ----------------------------
    private fun startInference() {
        if (!allPermissionsGranted()) {
            requestPermissionLauncher.launch(CAMERA_PERMISSIONS)
            return
        }
        if (snapshotShown) {
            clearResults()
            restartCamera()
        }
        cameraHelper.openCamera()
        isInferring = true
        viewBinding.startInferenceButton.text = getString(R.string.stop_inference)
        viewBinding.startInferenceButton.backgroundTintList = ContextCompat.getColorStateList(this, R.color.red)
        viewBinding.processedFrameView.setImageDrawable(null)
        viewBinding.processedFrameView.visibility = View.VISIBLE
        showStatus(getString(if (modelReady) R.string.status_detecting else R.string.status_loading))
    }

    private fun stopInference() {
        frameGeneration++
        isInferring = false
        isProcessingFrame = false
        viewBinding.startInferenceButton.text = getString(R.string.start_inference)
        viewBinding.startInferenceButton.backgroundTintList = ContextCompat.getColorStateList(this, R.color.blue)
        viewBinding.processedFrameView.visibility = View.GONE
        viewBinding.processedFrameView.setImageDrawable(null)
        statusMessage = getString(R.string.status_paused)
        showTransientStatus(getString(R.string.status_inference_off), INFERENCE_OFF_NOTICE_MS)
    }

    private fun processFrameWithVideoProcessor() {
        val vp = videoProcessor ?: return
        if (isProcessingFrame || !activityResumed || !modelReady) return
        if (SystemClock.uptimeMillis() - lastDetectionFinishedAt < DETECTION_INTERVAL_MS) return
        val bitmap = captureFrame() ?: return
        val generation = frameGeneration
        isProcessingFrame = true
        vp.processFrame(bitmap) { result ->
            if (generation != frameGeneration || !activityResumed) {
                result?.frame?.recycle()
                return@processFrame
            }
            isProcessingFrame = false
            lastDetectionFinishedAt = SystemClock.uptimeMillis()
            if (result == null) return@processFrame
            if (isInferring) {
                viewBinding.processedFrameView.setImageBitmap(result.frame)
                showStatus(getString(R.string.status_detections, result.detections.size, result.inferenceMillis))
            } else {
                result.frame.recycle()
            }
        }
    }

    /** Grabs the displayed preview at a reduced size for detection and overlay drawing. */
    private fun captureFrame(): Bitmap? {
        val view = viewBinding.viewFinder
        if (!view.isAvailable || view.width == 0 || view.height == 0) return null
        val scale = (MAX_FRAME_EDGE.toFloat() / max(view.width, view.height)).coerceAtMost(1f)
        val width = max(1, (view.width * scale).roundToInt())
        val height = max(1, (view.height * scale).roundToInt())
        return view.getBitmap(width, height)
    }

    // ----------------------------
    // Snapshot: detect one frame and freeze it
    // ----------------------------
    private fun takeSnapshotAndProcessOnce() {
        val vp = videoProcessor ?: return
        if (isInferring || isProcessingFrame) return
        if (!allPermissionsGranted()) {
            requestPermissionLauncher.launch(CAMERA_PERMISSIONS)
            return
        }
        if (!modelReady) {
            Toast.makeText(this, R.string.status_loading, Toast.LENGTH_SHORT).show()
            return
        }
        if (snapshotShown) {
            clearResults()
            restartCamera()
            Toast.makeText(this, R.string.waiting_for_camera, Toast.LENGTH_SHORT).show()
            return
        }
        if (videoRecorder.isRecording) stopRecording()
        val snapshot = if (cameraHelper.cameraDevice != null) captureFrame() else null
        if (snapshot == null) {
            restartCamera()
            Toast.makeText(this, R.string.waiting_for_camera, Toast.LENGTH_SHORT).show()
            return
        }
        val generation = frameGeneration
        isProcessingFrame = true
        viewBinding.TakeSnapshotButton.isEnabled = false
        viewBinding.startInferenceButton.isEnabled = false
        vp.processFrame(snapshot, annotateFrame = true) { result ->
            if (generation != frameGeneration || !activityResumed) {
                result?.frame?.recycle()
                return@processFrame
            }
            isProcessingFrame = false
            viewBinding.TakeSnapshotButton.isEnabled = true
            viewBinding.startInferenceButton.isEnabled = true
            if (result == null) {
                Toast.makeText(this, R.string.could_not_process, Toast.LENGTH_LONG).show()
                return@processFrame
            }
            snapshotShown = true
            viewBinding.processedFrameView.setImageBitmap(result.frame)
            viewBinding.processedFrameView.visibility = View.VISIBLE
            showStatus(getString(R.string.status_snapshot, result.detections.size, result.inferenceMillis))
            cameraHelper.closeCamera()
        }
    }

    // ----------------------------
    // Data collection recording (headset button B)
    // ----------------------------
    private fun startRecording() {
        if (!allPermissionsGranted()) {
            requestPermissionLauncher.launch(CAMERA_PERMISSIONS)
            return
        }
        if (!recordingPermissionsGranted()) {
            requestRecordingPermissionLauncher.launch(RECORDING_PERMISSIONS)
            return
        }
        if (snapshotShown) {
            clearResults()
            restartCamera()
        }
        if (cameraHelper.cameraDevice == null) {
            restartCamera()
            Toast.makeText(this, R.string.waiting_for_camera, Toast.LENGTH_SHORT).show()
            return
        }
        viewBinding.recordButton.isEnabled = false
        videoRecorder.start(cameraHelper) { started, message ->
            viewBinding.recordButton.isEnabled = true
            if (started) {
                viewBinding.recordButton.text = getString(R.string.stop_recording)
                viewBinding.recordButton.backgroundTintList = ContextCompat.getColorStateList(this, R.color.red)
                viewBinding.recordingIndicator.visibility = View.VISIBLE
            } else {
                showTransientStatus(getString(R.string.status_cannot_record, message ?: ""), RECORDING_FEEDBACK_MS)
            }
        }
    }

    private fun stopRecording() {
        viewBinding.recordingIndicator.visibility = View.GONE
        viewBinding.recordButton.text = getString(R.string.start_recording)
        viewBinding.recordButton.backgroundTintList = ContextCompat.getColorStateList(this, R.color.yellow)
        val saved = videoRecorder.stop(cameraHelper)
        showTransientStatus(
            if (saved != null) getString(R.string.status_saved, saved) else getString(R.string.status_recording_failed),
            RECORDING_FEEDBACK_MS
        )
    }

    // ----------------------------
    // Helpers
    // ----------------------------
    private fun clearResults() {
        if (videoRecorder.isRecording) stopRecording()
        frameGeneration++
        isInferring = false
        isProcessingFrame = false
        snapshotShown = false
        viewBinding.startInferenceButton.isEnabled = true
        viewBinding.TakeSnapshotButton.isEnabled = true
        viewBinding.startInferenceButton.text = getString(R.string.start_inference)
        viewBinding.startInferenceButton.backgroundTintList = ContextCompat.getColorStateList(this, R.color.blue)
        viewBinding.processedFrameView.visibility = View.GONE
        viewBinding.processedFrameView.setImageDrawable(null)
        showStatus(getString(if (modelReady) R.string.status_ready else R.string.status_loading))
    }

    private fun restartCamera() {
        try {
            cameraHelper.closeCamera()
            cameraHelper.stopBackgroundThread()
        } catch (_: Throwable) { /* ignore */ }

        cameraHelper.startBackgroundThread()

        if (allPermissionsGranted()) {
            if (viewBinding.viewFinder.isAvailable) {
                cameraHelper.openCamera()
            } else {
                viewBinding.viewFinder.surfaceTextureListener = textureListener
            }
        } else {
            requestPermissionLauncher.launch(CAMERA_PERMISSIONS)
        }
    }

    private fun showStatus(message: String) {
        statusMessage = message
        handler.removeCallbacks(restoreStatus)
        viewBinding.statusText.text = message
    }

    private fun showTransientStatus(message: String, durationMs: Long) {
        handler.removeCallbacks(restoreStatus)
        viewBinding.statusText.text = message
        handler.postDelayed(restoreStatus, durationMs)
    }

    private fun showGuideDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.guide_title)
            .setMessage(R.string.guide_text)
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
            Toast.makeText(this, url, Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() {
        super.onResume()
        activityResumed = true
        cameraHelper.startBackgroundThread()
        applyDetectionSettings()
        if (allPermissionsGranted()) {
            if (viewBinding.viewFinder.isAvailable && !snapshotShown) cameraHelper.openCamera()
        } else if (!permissionRequested) {
            permissionRequested = true
            requestPermissionLauncher.launch(CAMERA_PERMISSIONS)
        }
    }

    override fun onPause() {
        activityResumed = false
        clearResults()
        cameraHelper.closeCamera()
        cameraHelper.stopBackgroundThread()
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        sharedPreferences.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        videoProcessor?.close()
        videoProcessor = null
        super.onDestroy()
    }

    private fun allPermissionsGranted(): Boolean = CAMERA_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun recordingPermissionsGranted(): Boolean = RECORDING_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }
}
