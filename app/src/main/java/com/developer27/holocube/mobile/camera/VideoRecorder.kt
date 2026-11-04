package com.developer27.holocube.mobile.camera

import android.content.ContentValues
import android.content.Context
import android.media.MediaRecorder
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * YOLO data collection on the phone: records the camera with microphone audio to
 * Movies/HoloCube/HoloCube_yyyyMMdd_HHmmss_SSS.mp4, the same folder and naming as
 * the headset's B-button recordings.
 */
class VideoRecorder(private val context: Context) {
    companion object {
        private const val TAG = "HoloCube"
        private const val FOLDER = "HoloCube"
        const val RELATIVE_PATH = "Movies/HoloCube"
        private const val BIT_RATE = 8_000_000
        private const val FRAME_RATE = 30
    }

    var isRecording = false
        private set
    private var starting = false
    private var recorder: MediaRecorder? = null
    private var outputUri: Uri? = null
    private var outputFile: File? = null
    private var outputDescriptor: ParcelFileDescriptor? = null
    private var displayName = ""

    /**
     * Prepares an MP4 in Movies/HoloCube and switches the camera to a recording session.
     * [onStarted] runs on the main thread with success, or false and a reason.
     */
    fun start(camera: CameraHelper, onStarted: (Boolean, String?) -> Unit) {
        if (isRecording || starting) {
            onStarted(false, "Already recording")
            return
        }
        if (camera.cameraDevice == null) {
            onStarted(false, "Waiting for the camera…")
            return
        }
        val size = camera.videoSize ?: Size(1280, 720)
        val name = "HoloCube_" + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date()) + ".mp4"
        val mediaRecorder = try {
            createRecorder(camera, size, name)
        } catch (error: Exception) {
            Log.e(TAG, "HOLOCUBE_VIDEO_FAILED: could not prepare the recorder", error)
            cleanupOutput(keep = false)
            onStarted(false, error.message ?: "Could not prepare the recorder")
            return
        }

        starting = true
        camera.startRecordingSession(mediaRecorder.surface) { configured ->
            starting = false
            if (!configured) {
                release(mediaRecorder)
                cleanupOutput(keep = false)
                onStarted(false, "Camera session failed")
                return@startRecordingSession
            }
            try {
                mediaRecorder.start()
                recorder = mediaRecorder
                isRecording = true
                displayName = name
                Log.i(TAG, "HOLOCUBE_VIDEO_STARTED: $name")
                onStarted(true, null)
            } catch (error: Exception) {
                Log.e(TAG, "HOLOCUBE_VIDEO_FAILED: recorder did not start", error)
                release(mediaRecorder)
                camera.stopRecordingSession()
                cleanupOutput(keep = false)
                onStarted(false, error.message ?: "Recorder did not start")
            }
        }
    }

    /** Stops and finalizes the MP4. Returns the saved path for display, or null on failure. */
    fun stop(camera: CameraHelper): String? {
        val mediaRecorder = recorder ?: return null
        recorder = null
        isRecording = false
        Log.i(TAG, "HOLOCUBE_VIDEO_SAVING: $displayName")
        var saved = true
        try {
            mediaRecorder.stop()
        } catch (error: RuntimeException) {
            // No valid frames were written (for example, stopped immediately).
            Log.e(TAG, "HOLOCUBE_VIDEO_FAILED: no video data was saved", error)
            saved = false
        }
        release(mediaRecorder)
        camera.stopRecordingSession()
        cleanupOutput(keep = saved)
        if (!saved) return null
        val path = "$RELATIVE_PATH/$displayName"
        Log.i(TAG, "HOLOCUBE_VIDEO_SAVED: $path")
        return path
    }

    private fun createRecorder(camera: CameraHelper, size: Size, name: String): MediaRecorder {
        val mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC)
        mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        openOutput(name, mediaRecorder)
        mediaRecorder.setVideoEncodingBitRate(BIT_RATE)
        mediaRecorder.setVideoFrameRate(FRAME_RATE)
        mediaRecorder.setVideoSize(size.width, size.height)
        mediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        mediaRecorder.setOrientationHint(orientationHint(camera))
        mediaRecorder.prepare()
        return mediaRecorder
    }

    private fun openOutput(name: String, mediaRecorder: MediaRecorder) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, RELATIVE_PATH)
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = context.contentResolver.insert(collection, values)
                ?: throw IllegalStateException("Could not create the video in Movies/HoloCube")
            val descriptor = context.contentResolver.openFileDescriptor(uri, "rw")
                ?: throw IllegalStateException("Could not open the video for writing")
            outputUri = uri
            outputDescriptor = descriptor
            mediaRecorder.setOutputFile(descriptor.fileDescriptor)
        } else {
            val folder = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), FOLDER)
            if (!folder.isDirectory && !folder.mkdirs()) throw IllegalStateException("Could not create Movies/HoloCube")
            val file = File(folder, name)
            outputFile = file
            mediaRecorder.setOutputFile(file.absolutePath)
        }
    }

    private fun cleanupOutput(keep: Boolean) {
        try {
            outputDescriptor?.close()
        } catch (_: Exception) {
        }
        outputDescriptor = null
        outputUri?.let { uri ->
            if (keep) {
                val values = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
                context.contentResolver.update(uri, values, null, null)
            } else {
                context.contentResolver.delete(uri, null, null)
            }
        }
        outputFile?.let { file ->
            if (keep) MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf("video/mp4"), null)
            else file.delete()
        }
        outputUri = null
        outputFile = null
    }

    private fun release(mediaRecorder: MediaRecorder) {
        try {
            mediaRecorder.reset()
        } catch (_: Exception) {
        }
        mediaRecorder.release()
    }

    /** Rotation players apply so the recording is upright, from the sensor and display orientation. */
    private fun orientationHint(camera: CameraHelper): Int =
        (camera.sensorOrientation - camera.displayRotationDegrees() + 360) % 360
}
