package com.google.mediapipe.examples.gesturerecognizer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizer
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizerResult

class GestureRecognizerHelper(
        val context: Context,
        val gestureRecognizerListener: GestureRecognizerListener? = null
) {

    // For this example this needs to be a var so it can be reset on changes. If the
    // GestureRecognizer will not change, a lazy val would be preferable.
    private var gestureRecognizer: GestureRecognizer? = null

    init {
        setupGestureRecognizer()
    }

    fun clearGestureRecognizer() {
        gestureRecognizer?.close()
        gestureRecognizer = null
    }

    // Initialize the gesture recognizer using current settings on the thread that is using it. CPU
    // can be used with recognizers that are created on the main thread and used on a background
    // thread, but the GPU delegate needs to be used on the thread that initialized the recognizer
    fun setupGestureRecognizer() {
        // Set general recognition options, including number of used threads
        val baseOptionBuilder = BaseOptions.builder()

        // Use the specified hardware for running the model. Default to CPU
        baseOptionBuilder.setDelegate(Delegate.CPU)

        baseOptionBuilder.setModelAssetPath("gesture_recognizer.task")

        try {
            val baseOptions = baseOptionBuilder.build()
            val optionsBuilder =
                    GestureRecognizer.GestureRecognizerOptions.builder()
                            .setBaseOptions(baseOptions)
                            .setRunningMode(RunningMode.LIVE_STREAM)

            optionsBuilder
                    .setResultListener(this::returnLivestreamResult)
                    .setErrorListener(this::returnLivestreamError)

            val options = optionsBuilder.build()
            gestureRecognizer = GestureRecognizer.createFromOptions(context, options)
        } catch (e: IllegalStateException) {
            gestureRecognizerListener?.onError(
                    "Gesture recognizer failed to initialize. See error logs for details"
            )
            Log.e(TAG, "MP Task Vision failed to load the task with error: " + e.message)
        } catch (e: RuntimeException) {
            gestureRecognizerListener?.onError(
                    "Gesture recognizer failed to initialize. See error logs for details"
            )
            Log.e(TAG, "MP Task Vision failed to load the task with error: " + e.message)
        }
    }

    // Convert the ImageProxy to MP Image and feed it to GestureRecognizer.
    fun recognizeLiveStream(
            imageProxy: ImageProxy,
    ) {
        val frameTime = SystemClock.uptimeMillis()

        // Copy out RGB bits from the frame to a bitmap buffer
        val bitmapBuffer =
                Bitmap.createBitmap(imageProxy.width, imageProxy.height, Bitmap.Config.ARGB_8888)
        imageProxy.use { bitmapBuffer.copyPixelsFromBuffer(imageProxy.planes[0].buffer) }
        imageProxy.close()

        val matrix =
                Matrix().apply {
                    // Rotate the frame received from the camera to be in the same direction as
                    // it'll be shown
                    postRotate(imageProxy.imageInfo.rotationDegrees.toFloat())

                    // flip image since we only support front camera
//                    postScale(-1f, 1f, imageProxy.width.toFloat(), imageProxy.height.toFloat())
                }

        // Rotate bitmap to match what our model expects
        val rotatedBitmap =
                Bitmap.createBitmap(
                        bitmapBuffer,
                        0,
                        0,
                        bitmapBuffer.width,
                        bitmapBuffer.height,
                        matrix,
                        true
                )

        // Convert the input Bitmap object to an MPImage object to run inference
        val mpImage = BitmapImageBuilder(rotatedBitmap).build()

        recognizeAsync(mpImage, frameTime)
    }

    // Run hand gesture recognition using MediaPipe Gesture Recognition API
    @VisibleForTesting
    fun recognizeAsync(mpImage: MPImage, frameTime: Long) {
        // As we're using running mode LIVE_STREAM, the recognition result will be returned in
        // returnLivestreamResult function
        gestureRecognizer?.recognizeAsync(mpImage, frameTime)
    }

    // Return running status of the recognizer helper
    fun isClosed(): Boolean {
        return gestureRecognizer == null
    }

    // Return the recognition result to the GestureRecognizerHelper's caller
    private fun returnLivestreamResult(result: GestureRecognizerResult, input: MPImage) {
        gestureRecognizerListener?.onResults(
                ResultBundle(listOf(result), input.height, input.width)
        )
    }

    // Return errors thrown during recognition to this GestureRecognizerHelper's caller
    private fun returnLivestreamError(error: RuntimeException) {
        gestureRecognizerListener?.onError(error.message ?: "An unknown error has occurred")
    }

    companion object {
        val TAG = "GestureRecognizerHelper ${this.hashCode()}"
    }

    data class ResultBundle(
            val results: List<GestureRecognizerResult>,
            val inputImageHeight: Int,
            val inputImageWidth: Int,
    )

    interface GestureRecognizerListener {
        fun onError(error: String)
        fun onResults(resultBundle: ResultBundle)
    }
}
