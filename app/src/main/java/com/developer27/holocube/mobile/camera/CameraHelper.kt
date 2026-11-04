package com.developer27.holocube.mobile.camera

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.MotionEvent
import android.view.Surface
import android.widget.Toast
import com.developer27.holocube.mobile.MainActivity
import com.developer27.holocube.mobile.databinding.ActivityMainBinding
import kotlin.math.abs
import kotlin.math.max

/**
 * CameraHelper is responsible for:
 *  - Opening & closing the camera
 *  - Switching front/back (persisted in SharedPreferences)
 *  - Creating a preview session, or a preview + MediaRecorder session for data collection
 *  - Handling zoom and the preview transform (centre crop, no stretching)
 *  - Starting a background thread for camera operations
 *
 * Adapted from LiFind's CameraHelper. HoloCube keeps automatic exposure because it
 * detects everyday objects rather than rolling-shutter LED patterns.
 */
class CameraHelper(
    private val activity: MainActivity,
    private val viewBinding: ActivityMainBinding,
    private val sharedPreferences: SharedPreferences
) {
    companion object {
        private const val TAG = "CameraHelper"

        // Persist camera facing in prefs: "front" | "back"
        private const val PREF_CAMERA_FACING = "camera_facing"
        private const val FACING_FRONT = "front"
        private const val FACING_BACK = "back"

        private const val TARGET_WIDTH = 1280
        private const val TARGET_HEIGHT = 720
    }

    // The Android Camera2 API
    val cameraManager: CameraManager by lazy {
        activity.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    }

    // Active camera device + capture session
    var cameraDevice: CameraDevice? = null
    var cameraCaptureSession: CameraCaptureSession? = null

    // Capture builder for preview (and record)
    var captureRequestBuilder: CaptureRequest.Builder? = null

    // Preview + video sizes
    var previewSize: Size? = null
    var videoSize: Size? = null

    // Sensor area for zoom, and the sensor's mounting orientation for recordings
    var sensorArraySize: Rect? = null
    var sensorOrientation: Int = 90
        private set

    // Whether we are using the front camera (loaded from prefs)
    var isFrontCamera: Boolean = false
        private set

    // Thread for camera operations
    private var backgroundThread: HandlerThread? = null
    var backgroundHandler: Handler? = null
        private set

    // Zoom control
    private var zoomLevel = 1.0f
    private var maxZoom = 1.0f
    private val callbackHandler = Handler(activity.mainLooper)
    private var cameraGeneration = 0
    private var openingCamera = false
    private var previewSurface: Surface? = null

    // Data-collection recording: an extra MediaRecorder target in the session
    private var recordingSurface: Surface? = null
    private var sessionConfiguredCallback: ((Boolean) -> Unit)? = null
    val isRecordingSessionActive: Boolean get() = recordingSurface != null

    init {
        loadFacingFromPrefs()
    }

    private fun loadFacingFromPrefs() {
        val facing = sharedPreferences.getString(PREF_CAMERA_FACING, FACING_BACK) ?: FACING_BACK
        isFrontCamera = (facing == FACING_FRONT)
    }

    private fun saveFacingToPrefs(front: Boolean) {
        sharedPreferences.edit()
            .putString(PREF_CAMERA_FACING, if (front) FACING_FRONT else FACING_BACK)
            .apply()
    }

    /**
     * Callback for camera device events
     */
    private fun stateCallback(generation: Int) = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            if (generation != cameraGeneration) {
                camera.close()
                return
            }
            openingCamera = false
            cameraDevice = camera
            createCameraPreview()
        }

        override fun onDisconnected(camera: CameraDevice) {
            camera.close()
            if (generation == cameraGeneration) closeCamera()
        }

        override fun onError(camera: CameraDevice, error: Int) {
            camera.close()
            if (generation != cameraGeneration) return
            closeCamera()
            Toast.makeText(activity, "Camera unavailable. Tap C to retry.", Toast.LENGTH_LONG).show()
        }
    }

    // ------------------------------------------------------------------------
    // Background Thread Setup
    // ------------------------------------------------------------------------
    fun startBackgroundThread() {
        if (backgroundThread != null) return
        backgroundThread = HandlerThread("CameraBackground").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }
    }

    // ------------------------------------------------------------------------
    // Front/Back camera option
    // ------------------------------------------------------------------------
    /**
     * Call this from UI (button) to toggle front/back.
     * Persisted to SharedPreferences and restarts camera safely.
     */
    @SuppressLint("MissingPermission")
    fun toggleCameraFacing() {
        isFrontCamera = !isFrontCamera
        saveFacingToPrefs(isFrontCamera)
        restartCamera()
    }

    @SuppressLint("MissingPermission")
    private fun restartCamera() {
        // reset zoom (optional safety when switching cameras)
        zoomLevel = 1.0f

        // Close then reopen with new facing
        closeCamera()
        openCamera()
    }

    // ------------------------------------------------------------------------
    // Open/Close Camera
    // ------------------------------------------------------------------------
    @SuppressLint("MissingPermission")
    fun openCamera() {
        if (openingCamera || cameraDevice != null || !viewBinding.viewFinder.isAvailable) return
        try {
            // Decide which camera (front/back)
            val cameraId = getCameraId()
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)

            // Grab the full sensor area for zoom
            sensorArraySize = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            maxZoom = (characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f).coerceAtLeast(1f)
            zoomLevel = zoomLevel.coerceIn(1f, maxZoom)

            // Possible output sizes
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?: return

            // Choose your preview/video sizes
            previewSize = chooseOptimalSize(map.getOutputSizes(SurfaceTexture::class.java))
            videoSize = chooseOptimalSize(map.getOutputSizes(MediaRecorder::class.java))
            configureTransform(viewBinding.viewFinder.width, viewBinding.viewFinder.height)

            // Now open the selected camera
            openingCamera = true
            cameraManager.openCamera(cameraId, stateCallback(cameraGeneration), callbackHandler)
        } catch (e: Exception) {
            openingCamera = false
            Log.e(TAG, "Could not open camera", e)
            Toast.makeText(activity, "Camera unavailable. Check camera permission and retry.", Toast.LENGTH_SHORT).show()
        }
    }

    fun closeCamera() {
        cameraGeneration++
        openingCamera = false
        cameraCaptureSession?.close()
        cameraCaptureSession = null
        cameraDevice?.close()
        cameraDevice = null
        captureRequestBuilder = null
        previewSurface?.release()
        previewSurface = null
        recordingSurface = null
        notifySessionConfigured(false)
    }

    // ------------------------------------------------------------------------
    // Create Preview (optionally with the MediaRecorder surface)
    // ------------------------------------------------------------------------
    fun createCameraPreview() {
        try {
            val texture = viewBinding.viewFinder.surfaceTexture ?: return
            // Match the texture view size to the chosen preview size
            previewSize?.let { texture.setDefaultBufferSize(it.width, it.height) }

            val camera = cameraDevice ?: return
            val generation = cameraGeneration
            previewSurface?.release()
            val previewSurface = Surface(texture).also { this.previewSurface = it }
            val recordSurface = recordingSurface

            // Build a preview (or record) request
            val template = if (recordSurface != null) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW
            val builder = camera.createCaptureRequest(template)
            captureRequestBuilder = builder
            // Add the preview surface (and recorder surface) as targets
            builder.addTarget(previewSurface)
            recordSurface?.let { builder.addTarget(it) }

            applyAutoExposure()
            applyZoom()

            // ----------------------------------------------------------------
            // Force color correction to avoid a colour tint on some devices
            // ----------------------------------------------------------------
            builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
            builder.set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_HIGH_QUALITY)

            // Now create the capture session
            @Suppress("DEPRECATION")
            camera.createCaptureSession(
                listOfNotNull(previewSurface, recordSurface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (generation != cameraGeneration || cameraDevice !== camera) {
                            session.close()
                            notifySessionConfigured(false)
                            return
                        }
                        // Save the session
                        cameraCaptureSession = session
                        updatePreview() // Start the preview
                        notifySessionConfigured(true)
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        session.close()
                        if (generation != cameraGeneration) return
                        notifySessionConfigured(false)
                        Toast.makeText(activity, "Preview config failed.", Toast.LENGTH_SHORT).show()
                    }
                },
                callbackHandler
            )
        } catch (e: CameraAccessException) {
            e.printStackTrace()
            notifySessionConfigured(false)
        } catch (e: IllegalStateException) {
            Log.e(TAG, "Camera session could not be created", e)
            notifySessionConfigured(false)
        }
    }

    private fun notifySessionConfigured(configured: Boolean) {
        val callback = sessionConfiguredCallback ?: return
        sessionConfiguredCallback = null
        callback(configured)
    }

    /**
     * Rebuilds the capture session with a MediaRecorder surface.
     * [onConfigured] runs on the main thread once the camera streams to both targets.
     */
    fun startRecordingSession(surface: Surface, onConfigured: (Boolean) -> Unit) {
        if (cameraDevice == null) {
            onConfigured(false)
            return
        }
        notifySessionConfigured(false)
        recordingSurface = surface
        sessionConfiguredCallback = onConfigured
        cameraCaptureSession?.close()
        cameraCaptureSession = null
        createCameraPreview()
    }

    /** Returns to a preview-only session after a recording stops. */
    fun stopRecordingSession() {
        if (recordingSurface == null) return
        recordingSurface = null
        notifySessionConfigured(false)
        cameraCaptureSession?.close()
        cameraCaptureSession = null
        if (cameraDevice != null) createCameraPreview()
    }

    /**
     * Update the camera preview with latest builder settings
     */
    fun updatePreview() {
        if (cameraDevice == null || captureRequestBuilder == null) return
        try {
            cameraCaptureSession?.setRepeatingRequest(
                captureRequestBuilder!!.build(),
                null,
                callbackHandler
            )
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Preview request skipped: ${e.message}")
        }
    }

    // ------------------------------------------------------------------------
    // Camera Selection (Front/Back) with robust fallback
    // ------------------------------------------------------------------------
    fun getCameraId(): String {
        var backId: String? = null
        var frontId: String? = null

        for (id in cameraManager.cameraIdList) {
            val facing = cameraManager
                .getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING)

            if (facing == CameraCharacteristics.LENS_FACING_BACK && backId == null) {
                backId = id
            }
            if (facing == CameraCharacteristics.LENS_FACING_FRONT && frontId == null) {
                frontId = id
            }
        }

        return if (isFrontCamera) {
            frontId ?: backId ?: cameraManager.cameraIdList.firstOrNull() ?: error("No camera available")
        } else {
            backId ?: frontId ?: cameraManager.cameraIdList.firstOrNull() ?: error("No camera available")
        }
    }

    private fun chooseOptimalSize(choices: Array<Size>): Size {
        // Try to find 1280x720 specifically
        val found720p = choices.find { it.width == TARGET_WIDTH && it.height == TARGET_HEIGHT }
        if (found720p != null) {
            return found720p
        }
        // fallback to the size closest to 720p
        return choices.minByOrNull { abs(it.width * it.height - TARGET_WIDTH * TARGET_HEIGHT) }
            ?: error("No supported preview sizes")
    }

    // ------------------------------------------------------------------------
    // Exposure
    // ------------------------------------------------------------------------
    private fun applyAutoExposure() {
        captureRequestBuilder?.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        captureRequestBuilder?.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
    }

    // ------------------------------------------------------------------------
    // Preview transform
    // ------------------------------------------------------------------------
    /** The display rotation as a Surface.ROTATION_* constant. */
    fun displayRotation(): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        activity.display?.rotation ?: Surface.ROTATION_0
    } else {
        @Suppress("DEPRECATION")
        activity.windowManager.defaultDisplay.rotation
    }

    /** The display rotation in degrees. */
    fun displayRotationDegrees(): Int = when (displayRotation()) {
        Surface.ROTATION_90 -> 90
        Surface.ROTATION_180 -> 180
        Surface.ROTATION_270 -> 270
        else -> 0
    }

    /**
     * Scales the preview so it fills the TextureView without stretching (centre crop),
     * so the detections drawn over it keep the camera's real proportions.
     */
    fun configureTransform(viewWidth: Int, viewHeight: Int) {
        val preview = previewSize ?: return
        if (viewWidth == 0 || viewHeight == 0) return
        val rotation = displayRotation()
        val matrix = Matrix()
        val centerX = viewWidth / 2f
        val centerY = viewHeight / 2f
        if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) {
            val viewRect = RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
            val bufferRect = RectF(0f, 0f, preview.height.toFloat(), preview.width.toFloat())
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
            val scale = max(viewHeight.toFloat() / preview.height, viewWidth.toFloat() / preview.width)
            matrix.postScale(scale, scale, centerX, centerY)
            matrix.postRotate(90f * (rotation - 2), centerX, centerY)
        } else {
            // Portrait: the system already rotates the 16:9 buffer into the view;
            // undo the stretch with a centre-crop scale.
            val bufferWidth = preview.height.toFloat()
            val bufferHeight = preview.width.toFloat()
            val fill = max(viewWidth / bufferWidth, viewHeight / bufferHeight)
            matrix.setScale(bufferWidth * fill / viewWidth, bufferHeight * fill / viewHeight, centerX, centerY)
            if (rotation == Surface.ROTATION_180) matrix.postRotate(180f, centerX, centerY)
        }
        viewBinding.viewFinder.setTransform(matrix)
    }

    // ------------------------------------------------------------------------
    // Zoom
    // ------------------------------------------------------------------------
    @SuppressLint("ClickableViewAccessibility")
    fun setupZoomControls() {
        val zoomHandler = Handler(activity.mainLooper)
        var zoomInRunnable: Runnable? = null
        var zoomOutRunnable: Runnable? = null

        // Repetitive zoom in on long-press
        viewBinding.zoomInButton.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    zoomInRunnable = object : Runnable {
                        override fun run() {
                            zoomIn()
                            zoomHandler.postDelayed(this, 50)
                        }
                    }
                    zoomHandler.post(zoomInRunnable!!)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    zoomInRunnable?.let { zoomHandler.removeCallbacks(it) }
                    true
                }
                else -> false
            }
        }

        // Repetitive zoom out on long-press
        viewBinding.zoomOutButton.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    zoomOutRunnable = object : Runnable {
                        override fun run() {
                            zoomOut()
                            zoomHandler.postDelayed(this, 50)
                        }
                    }
                    zoomHandler.post(zoomOutRunnable!!)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    zoomOutRunnable?.let { zoomHandler.removeCallbacks(it) }
                    true
                }
                else -> false
            }
        }
    }

    private fun zoomIn() {
        if (zoomLevel < maxZoom) {
            zoomLevel = (zoomLevel + 0.1f).coerceAtMost(maxZoom)
            applyZoom()
            updatePreview()
        }
    }

    private fun zoomOut() {
        if (zoomLevel > 1.0f) {
            zoomLevel = (zoomLevel - 0.1f).coerceAtLeast(1f)
            applyZoom()
            updatePreview()
        }
    }

    /**
     * Applies digital zoom by setting the SCALER_CROP_REGION
     */
    fun applyZoom() {
        val sensor = sensorArraySize ?: return
        val builder = captureRequestBuilder ?: return
        val ratio = 1 / zoomLevel
        val croppedWidth = sensor.width() * ratio
        val croppedHeight = sensor.height() * ratio

        val left = sensor.left + ((sensor.width() - croppedWidth) / 2).toInt()
        val top = sensor.top + ((sensor.height() - croppedHeight) / 2).toInt()
        val right = (left + croppedWidth).toInt()
        val bottom = (top + croppedHeight).toInt()

        builder.set(CaptureRequest.SCALER_CROP_REGION, Rect(left, top, right, bottom))
    }
}
