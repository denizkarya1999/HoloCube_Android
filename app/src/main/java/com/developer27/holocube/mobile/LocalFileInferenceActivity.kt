package com.developer27.holocube.mobile

import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import com.developer27.holocube.mobile.databinding.ActivityLocalFileInferenceBinding
import com.developer27.holocube.mobile.videoprocessing.FrameResult
import com.developer27.holocube.mobile.videoprocessing.LocalInferenceModel
import com.developer27.holocube.mobile.videoprocessing.ModelSelection
import com.developer27.holocube.mobile.videoprocessing.OrientationHelper
import com.developer27.holocube.mobile.videoprocessing.Settings
import com.developer27.holocube.mobile.videoprocessing.VideoProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import kotlin.math.max

/**
 * Settings → In-Device Local File Inference: open an image stored on this phone and run
 * the selected model on it. The annotated image, the box, its confidence, and the
 * inference time are shown. Images opened with or shared to HoloCube land here too.
 *
 * The same [VideoProcessor] path as the live camera is used, so the result is exactly
 * what the camera screen would draw for that frame. [LocalInferenceModel] lists the
 * models: the Transmitter Detection Model and the Orientation Detection Model, each with a checkbox.
 */
class LocalFileInferenceActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLocalFileInferenceBinding
    private lateinit var sharedPreferences: SharedPreferences
    private var processor: VideoProcessor? = null
    private var modelReady = false
    private var running = false
    private var pendingUri: Uri? = null
    private var lastUri: Uri? = null
    private var shownFrame: Bitmap? = null

    /** The most recent result (its frame is the one on screen); read by the device tests. */
    @VisibleForTesting
    var lastResult: FrameResult? = null
        private set

    private val openDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) openImage(uri)
    }

    companion object {
        /** Opened images are decoded with their long edge limited to this many pixels. */
        private const val MAX_EDGE = 1920
        private const val STATE_URI = "image_uri"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLocalFileInferenceBinding.inflate(layoutInflater)
        setContentView(binding.root)
        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)

        binding.titleContainer.setOnClickListener { openUrl(getString(R.string.url_website)) }

        // Models to run: two checkboxes stored in the same preferences as Settings → Models to Run.
        binding.transmitterCheck.text = LocalInferenceModel.TRANSMITTER.displayName
        binding.transmitterDescription.text = LocalInferenceModel.TRANSMITTER.description
        binding.orientationCheck.text = LocalInferenceModel.ORIENTATION.displayName
        binding.orientationDescription.text = LocalInferenceModel.ORIENTATION.description
        showModelSelection()

        binding.openImageButton.setOnClickListener { openDocument.launch(arrayOf("image/*")) }
        binding.runAgainButton.setOnClickListener { lastUri?.let(::openImage) }
        binding.runAgainButton.isEnabled = false

        processor = VideoProcessor(this)
        showStatus(getString(R.string.local_status_loading))
        processor?.warmUp { error ->
            modelReady = error == null
            if (error != null) {
                showStatus(getString(R.string.status_detection_stopped, error))
            } else {
                showStatus(getString(R.string.local_status_ready))
                pendingUri?.let {
                    pendingUri = null
                    openImage(it)
                }
            }
        }

        val incoming = savedInstanceState?.getString(STATE_URI)?.let(Uri::parse) ?: imageUriFrom(intent)
        if (incoming != null) openImage(incoming)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        imageUriFrom(intent)?.let(::openImage)
    }

    override fun onResume() {
        super.onResume()
        // The Settings page may have changed the threshold, thread count, or models meanwhile.
        Settings.Inference.applyPreferences(sharedPreferences)
        showModelSelection()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_URI, lastUri?.toString())
    }

    override fun onDestroy() {
        processor?.close()
        processor = null
        binding.resultImage.setImageDrawable(null)
        shownFrame?.recycle()
        shownFrame = null
        super.onDestroy()
    }

    /** Decodes the image at [uri] off the main thread and runs the selected model on it. */
    fun openImage(uri: Uri) {
        if (!modelReady) {
            pendingUri = uri
            showStatus(getString(R.string.local_status_loading))
            return
        }
        if (running) return
        val vp = processor ?: return
        val models = ModelSelection.fromSettings()
        lastUri = uri
        if (models.isEmpty) {
            binding.runAgainButton.isEnabled = true
            binding.detailText.visibility = View.GONE
            showStatus(getString(R.string.local_status_no_model))
            return
        }
        running = true
        binding.openImageButton.isEnabled = false
        binding.runAgainButton.isEnabled = false
        showStatus(getString(R.string.local_status_running))
        lifecycleScope.launch {
            val decoded = withContext(Dispatchers.IO) {
                try {
                    Decoded(decodeImage(uri), displayName(uri))
                } catch (e: Exception) {
                    Failure(e.message ?: e.toString())
                }
            }
            when (decoded) {
                is Failure -> showFailure(decoded.message)
                is Decoded -> {
                    // processFrame recycles the bitmap, so note its size first.
                    val width = decoded.bitmap.width
                    val height = decoded.bitmap.height
                    vp.processFrame(decoded.bitmap, annotateFrame = true, models = models) { result ->
                        val unavailable = result?.orientationUnavailable
                        when {
                            result == null -> showFailure(getString(R.string.local_unsupported_image))
                            unavailable != null -> {
                                result.frame.recycle()
                                showFailure(getString(R.string.local_orientation_unavailable, unavailable))
                            }
                            else -> showResult(result, decoded.name, width, height)
                        }
                    }
                }
            }
        }
    }

    /** Sets the two checkboxes from the stored selection without firing their listeners. */
    private fun showModelSelection() {
        val checks = listOf(
            binding.transmitterCheck to Settings.Inference.KEY_MODEL_TRANSMITTER,
            binding.orientationCheck to Settings.Inference.KEY_MODEL_ORIENTATION
        )
        val selection = ModelSelection.fromSettings()
        for ((check, key) in checks) {
            check.setOnCheckedChangeListener(null)
            check.isChecked = if (key == Settings.Inference.KEY_MODEL_TRANSMITTER) selection.transmitter else selection.orientation
            check.setOnCheckedChangeListener { _, checked ->
                sharedPreferences.edit().putBoolean(key, checked).apply()
                Settings.Inference.applyPreferences(sharedPreferences)
                // Show the current image with the new choice straight away.
                lastUri?.let(::openImage)
            }
        }
    }

    private fun showResult(result: FrameResult, name: String, width: Int, height: Int) {
        running = false
        binding.openImageButton.isEnabled = true
        binding.runAgainButton.isEnabled = true
        lastResult = result
        val previous = shownFrame
        shownFrame = result.frame
        binding.resultImage.setImageBitmap(result.frame)
        previous?.recycle()

        // One status line: what each selected model found, then the total time.
        val threshold = (Settings.Inference.confidenceThreshold * 100f + 0.5f).toInt()
        val models = result.models
        val detection = result.detections.firstOrNull()
        val orientation = result.orientations.firstOrNull()
        val parts = ArrayList<String>()
        val details = ArrayList<String>()
        if (models.transmitter) {
            if (detection == null) {
                parts += getString(R.string.local_part_transmitter_none, threshold)
            } else {
                parts += getString(R.string.local_part_transmitter_found, result.detections.size)
                details += getString(
                    R.string.local_status_box,
                    (detection.confidence * 100f + 0.5f).toInt(),
                    detection.left.toInt(), detection.top.toInt(), detection.right.toInt(), detection.bottom.toInt()
                )
            }
        }
        if (models.orientation) {
            when {
                // With both models selected, orientation waits for a transmitter box.
                !result.orientationRan -> parts += getString(R.string.local_part_orientation_skipped)
                orientation == null -> parts += getString(
                    if (models.orientationNeedsTransmitter) R.string.local_part_orientation_none_in_box
                    else R.string.local_part_orientation_none,
                    threshold
                )
                else -> {
                    val direction = OrientationHelper.classNameForId(orientation.classId)
                    parts += getString(R.string.local_part_orientation_found, direction)
                    details += getString(
                        R.string.local_status_triangle,
                        direction, (orientation.confidence * 100f + 0.5f).toInt(),
                        orientation.ax.toInt(), orientation.ay.toInt(),
                        orientation.bx.toInt(), orientation.by.toInt(),
                        orientation.cx.toInt(), orientation.cy.toInt()
                    )
                }
            }
        }
        showStatus(
            getString(
                R.string.local_status_summary, name, width, height, parts.joinToString(" · "),
                result.inferenceMillis + result.orientationMillis
            )
        )
        binding.detailText.visibility = if (details.isEmpty()) View.GONE else View.VISIBLE
        binding.detailText.text = details.joinToString("\n")
    }

    private fun showFailure(message: String) {
        running = false
        binding.openImageButton.isEnabled = true
        binding.runAgainButton.isEnabled = lastUri != null
        binding.detailText.visibility = View.GONE
        showStatus(getString(R.string.local_status_error, message))
    }

    private fun showStatus(message: String) {
        binding.statusText.text = message
    }

    private fun imageUriFrom(intent: Intent?): Uri? = when (intent?.action) {
        Intent.ACTION_VIEW -> intent.data
        Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
        else -> null
    }

    private sealed interface Decode
    private class Decoded(val bitmap: Bitmap, val name: String) : Decode
    private class Failure(val message: String) : Decode

    /** A software ARGB_8888 bitmap (what OpenCV needs), downscaled so the long edge is at most [MAX_EDGE]. */
    private fun decodeImage(uri: Uri): Bitmap {
        val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // ImageDecoder also applies the EXIF orientation of phone photos.
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri)) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val longest = max(info.size.width, info.size.height)
                if (longest > MAX_EDGE) decoder.setTargetSampleSize(longest / MAX_EDGE + 1)
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            openStream(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / sample > MAX_EDGE) sample *= 2
            val options = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            openStream(uri).use { BitmapFactory.decodeStream(it, null, options) }
                ?: throw IOException(getString(R.string.local_unsupported_image))
        }
        if (bitmap.config == Bitmap.Config.ARGB_8888) return bitmap
        return bitmap.copy(Bitmap.Config.ARGB_8888, false).also { bitmap.recycle() }
    }

    private fun openStream(uri: Uri): InputStream =
        contentResolver.openInputStream(uri) ?: throw IOException(getString(R.string.local_unsupported_image))

    private fun displayName(uri: Uri): String {
        if (uri.scheme == "content") {
            try {
                contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val name = cursor.getString(0)
                        if (!name.isNullOrBlank()) return name
                    }
                }
            } catch (_: Exception) {
                // Fall through to the path segment.
            }
        }
        return uri.lastPathSegment ?: uri.toString()
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
            Toast.makeText(this, url, Toast.LENGTH_LONG).show()
        }
    }
}
