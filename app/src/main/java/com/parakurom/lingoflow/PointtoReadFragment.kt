package com.parakurom.lingoflow

import android.annotation.SuppressLint
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.Bundle
import android.speech.tts.TextToSpeech
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
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.Locale
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

    // TTS variables
    private lateinit var textToSpeech: TextToSpeech
    private var isTtsInitialized = false
    private var lastReadText = ""
    private var lastOcrTime = 0L
    private val OCR_COOLDOWN_MS = 600 // Cooldown between text readings to prevent rapid repeating

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
    // Adjust region dimensions for better text recognition
    private val FINGER_REGION_WIDTH = 300 // Width of OCR region in pixels (increased for better text capture)
    private val FINGER_REGION_HEIGHT = 75// Height of OCR region in pixels (increased for better text capture)
    private val VERTICAL_OFFSET = 50 // How far above finger to place the region
    private var middleFingerDepth: Float = 0f  // Initialize depth to 0

    // Add a processing lock to prevent overlapping OCR operations
    private var isProcessingOcr = false

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

        // Shutdown TTS
        if (::textToSpeech.isInitialized && isTtsInitialized) {
            textToSpeech.stop()
            textToSpeech.shutdown()
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

        // Initialize Text-to-Speech
        initializeTextToSpeech()

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

    // Initialize the Text-to-Speech engine
    private fun initializeTextToSpeech() {
        textToSpeech = TextToSpeech(requireContext()) { status ->
            if (status == TextToSpeech.SUCCESS) {
                // Set language to default device language
                val result = textToSpeech.setLanguage(Locale.getDefault())
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.e(TAG, "TTS language not supported")
                    Toast.makeText(requireContext(), "TTS language not supported", Toast.LENGTH_SHORT).show()
                } else {
                    isTtsInitialized = true
                    Log.d(TAG, "TTS initialized successfully")
                    // Confirm TTS initialization with a toast
                    Toast.makeText(requireContext(), "Text-to-Speech ready", Toast.LENGTH_SHORT).show()
                }
            } else {
                Log.e(TAG, "TTS initialization failed")
                Toast.makeText(requireContext(), "TTS initialization failed", Toast.LENGTH_SHORT).show()
            }
        }
        // Set speech rate and pitch
        textToSpeech.setSpeechRate(0.9f) // Slightly slower for better clarity
        textToSpeech.setPitch(1.0f)
    }

    // Speak the given text using TTS with error handling
    private fun speakText(text: String) {
        if (!isTtsInitialized) {
            Log.e(TAG, "TTS not initialized, cannot speak")
            return
        }

        if (text.isBlank()) {
            Log.d(TAG, "Empty text, nothing to speak")
            return
        }

        try {
            // Only speak if the text is different from the last one or enough time has passed
            val currentTime = System.currentTimeMillis()
            if (text != lastReadText || currentTime - lastOcrTime > OCR_COOLDOWN_MS) {
                lastReadText = text
                lastOcrTime = currentTime

                // Check TTS status before speaking
                if (textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, null, "tts1") == TextToSpeech.ERROR) {
                    Log.e(TAG, "Error speaking text")
                } else {
                    Log.d(TAG, "Speaking text: $text")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception while speaking text: ${e.message}")
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
                    if (isOcrRunning && !isProcessingOcr) {
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

    // Improved image to bitmap conversion with proper rotation handling
    private fun imageToBitmap(imageProxy: ImageProxy): Bitmap? {
        val image = imageProxy.image ?: return null

        try {
            // For RGBA_8888 format directly
            if (imageProxy.format == ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888) {
                val byteBuffer = imageProxy.planes[0].buffer
                val bytes = ByteArray(byteBuffer.remaining())
                byteBuffer.get(bytes)

                val bitmap = Bitmap.createBitmap(
                    imageProxy.width, imageProxy.height, Bitmap.Config.ARGB_8888
                )
                bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(bytes))

                // Apply rotation if needed
                val rotationDegrees = imageProxy.imageInfo.rotationDegrees
                if (rotationDegrees != 0) {
                    val matrix = Matrix()
                    matrix.postRotate(rotationDegrees.toFloat())
                    return Bitmap.createBitmap(
                        bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true
                    )
                }
                return bitmap
            }
            // When using YUV_420_888 format
            else if (image.format == ImageFormat.YUV_420_888) {
                val planes = image.planes
                val yBuffer = planes[0].buffer
                val uBuffer = planes[1].buffer
                val vBuffer = planes[2].buffer

                val ySize = yBuffer.remaining()
                val uSize = uBuffer.remaining()
                val vSize = vBuffer.remaining()

                val nv21 = ByteArray(ySize + uSize + vSize)

                // Copy Y plane
                yBuffer.get(nv21, 0, ySize)

                // Copy VU data
                vBuffer.get(nv21, ySize, vSize)
                uBuffer.get(nv21, ySize + vSize, uSize)

                val yuvImage = YuvImage(
                    nv21,
                    ImageFormat.NV21,
                    image.width,
                    image.height,
                    null
                )

                val out = ByteArrayOutputStream()
                yuvImage.compressToJpeg(
                    Rect(0, 0, image.width, image.height),
                    100,
                    out
                )

                val imageBytes = out.toByteArray()
                val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)

                // Apply rotation if needed
                val rotationDegrees = imageProxy.imageInfo.rotationDegrees
                if (rotationDegrees != 0) {
                    val matrix = Matrix()
                    matrix.postRotate(rotationDegrees.toFloat())
                    return Bitmap.createBitmap(
                        bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true
                    )
                }
                return bitmap
            }
            // For JPEG format
            else if (image.format == ImageFormat.JPEG) {
                val buffer = image.planes[0].buffer
                val bytes = ByteArray(buffer.capacity())
                buffer.get(bytes)
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

                // Apply rotation if needed
                val rotationDegrees = imageProxy.imageInfo.rotationDegrees
                if (rotationDegrees != 0) {
                    val matrix = Matrix()
                    matrix.postRotate(rotationDegrees.toFloat())
                    return Bitmap.createBitmap(
                        bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true
                    )
                }
                return bitmap
            }
            return null
        } catch (e: Exception) {
            Log.e(TAG, "Error converting image to bitmap: ${e.message}")
            return null
        }
    }

    // Direct OCR on region of bitmap with improved error handling
    private fun processRoiTextRecognition(imageProxy: ImageProxy, roi: Rect) {
        if (isProcessingOcr) {
            imageProxy.close()
            return
        }

        isProcessingOcr = true

        val bitmap = imageToBitmap(imageProxy)
        if (bitmap == null) {
            Log.e(TAG, "Failed to get bitmap from image")
            imageProxy.close()
            isProcessingOcr = false
            return
        }

        try {
            // Ensure roi is within bitmap bounds
            val roiLeft = roi.left.coerceIn(0, bitmap.width - 1)
            val roiTop = roi.top.coerceIn(0, bitmap.height - 1)
            val roiWidth = roi.width().coerceIn(1, bitmap.width - roiLeft)
            val roiHeight = roi.height().coerceIn(1, bitmap.height - roiTop)

            if (roiWidth <= 1 || roiHeight <= 1) {
                Log.e(TAG, "ROI dimensions too small: ${roiWidth}x${roiHeight}")
                bitmap.recycle()
                imageProxy.close()
                isProcessingOcr = false
                return
            }

            // Create cropped bitmap
            val croppedBitmap = Bitmap.createBitmap(
                bitmap,
                roiLeft,
                roiTop,
                roiWidth,
                roiHeight
            )

            // Process the cropped bitmap
            val inputImage = InputImage.fromBitmap(croppedBitmap, 0)

            textRecognizer.process(inputImage)
                .addOnSuccessListener { text ->
                    displayRecognizedText(text)
                    // Recycle bitmaps to avoid memory leaks
                    croppedBitmap.recycle()
                    bitmap.recycle()
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "ROI text recognition failed: ${e.message}", e)
                    croppedBitmap.recycle()
                    bitmap.recycle()
                }
                .addOnCompleteListener {
                    imageProxy.close()
                    isProcessingOcr = false
                }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing ROI: ${e.message}", e)
            bitmap.recycle()
            imageProxy.close()
            isProcessingOcr = false
        }
    }

    // Start OCR processing with visual feedback
    private fun startOcrProcess() {
        if (!isOcrRunning) {
            isOcrRunning = true
            activity?.runOnUiThread {
                recognizedTextView.visibility = View.VISIBLE
                recognizedTextView.text = "Reading mode active..."
            }
            Toast.makeText(requireContext(), "Reading Mode Started", Toast.LENGTH_SHORT).show()
            Log.d(TAG, "OCR and TTS process started")
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        imageAnalyzer?.targetRotation = viewFinder.display.rotation
        ocrAnalyzer?.targetRotation = viewFinder.display.rotation
    }

    // Add these properties to your class
    private var lastProcessedRoi: Rect? = null
    private var lastRecognizedTextTime: Long = 0
    private val TTS_COOLDOWN_MS = 3000 // 3 seconds between readings of the same region
    private val ROI_CHANGE_THRESHOLD = 100 // Pixel distance needed to consider a new ROI

    // Modify the processTextRecognition method with cooldown logic
    private fun processTextRecognition(imageProxy: ImageProxy) {
        if (!isOcrRunning || middleFingerPosition == null) {
            imageProxy.close()
            return
        }

        try {
            // Calculate region above the middle finger
            val fingerPosition = middleFingerPosition!!

            // Get media image
            val mediaImage = imageProxy.image
            if (mediaImage == null) {
                imageProxy.close()
                return
            }

            // Calculate ROI rectangle above the finger
            val roi = calculateFingerROI(
                fingerPosition,
                imageProxy.width,
                imageProxy.height
            )

            // Check if we need to process this ROI or if we're still in cooldown period
            val currentTime = System.currentTimeMillis()
            val shouldProcessNewRoi = when {
                // First ROI, always process
                lastProcessedRoi == null -> true

                // Check if enough time has passed since last text recognition
                (currentTime - lastRecognizedTextTime > TTS_COOLDOWN_MS) -> true

                // Check if ROI has moved significantly (user pointing at different text)
                roiDistanceExceedsThreshold(roi, lastProcessedRoi!!) -> true

                // Otherwise, skip processing to avoid repeated readings
                else -> false
            }

            if (shouldProcessNewRoi && !isProcessingOcr) {
                // Process the ROI for text recognition directly
                processRoiTextRecognition(imageProxy, roi)
                lastProcessedRoi = roi
            } else {
                imageProxy.close()
            }

            // Always visualize the ROI on overlay
            activity?.runOnUiThread {
                overlay.ocrRegion = roi
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in processTextRecognition: ${e.message}", e)
            imageProxy.close()
            isProcessingOcr = false
        }
    }

    // Calculate if two ROIs are significantly different
    private fun roiDistanceExceedsThreshold(roi1: Rect, roi2: Rect): Boolean {
        val centerX1 = roi1.left + roi1.width() / 2
        val centerY1 = roi1.top + roi1.height() / 2
        val centerX2 = roi2.left + roi2.width() / 2
        val centerY2 = roi2.top + roi2.height() / 2

        val distance = Math.sqrt(
            Math.pow((centerX2 - centerX1).toDouble(), 2.0) +
                    Math.pow((centerY2 - centerY1).toDouble(), 2.0)
        )

        return distance > ROI_CHANGE_THRESHOLD
    }

    // Modify the displayRecognizedText method to update the last recognition time
    private fun displayRecognizedText(text: Text) {
        if (!isAdded) return

        val recognizedText = text.text.trim()

        // Update the timestamp when text is successfully recognized
        lastRecognizedTextTime = System.currentTimeMillis()

        activity?.runOnUiThread {
            if (recognizedText.isNotEmpty()) {
                // Update UI with recognized text
                Log.d(TAG, "Recognized text: $recognizedText")

                // Update the TextView with better visibility
                recognizedTextView.text = recognizedText
                recognizedTextView.visibility = View.VISIBLE

                // Make text larger or more prominent if needed
                recognizedTextView.textSize = 18f

                // Also update the overlay to display text in the OCR region
                overlay.setDetectedText(recognizedText)

                // Speak the recognized text
                speakText(recognizedText)
            } else {
                // For empty results, show a searching message
                recognizedTextView.text = "Searching for text..."
            }
        }
    }

    // When stopping the OCR process, reset the cooldown variables
    private fun stopOcrProcess() {
        if (isOcrRunning) {
            isOcrRunning = false
            // Reset cooldown tracking variables
            lastProcessedRoi = null
            lastRecognizedTextTime = 0

            // Clear the displayed text
            activity?.runOnUiThread {
                recognizedTextView.text = ""
                recognizedTextView.visibility = View.GONE
                overlay.setDetectedText(null)
                overlay.ocrRegion = null
            }
            // Stop any ongoing speech
            if (isTtsInitialized) {
                textToSpeech.stop()
            }
            Toast.makeText(requireContext(), "Reading Mode Stopped", Toast.LENGTH_SHORT).show()
            Log.d(TAG, "OCR and TTS process stopped")
        }
    }
     // Calculate ROI based on finger position with improved sizing and positioning
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

        // Apply depth-based scaling - deeper fingers (farther from camera) need larger regions
        val zDepthScaleFactor = 1.0f
        val scaledWidth = (FINGER_REGION_WIDTH * zDepthScaleFactor).toInt()
        val scaledHeight = (FINGER_REGION_HEIGHT * zDepthScaleFactor).toInt()

        // Place the top-left corner of the box directly at the finger position
        // This makes the box start exactly where the finger is pointing
        val left = fingerX.coerceIn(0, imageWidth - FINGER_REGION_WIDTH)
        val top = fingerY.coerceIn(0, imageHeight - FINGER_REGION_HEIGHT)

        // Calculate right and bottom based on the scaled dimensions
        val right = (left + scaledWidth).coerceAtMost(imageWidth)
        val bottom = (top + scaledHeight).coerceAtMost(imageHeight)

        return Rect(left, top, right, bottom)
    }
    // Display the recognized text and speak it with improved visibility

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

                // Store z-depth for scaling calculations
                middleFingerDepth = middleFinger.z()

                // Update overlay to show tracking point
                overlay.setFingerPosition(middleFingerPosition)

                // Log the finger position for debugging
                Log.d(TAG, "Middle finger at: ${middleFingerPosition?.first}, ${middleFingerPosition?.second}, depth: $middleFingerDepth")
            } else {
                middleFingerPosition = null
                overlay.setFingerPosition(null)
                overlay.ocrRegion = null
            }

            // Handle gesture detection with throttling
            val currentTime = System.currentTimeMillis()
            if (gestureCategories.isNotEmpty() &&
                gestureCategories.first().isNotEmpty() &&
                currentTime - lastGestureDetectionTime > GESTURE_COOLDOWN_MS) {

                val topGesture = gestureCategories.first().first().categoryName()
                val score = gestureCategories.first().first().score()

                // Only consider gestures with reasonable confidence
                if (score > 0.6) {
                    Log.d(TAG, "Detected gesture: $topGesture with score $score")

                    when (topGesture) {
                        "two_up_inverted", "peace", "thumbs_up" -> {
                            lastGestureDetectionTime = currentTime
                            startOcrProcess()
                        }
                        "stop_inverted", "closed_fist", "thumbs_down" -> {
                            lastGestureDetectionTime = currentTime
                            stopOcrProcess()
                        }
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