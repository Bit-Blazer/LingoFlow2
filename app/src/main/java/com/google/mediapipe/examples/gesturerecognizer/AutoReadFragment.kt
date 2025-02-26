package com.google.mediapipe.examples.gesturerecognizer

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.Fragment
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class AutoReadFragment : Fragment() {

    private lateinit var viewFinder: PreviewView
    private lateinit var textOverlay: TextView
    private lateinit var recognizedTextView: TextView
    private lateinit var scrollView: NestedScrollView
    private lateinit var cameraExecutor: ExecutorService
    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.fragment_auto_read, container, false)

        viewFinder = view.findViewById(R.id.view_finder)
        textOverlay = view.findViewById(R.id.text_overlay)
        recognizedTextView = view.findViewById(R.id.recognized_text)
        scrollView = view.findViewById(R.id.scrollView)

        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        cameraExecutor = Executors.newSingleThreadExecutor()
        startCamera()

        // Hide text overlay when scrolling up
        scrollView.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            if (scrollY > 100) {
                textOverlay.visibility = View.VISIBLE
            } else {
                textOverlay.visibility = View.GONE
            }
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())

        cameraProviderFuture.addListener(
                {
                    val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()
                    val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                    val preview =
                            Preview.Builder().build().also {
                                it.setSurfaceProvider(viewFinder.surfaceProvider)
                            }

                    val imageAnalysis =
                            ImageAnalysis.Builder()
                                    .setBackpressureStrategy(
                                            ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
                                    )
                                    .build()

                    imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                        processImage(imageProxy)
                    }

                    try {
                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis)
                    } catch (exc: Exception) {
                        Log.e("AutoReadFragment", "Camera binding failed", exc)
                    }
                },
                ContextCompat.getMainExecutor(requireContext())
        )
    }

    private fun processImage(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage != null) {
            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

            textRecognizer
                    .process(image)
                    .addOnSuccessListener { visionText -> displayText(visionText) }
                    .addOnFailureListener { e -> Log.e("AutoReadFragment", "OCR failed", e) }
                    .addOnCompleteListener { imageProxy.close() }
        }
    }

    private fun displayText(visionText: Text) {
        activity?.runOnUiThread { recognizedTextView.text = visionText.text }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        cameraExecutor.shutdown()
    }
}
