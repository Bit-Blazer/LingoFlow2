package com.parakurom.lingoflow

import android.annotation.SuppressLint
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Toast
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.navigation.Navigation
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class CameraFragment : Fragment(), GestureRecognizerHelper.GestureRecognizerListener {

    companion object {
        private const val TAG = "Hand gesture recognizer"
    }

    private lateinit var gestureRecognizerHelper: GestureRecognizerHelper
    private var defaultNumResults = 1
    private val gestureRecognizerResultAdapter: GestureRecognizerResultsAdapter by lazy {
        GestureRecognizerResultsAdapter().apply { updateAdapterSize(defaultNumResults) }
    }
    private var preview: Preview? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraFacing = CameraSelector.LENS_FACING_BACK

    // OCR variables
    private lateinit var textRecognizer: TextRecognizer
    private var isOcrRunning = false
    private var lastGestureDetectionTime = 0L
    private val GESTURE_COOLDOWN_MS = 1000 // 1 second cooldown between gesture detections

    // ROI variables
    private lateinit var roiView: RegionOfInterestView
    private var useRoi = false // Flag to determine if we should use ROI or process the entire image

    /** Blocking ML operations are performed using this executor */
    private lateinit var backgroundExecutor: ExecutorService
    private lateinit var recyclerView: RecyclerView
    private lateinit var viewFinder: PreviewView
    private lateinit var overlay: OverlayView
    private lateinit var toggleRoiButton: Button

    override fun onResume() {
        super.onResume()
        // Make sure that all permissions are still present, since the
        // user could have removed them while the app was in paused state.
        if (!PermissionsFragment.hasPermissions(requireContext())) {
            Navigation.findNavController(requireActivity(), R.id.fragment_container)
                .navigate(R.id.action_camera_to_permissions)
        }

        // Start the GestureRecognizerHelper again when users come back
        // to the foreground.
        backgroundExecutor.execute {
            if (gestureRecognizerHelper.isClosed()) {
                gestureRecognizerHelper.setupGestureRecognizer()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        if (this::gestureRecognizerHelper.isInitialized) {
            // Close the Gesture Recognizer helper and release resources
            backgroundExecutor.execute { gestureRecognizerHelper.clearGestureRecognizer() }
        }

        // Stop OCR if it's running when fragment is paused
        if (isOcrRunning) {
            stopOcrProcess()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()

        // Shut down our background executor
        backgroundExecutor.shutdown()
        backgroundExecutor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS)

        // Close text recognizer
        if (::textRecognizer.isInitialized) {
            textRecognizer.close()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.fragment_point_to_read, container, false)
        recyclerView = view.findViewById(R.id.recyclerview_results)
        viewFinder = view.findViewById(R.id.view_finder)
        overlay = view.findViewById(R.id.overlay)
        roiView = view.findViewById(R.id.roi_view)
        toggleRoiButton = view.findViewById(R.id.toggle_roi_button)
        return view
    }

    @SuppressLint("MissingPermission")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = gestureRecognizerResultAdapter

        // Initialize our background executor
        backgroundExecutor = Executors.newSingleThreadExecutor()

        // Initialize the TextRecognizer
        textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

        // Wait for the views to be properly laid out
        viewFinder.post {
            // Set up the camera and its use cases
            setUpCamera()

            // Initialize ROI view after camera is set up
            roiView.setInitialRoi(viewFinder.width, viewFinder.height)
            roiView.visibility = View.GONE // Initially hide ROI selector
        }

        // Create the Hand Gesture Recognition Helper that will handle the inference
        backgroundExecutor.execute {
            gestureRecognizerHelper =
                GestureRecognizerHelper(
                    context = requireContext(),
                    gestureRecognizerListener = this
                )
        }

        // Set up toggle ROI button
        toggleRoiButton.setOnClickListener {
            toggleRoi()
        }
    }

    // Toggle ROI selection mode
    private fun toggleRoi() {
        useRoi = !useRoi

        if (useRoi) {
            roiView.visibility = View.VISIBLE
            toggleRoiButton.text = "Disable ROI"
            Toast.makeText(requireContext(), "ROI mode enabled. Select an area.", Toast.LENGTH_SHORT).show()
        } else {
            roiView.visibility = View.GONE
            toggleRoiButton.text = "Enable ROI"
            Toast.makeText(requireContext(), "Processing full image", Toast.LENGTH_SHORT).show()
        }
    }

    // Initialize CameraX, and prepare to bind the camera use cases
    private fun setUpCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener(
            {
                // CameraProvider
                cameraProvider = cameraProviderFuture.get()

                // Build and bind the camera use cases
                bindCameraUseCases()
            },
            ContextCompat.getMainExecutor(requireContext())
        )
    }

    // Declare and bind preview, capture and analysis use cases
    @SuppressLint("UnsafeOptInUsageError")
    private fun bindCameraUseCases() {

        // CameraProvider
        val cameraProvider =
            cameraProvider ?: throw IllegalStateException("Camera initialization failed.")

        val cameraSelector = CameraSelector.Builder().requireLensFacing(cameraFacing).build()

        // Preview. Only using the 4:3 ratio because this is the closest to our models
        preview =
            Preview.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .setTargetRotation(viewFinder.display.rotation)
                .build()

        // ImageAnalysis. Using RGBA 8888 to match how our models work
        imageAnalyzer =
            ImageAnalysis.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .setTargetRotation(viewFinder.display.rotation)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
                // The analyzer can then be assigned to the instance
                .also {
                    it.setAnalyzer(backgroundExecutor) { image ->
                        recognizeHand(image)
                    }
                }


        val ocrAnalyzer = ImageAnalysis.Builder()
            // configuration...
            .build()
            .also {
                it.setAnalyzer(backgroundExecutor) { image ->
                    if (isOcrRunning) {
                        processTextRecognition(image)
                    } else {
                        image.close()
                    }
                }
            }

        // Must unbind the use-cases before rebinding them
        cameraProvider.unbindAll()

        try {
            // A variable number of use-cases can be passed here -
            // camera provides access to CameraControl & CameraInfo
            camera =  cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalyzer, ocrAnalyzer)

            // Attach the viewfinder's surface provider to preview use case
            preview?.setSurfaceProvider(viewFinder.surfaceProvider)
        } catch (exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
        }
    }

    private fun recognizeHand(imageProxy: ImageProxy) {
        gestureRecognizerHelper.recognizeLiveStream(
            imageProxy = imageProxy,
        )
    }

    // Process OCR on the image
    private fun processTextRecognition(imageProxy: ImageProxy) {
        // Get the InputImage from ImageProxy
        val mediaImage = imageProxy.image
        if (mediaImage != null) {
            val inputImage = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

            // Apply ROI if enabled
            if (useRoi && ::roiView.isInitialized) {
                // Get ROI in image coordinates
                val roi = roiView.calculateROIRect(
                    inputImage.width,
                    inputImage.height
                )

                // Process only the region of interest
                processRoiTextRecognition(inputImage, roi, imageProxy)
            } else {
                // Process the whole image
                processFullImageTextRecognition(inputImage, imageProxy)
            }
        } else {
            imageProxy.close()
        }
    }

    // Process text recognition on the entire image
    private fun processFullImageTextRecognition(inputImage: InputImage, imageProxy: ImageProxy) {
        textRecognizer.process(inputImage)
            .addOnSuccessListener { text ->
                displayRecognizedText(text)
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Text recognition failed: ${e.message}", e)
            }
            .addOnCompleteListener {
                // Important to close the image to avoid memory leaks
                imageProxy.close()
            }
    }

    // Process text recognition on just the ROI
    private fun processRoiTextRecognition(inputImage: InputImage, roi: Rect, imageProxy: ImageProxy) {
        try {
            // Create a cropped image using the ROI coordinates
            val croppedImage = InputImage.fromBitmap(
                android.graphics.Bitmap.createBitmap(
                    inputImage.bitmapInternal!!,
                    roi.left,
                    roi.top,
                    roi.width(),
                    roi.height()
                ),
                0
            )

            // Process the cropped image
            textRecognizer.process(croppedImage)
                .addOnSuccessListener { text ->
                    displayRecognizedText(text)
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "ROI text recognition failed: ${e.message}", e)
                }
                .addOnCompleteListener {
                    // Important to close the image to avoid memory leaks
                    imageProxy.close()
                }
        } catch (e: Exception) {
            Log.e(TAG, "Error cropping image for ROI: ${e.message}", e)
            imageProxy.close()
        }
    }

    // Display the recognized text
    private fun displayRecognizedText(text: Text) {
        activity?.runOnUiThread {
            if (text.text.isNotEmpty()) {
                // Update UI with recognized text
                Log.d(TAG, "Recognized text: ${text.text}")

                // Show a toast with the recognized text (for debugging)
                Toast.makeText(requireContext(), "Text: ${text.text}", Toast.LENGTH_SHORT).show()

                // TODO: Implement a proper UI for displaying the recognized text
                // For example:
                // 1. Add a TextView to your layout
                // 2. Show a dialog with the recognized text
                // 3. Pass the text to another fragment for further processing
            }
        }
    }

    // Start OCR processing
    private fun startOcrProcess() {
        if (!isOcrRunning) {
            isOcrRunning = true
            Toast.makeText(requireContext(), "OCR Started", Toast.LENGTH_SHORT).show()
            Log.d(TAG, "OCR process started")
        }
    }

    // Stop OCR processing
    private fun stopOcrProcess() {
        if (isOcrRunning) {
            isOcrRunning = false
            Toast.makeText(requireContext(), "OCR Stopped", Toast.LENGTH_SHORT).show()
            Log.d(TAG, "OCR process stopped")
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        imageAnalyzer?.targetRotation = viewFinder.display.rotation
    }

    // Update UI after a hand gesture has been recognized. Implements throttling
    // to prevent multiple triggers in quick succession.
    override fun onResults(resultBundle: GestureRecognizerHelper.ResultBundle) {
        activity?.runOnUiThread {
            if (::overlay.isInitialized) {
                // Show result of recognized gesture
                val gestureCategories = resultBundle.results.first().gestures()
                gestureRecognizerResultAdapter.updateResults(
                    if (gestureCategories.isNotEmpty()) gestureCategories.first()
                    else emptyList()
                )

                // Check for specific gestures with throttling to prevent multiple triggers
                val currentTime = System.currentTimeMillis()
                if (gestureCategories.isNotEmpty() &&
                    gestureCategories.first().isNotEmpty() &&
                    currentTime - lastGestureDetectionTime > GESTURE_COOLDOWN_MS) {

                    val topGesture = gestureCategories.first().first().categoryName()

                    when (topGesture) {
                        "like" -> {
                            lastGestureDetectionTime = currentTime
                            startOcrProcess()
                        }
                        "stop_inverted" -> {
                            lastGestureDetectionTime = currentTime
                            stopOcrProcess()
                        }
                        // Add new gesture for toggling ROI
                        "pointing_up" -> {
                            lastGestureDetectionTime = currentTime
                            toggleRoi()
                        }
                    }
                }

                // Pass necessary information to OverlayView for drawing on the canvas
                overlay.setResults(
                    resultBundle.results.first(),
                    resultBundle.inputImageHeight,
                    resultBundle.inputImageWidth,
                )

                // Force a redraw
                overlay.invalidate()
            }
        }
    }

    override fun onError(error: String) {
        activity?.runOnUiThread {
            Toast.makeText(requireContext(), error, Toast.LENGTH_SHORT).show()
            gestureRecognizerResultAdapter.updateResults(emptyList())
        }
    }
}