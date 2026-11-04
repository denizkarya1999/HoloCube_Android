package com.developer27.holocube.mobile.videoprocessing

import android.content.Context
import android.util.Log
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.io.Closeable
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * Owns one background thread that runs the embedded Python/PyTorch runtime.
 * Kotlin port of the Quest app's PyTorchWorker.java: start Python once, load the
 * original checkpoint, run the startup reference check, then serve one image at a time.
 */
class PyTorchWorker(context: Context, threads: Int) : Closeable {
    companion object {
        const val TAG = "HoloCube"
        const val MODULE = "holocube_runtime"
    }

    private val executor: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, "HoloCube-PyTorch")
    }
    private val startup: Future<PyObject>
    @Volatile private var reportedFrame = false
    @Volatile var isClosed = false
        private set

    init {
        val app = context.applicationContext
        startup = executor.submit(Callable {
            synchronized(Python::class.java) {
                if (!Python.isStarted()) Python.start(AndroidPlatform(app))
            }
            val runtime = Python.getInstance().getModule(MODULE)
            Log.i(TAG, runtime.callAttr("initialize", threads).toString())
            Log.i(TAG, "HOLOCUBE_PT_READY: original YOLO26n checkpoint loaded on device")
            runtime
        })
    }

    /** True once the checkpoint is loaded and the reference check passed. Throws if startup failed. */
    val isReady: Boolean
        get() {
            check(!isClosed) { "PyTorch worker is closed" }
            if (!startup.isDone) return false
            get(startup) // Surface initialization errors to the UI.
            return true
        }

    /** Blocks until the runtime is ready and returns the Python module. */
    fun awaitReady(): PyObject = get(startup)

    /**
     * Runs the checkpoint on one letterboxed RGBA image and returns the
     * [1,300,6] rows flattened as [x1,y1,x2,y2,confidence,classId]...
     * Blocks the caller; VideoProcessor serializes calls with a mutex.
     */
    fun detect(rgba: ByteArray): FloatArray {
        check(!isClosed) { "PyTorch worker is closed" }
        val task = executor.submit(Callable {
            val start = System.nanoTime()
            val rows = awaitReady().callAttr("detect_rgba", rgba).toJava(FloatArray::class.java)
            if (!reportedFrame) {
                Log.i(TAG, "HOLOCUBE_PT_CAMERA_FRAME: " + rows.size / 6 +
                    " detection rows in " + (System.nanoTime() - start) / 1_000_000 + " ms")
                reportedFrame = true
            }
            rows
        })
        return get(task)
    }

    /** Changes the number of CPU threads PyTorch uses; applied before the next frame. */
    fun setThreads(threads: Int) {
        if (isClosed) return
        executor.submit { awaitReady().callAttr("set_threads", threads) }
    }

    override fun close() {
        isClosed = true
        executor.shutdown() // Finish native work without blocking the UI thread.
    }

    private fun <T> get(future: Future<T>): T = try {
        future.get()
    } catch (error: InterruptedException) {
        Thread.currentThread().interrupt()
        throw IllegalStateException("PyTorch worker interrupted", error)
    } catch (error: ExecutionException) {
        val cause = error.cause ?: error
        throw IllegalStateException("PyTorch: " + cause.message, cause)
    }
}
