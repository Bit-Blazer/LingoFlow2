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
import android.widget.TextView
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

class PointtoReadFragment : Fragment(), GestureRecognizerHelper.GestureRecognizerListener {

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
    private var ocrAnalyzer: ImageAnalysis? = null
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
    private lateinit var recognizedTextView: TextView

    // Add these variables for finger tracking
    private var middleFingerPosition: Pair<Float, Float>? = null
    private val FINGER_REGION_WIDTH = 300 // Width of OCR region in pixels
    private val FINGER_REGION_HEIGHT = 150 // Height of OCR region in pixels
    private val VERTICAL_OFFSET = 150 // How far above the finger to place the region

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
        recognizedTextView = view.findViewById(R.id.recognized_text)
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
            Toast.makeText(requireContext(), "Processing finger region", Toast.LENGTH_SHORT).show()
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

        // ImageAnalysis for hand gesture recognition
        imageAnalyzer =
            ImageAnalysis.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .setTargetRotation(viewFinder.display.rotation)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
                .also {
                    it.setAnalyzer(backgroundExecutor) { image ->
                        recognizeHand(image)
                    }
                }

        // Separate ImageAnalysis for OCR processing
        ocrAnalyzer = ImageAnalysis.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setTargetRotation(viewFinder.display.rotation)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
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
            // Bind all use cases to camera
            camera = cameraProvider.bindToLifecycle(
                this,
                cameraSelector,
                preview,
                imageAnalyzer,
                ocrAnalyzer
            )

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

    // Process text recognition on just the ROI
    private fun processRoiTextRecognition(inputImage: InputImage, roi: Rect, imageProxy: ImageProxy) {
        try {
            // Create a cropped image using the ROI coordinates
            val croppedImage = InputImage.fromBitmap(
                android.graphics.Bitmap.createBitmap(
                    inputImage.bitmapInternal!!,
                    roi.left.coerceIn(0, inputImage.width - 1),
                    roi.top.coerceIn(0, inputImage.height - 1),
                    roi.width().coerceIn(1, inputImage.width - roi.left),
                    roi.height().coerceIn(1, inputImage.height - roi.top)
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
            // Clear the displayed text
            activity?.runOnUiThread {
                recognizedTextView.text = ""
                overlay.setDetectedText(null)
            }
            Toast.makeText(requireContext(), "OCR Stopped", Toast.LENGTH_SHORT).show()
            Log.d(TAG, "OCR process stopped")
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        imageAnalyzer?.targetRotation = viewFinder.display.rotation
        ocrAnalyzer?.targetRotation = viewFinder.display.rotation
    }

    // Process text recognition based on middle finger position
    private fun processTextRecognition(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage != null) {
            val inputImage = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

            // Check if middle finger is detected and OCR is running
            if (isOcrRunning && middleFingerPosition != null) {
                // Calculate region above the middle finger
                val fingerPosition = middleFingerPosition!!

                // Create ROI rectangle above the finger
                val roi = calculateFingerROI(
                    fingerPosition,
                    inputImage.width,
                    inputImage.height
                )

                // Process the ROI for text recognition
                processRoiTextRecognition(inputImage, roi, imageProxy)

                // Visualize the ROI on overlay
                activity?.runOnUiThread {
                    overlay.ocrRegion=roi
                }
            } else {
                // If no finger detected or OCR not running, just close the image
                imageProxy.close()
            }
        } else {
            imageProxy.close()
        }
    }

    // Calculate ROI based on finger position
    private fun calculateFingerROI(
        fingerPosition: Pair<Float, Float>,
        imageWidth: Int,
        imageHeight: Int
    ): Rect {
        // Convert view coordinates to image coordinates
        val viewToImageScaleX = imageWidth.toFloat() / viewFinder.width
        val viewToImageScaleY = imageHeight.toFloat() / viewFinder.height

        // Calculate finger position in image coordinates
        val fingerX = (fingerPosition.first * viewToImageScaleX).toInt()
        val fingerY = (fingerPosition.second * viewToImageScaleY).toInt()

        // Create rectangle above the finger
        val left = (fingerX - FINGER_REGION_WIDTH / 2).coerceIn(0, imageWidth)
        val top = (fingerY - VERTICAL_OFFSET - FINGER_REGION_HEIGHT).coerceIn(0, imageHeight)
        val right = (left + FINGER_REGION_WIDTH).coerceIn(0, imageWidth)
        val bottom = (top + FINGER_REGION_HEIGHT).coerceIn(0, imageHeight)

        return Rect(left, top, right, bottom)
    }

    // Display the recognized text
    private fun displayRecognizedText(text: Text) {
        activity?.runOnUiThread {
            if (text.text.isNotEmpty()) {
                // Update UI with recognized text
                Log.d(TAG, "Recognized text: ${text.text}")

                // Update the TextView
                recognizedTextView.text = text.text
                recognizedTextView.visibility = View.VISIBLE

                // Also update the overlay to display text in the OCR region
                overlay.setDetectedText(text.text)
            }
        }
    }

    // Update the onResults method to extract middle finger position and handle gestures
    override fun onResults(resultBundle: GestureRecognizerHelper.ResultBundle) {
        activity?.runOnUiThread {
            if (!isAdded) return@runOnUiThread

            // Show result of recognized gesture
            val gestureCategories = resultBundle.results.first().gestures()
            gestureRecognizerResultAdapter.updateResults(
                if (gestureCategories.isNotEmpty()) gestureCategories.first()
                else emptyList()
            )

            // Extract middle finger position (Landmark 12)
            val landmarks = resultBundle.results.first().landmarks()
            if (landmarks.isNotEmpty() && landmarks[0].size > 12) {
                // Get middle finger landmark (index 12)
                val middleFinger = landmarks[0][12]

                // Convert normalized coordinates to view coordinates
                middleFingerPosition = Pair(
                    middleFinger.x() * viewFinder.width,
                    middleFinger.y() * viewFinder.height
                )

                // Update overlay to show tracking point
                overlay.setFingerPosition(middleFingerPosition)
            } else {
                middleFingerPosition = null
                overlay.setFingerPosition(null)
                overlay.ocrRegion=null
            }

            // Handle gesture detection with throttling
            val currentTime = System.currentTimeMillis()
            if (gestureCategories.isNotEmpty() &&
                gestureCategories.first().isNotEmpty() &&
                currentTime - lastGestureDetectionTime > GESTURE_COOLDOWN_MS) {

                val topGesture = gestureCategories.first().first().categoryName()

                when (topGesture) {
                    "pointing_up", "two_up_inverted" -> {
                        lastGestureDetectionTime = currentTime
                        startOcrProcess()
                    }
                    "stop_inverted", "closed_fist" -> {
                        lastGestureDetectionTime = currentTime
                        stopOcrProcess()
                    }
                }
            }

            // Pass necessary information to OverlayView for visualization
            overlay.setResults(
                resultBundle.results.first(),
                resultBundle.inputImageHeight,
                resultBundle.inputImageWidth,
            )
        }
    }

    override fun onError(error: String) {
        activity?.runOnUiThread {
            Toast.makeText(requireContext(), error, Toast.LENGTH_SHORT).show()
            gestureRecognizerResultAdapter.updateResults(emptyList())
        }
    }
}