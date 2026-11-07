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
import android.widget.AdapterView
import android.widget.ArrayAdapter
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
 * models; only the Transmitter Detection Model exists today.
 */
class LocalFileInferenceActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLocalFileInferenceBinding
    private lateinit var sharedPreferences: SharedPreferences
    private var processor: VideoProcessor? = null
    private var model = LocalInferenceModel.DEFAULT
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

        val models = LocalInferenceModel.entries
        binding.modelSpinner.adapter =
            ArrayAdapter(this, R.layout.spinner_item_model, models.map { it.displayName }).apply {
                setDropDownViewResource(R.layout.spinner_item_model)
            }
        binding.modelSpinner.setSelection(models.indexOf(model))
        binding.modelSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                model = models[position]
                binding.modelDescription.text = model.description
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        binding.modelDescription.text = model.description

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
        // The Settings page may have changed the threshold or thread count meanwhile.
        Settings.Inference.applyPreferences(sharedPreferences)
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
        running = true
        lastUri = uri
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
                    when (model) {
                        LocalInferenceModel.TRANSMITTER ->
                            vp.processFrame(decoded.bitmap, annotateFrame = true) { result ->
                                if (result == null) showFailure(getString(R.string.local_unsupported_image))
                                else showResult(result, decoded.name, width, height)
                            }
                    }
                }
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

        val threshold = (Settings.Inference.confidenceThreshold * 100f + 0.5f).toInt()
        val detection = result.detections.firstOrNull()
        showStatus(
            if (detection == null) {
                getString(R.string.local_status_none, name, width, height, threshold, result.inferenceMillis)
            } else {
                getString(R.string.local_status_found, name, width, height, result.detections.size, result.inferenceMillis)
            }
        )
        if (detection == null) {
            binding.detailText.visibility = View.GONE
        } else {
            binding.detailText.visibility = View.VISIBLE
            binding.detailText.text = getString(
                R.string.local_status_box,
                (detection.confidence * 100f + 0.5f).toInt(),
                detection.left.toInt(), detection.top.toInt(), detection.right.toInt(), detection.bottom.toInt()
            )
        }
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
