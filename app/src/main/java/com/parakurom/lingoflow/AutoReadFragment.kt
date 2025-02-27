package com.parakurom.lingoflow

import android.content.Context
import android.graphics.*
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.AttributeSet
import android.util.Log
import android.view.*
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.Fragment
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class AutoReadFragment : Fragment() {

    private lateinit var viewFinder: PreviewView
    private lateinit var recognizedTextView: TextView
    private lateinit var bottomSheet: NestedScrollView
    private lateinit var bottomSheetBehavior: BottomSheetBehavior<NestedScrollView>
    private lateinit var captureButton: FloatingActionButton
    private lateinit var restartTtsButton: Button
    private lateinit var stopTtsButton: Button
    private lateinit var seekBarPitch: SeekBar
    private lateinit var seekBarSpeed: SeekBar
    private lateinit var roiView: RegionOfInterestView

    private var imageCapture: ImageCapture? = null
    private lateinit var cameraExecutor: ExecutorService
    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private var textToSpeech: TextToSpeech? = null
    private var lastRecognizedText: String = ""
    private var isSpeaking: Boolean = false

    // Image processing variables
    private var capturedBitmap: Bitmap? = null
    private var imageWidth: Int = 0
    private var imageHeight: Int = 0

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.fragment_auto_read, container, false)

        viewFinder = view.findViewById(R.id.view_finder)
        bottomSheet = view.findViewById(R.id.bottom_sheet)
        recognizedTextView = view.findViewById(R.id.recognized_text)
        captureButton = view.findViewById(R.id.capture_button)
        restartTtsButton = view.findViewById(R.id.restart_tts_button)
        stopTtsButton = view.findViewById(R.id.stop_tts_button)
        seekBarPitch = view.findViewById(R.id.seekBarPitch)
        seekBarSpeed = view.findViewById(R.id.seekBarSpeed)
        roiView = view.findViewById(R.id.roi_view)

        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Initialize bottom sheet behavior
        bottomSheetBehavior = BottomSheetBehavior.from(bottomSheet)
        bottomSheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED

        cameraExecutor = Executors.newSingleThreadExecutor()
        startCamera()

        initializeTextToSpeech()

        captureButton.setOnClickListener {
            captureImage()
        }

        restartTtsButton.setOnClickListener {
            restartTextToSpeech()
        }

        stopTtsButton.setOnClickListener {
            stopTextToSpeech()
        }

        seekBarPitch.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    setPitch(progress / 50f)
                    // Re-speak text with new pitch if currently speaking
                    if (isSpeaking && lastRecognizedText.isNotEmpty()) {
                        restartTextToSpeech()
                    }
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        seekBarSpeed.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    setSpeechRate(progress / 50f)
                    // Re-speak text with new speed if currently speaking
                    if (isSpeaking && lastRecognizedText.isNotEmpty()) {
                        restartTextToSpeech()
                    }
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun initializeTextToSpeech() {
        textToSpeech = TextToSpeech(requireContext()) { status ->
            if (status == TextToSpeech.SUCCESS) {
                textToSpeech?.language = Locale.ENGLISH
                textToSpeech?.setOnUtteranceCompletedListener {
                    activity?.runOnUiThread {
                        isSpeaking = false
                        updateButtonStates()
                    }
                }
            } else {
                Log.e("AutoReadFragment", "TextToSpeech initialization failed")
                Toast.makeText(requireContext(), "TTS Initialization Failed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())

        cameraProviderFuture.addListener({
            try {
                val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()
                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(viewFinder.surfaceProvider)
                }

                imageCapture = ImageCapture.Builder().build()

                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture)

                // Get preview dimensions to set initial ROI size
                viewFinder.post {
                    roiView.setInitialRoi(viewFinder.width, viewFinder.height)
                }

            } catch (e: Exception) {
                Log.e("AutoReadFragment", "Camera binding failed", e)
                Toast.makeText(requireContext(), "Camera initialization failed", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    private fun captureImage() {
        val imageCapture = imageCapture ?: run {
            Log.e("AutoReadFragment", "ImageCapture is not initialized")
            Toast.makeText(requireContext(), "Camera is not ready yet", Toast.LENGTH_SHORT).show()
            return
        }

        imageCapture.takePicture(
            ContextCompat.getMainExecutor(requireContext()),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(imageProxy: ImageProxy) {
                    processImageWithROI(imageProxy)
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e("AutoReadFragment", "Image capture failed", exception)
                    Toast.makeText(requireContext(), "Image capture failed", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    private fun processImageWithROI(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage != null) {
            // Convert the image to a bitmap
            val bitmap = imageToBitmap(mediaImage, imageProxy.imageInfo.rotationDegrees)
            capturedBitmap = bitmap
            imageWidth = bitmap.width
            imageHeight = bitmap.height

            // Calculate ROI coordinates relative to the captured image
            val roi = calculateROIRect(bitmap.width, bitmap.height)

            // Crop the bitmap to the ROI
            val croppedBitmap = cropBitmap(bitmap, roi)

            // Create InputImage from cropped bitmap
            val image = InputImage.fromBitmap(croppedBitmap, 0)

            textRecognizer.process(image)
                .addOnSuccessListener { visionText -> displayText(visionText) }
                .addOnFailureListener { e ->
                    Log.e("AutoReadFragment", "OCR failed", e)
                    Toast.makeText(requireContext(), "Text recognition failed", Toast.LENGTH_SHORT).show()
                }
                .addOnCompleteListener { imageProxy.close() }
        }
    }

    private fun imageToBitmap(mediaImage: android.media.Image, rotation: Int): Bitmap {
        val buffer = mediaImage.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)

        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

        // Rotate the bitmap if needed
        return if (rotation != 0) {
            val matrix = Matrix()
            matrix.postRotate(rotation.toFloat())
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } else {
            bitmap
        }
    }

    private fun calculateROIRect(imageWidth: Int, imageHeight: Int): Rect {
        // Get ROI relative coordinates (0.0-1.0)
        val relativeRect = roiView.getRelativeCoordinates()

        // Convert to actual pixel coordinates in the captured image
        return Rect(
            (relativeRect.left * imageWidth).toInt(),
            (relativeRect.top * imageHeight).toInt(),
            (relativeRect.right * imageWidth).toInt(),
            (relativeRect.bottom * imageHeight).toInt()
        )
    }

    private fun cropBitmap(bitmap: Bitmap, roi: Rect): Bitmap {
        // Make sure the ROI is within bounds
        val safeRect = Rect(
            roi.left.coerceIn(0, bitmap.width),
            roi.top.coerceIn(0, bitmap.height),
            roi.right.coerceIn(0, bitmap.width),
            roi.bottom.coerceIn(0, bitmap.height)
        )

        // Check if the ROI has valid dimensions
        if (safeRect.width() <= 0 || safeRect.height() <= 0) {
            // Return the original bitmap if ROI is invalid
            return bitmap
        }

        return Bitmap.createBitmap(
            bitmap,
            safeRect.left,
            safeRect.top,
            safeRect.width(),
            safeRect.height()
        )
    }

    private fun displayText(visionText: Text) {
        activity?.runOnUiThread {
            lastRecognizedText = visionText.text

            if (lastRecognizedText.isNotEmpty()) {
                recognizedTextView.text = lastRecognizedText

                // Expand bottom sheet to show the text
                bottomSheetBehavior.state = BottomSheetBehavior.STATE_EXPANDED

                // Start speaking automatically on capture
                startTextToSpeech()
            } else {
                Toast.makeText(requireContext(), "No text detected in the selected area", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startTextToSpeech() {
        if (lastRecognizedText.isEmpty()) {
            Toast.makeText(requireContext(), "No text to read", Toast.LENGTH_SHORT).show()
            return
        }

        val params = HashMap<String, String>()
        params[TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID] = "speechId"

        textToSpeech?.speak(lastRecognizedText, TextToSpeech.QUEUE_FLUSH, params)
        isSpeaking = true
        updateButtonStates()
    }

    private fun stopTextToSpeech() {
        if (isSpeaking) {
            textToSpeech?.stop()
            isSpeaking = false
            updateButtonStates()
        }
    }

    private fun restartTextToSpeech() {
        stopTextToSpeech()
        startTextToSpeech()
    }

    private fun updateButtonStates() {
        // Enable/disable buttons based on state
        stopTtsButton.isEnabled = isSpeaking
        restartTtsButton.isEnabled = lastRecognizedText.isNotEmpty()
    }

    private fun setPitch(pitch: Float) {
        textToSpeech?.setPitch(pitch)
    }

    private fun setSpeechRate(rate: Float) {
        textToSpeech?.setSpeechRate(rate)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        cameraExecutor.shutdown()
        textToSpeech?.stop()
        textToSpeech?.shutdown()
    }
}

/**
 * Custom view for Region of Interest selection
 */
class RegionOfInterestView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // ROI rectangle (in view coordinates)
    private val roiRect = RectF()

    // For touch handling
    private var activePointerId = MotionEvent.INVALID_POINTER_ID
    private var isResizing = false
    private var isMoving = false
    private val lastTouchPoint = PointF()
    private val resizeHandleSize = 80f

    // For drawing
    private val roiPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 4f
        isAntiAlias = true
    }

    private val backgroundPaint = Paint().apply {
        color = Color.parseColor("#80000000") // Semi-transparent black
        style = Paint.Style.FILL
    }

    private val handlePaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    fun setInitialRoi(viewWidth: Int, viewHeight: Int) {
        // Set initial ROI to center 70% of the view
        val roiWidth = viewWidth * 0.7f
        val roiHeight = viewHeight * 0.3f  // Good for text which is usually wider than tall

        roiRect.left = (viewWidth - roiWidth) / 2
        roiRect.top = (viewHeight - roiHeight) / 2
        roiRect.right = roiRect.left + roiWidth
        roiRect.bottom = roiRect.top + roiHeight

        invalidate()
    }

    // Get ROI in relative coordinates (0.0-1.0)
    fun getRelativeCoordinates(): RectF {
        return RectF(
            roiRect.left / width,
            roiRect.top / height,
            roiRect.right / width,
            roiRect.bottom / height
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Draw semi-transparent background outside ROI
        // Top
        canvas.drawRect(0f, 0f, width.toFloat(), roiRect.top, backgroundPaint)
        // Left
        canvas.drawRect(0f, roiRect.top, roiRect.left, roiRect.bottom, backgroundPaint)
        // Right
        canvas.drawRect(roiRect.right, roiRect.top, width.toFloat(), roiRect.bottom, backgroundPaint)
        // Bottom
        canvas.drawRect(0f, roiRect.bottom, width.toFloat(), height.toFloat(), backgroundPaint)

        // Draw ROI rectangle
        canvas.drawRect(roiRect, roiPaint)

        // Draw resize handles at corners
        val handleRadius = resizeHandleSize / 4
        canvas.drawCircle(roiRect.left, roiRect.top, handleRadius, handlePaint)
        canvas.drawCircle(roiRect.right, roiRect.top, handleRadius, handlePaint)
        canvas.drawCircle(roiRect.left, roiRect.bottom, handleRadius, handlePaint)
        canvas.drawCircle(roiRect.right, roiRect.bottom, handleRadius, handlePaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activePointerId = event.getPointerId(0)
                lastTouchPoint.set(event.x, event.y)

                // Check if touch is on a resize handle or inside the ROI
                isResizing = isOnResizeHandle(lastTouchPoint.x, lastTouchPoint.y)
                isMoving = !isResizing && roiRect.contains(lastTouchPoint.x, lastTouchPoint.y)
            }

            MotionEvent.ACTION_MOVE -> {
                val pointerIndex = event.findPointerIndex(activePointerId)
                if (pointerIndex != -1) {
                    val currentX = event.getX(pointerIndex)
                    val currentY = event.getY(pointerIndex)
                    val dx = currentX - lastTouchPoint.x
                    val dy = currentY - lastTouchPoint.y

                    if (isResizing) {
                        resizeROI(lastTouchPoint.x, lastTouchPoint.y, currentX, currentY)
                    } else if (isMoving) {
                        moveROI(dx, dy)
                    }

                    lastTouchPoint.set(currentX, currentY)
                    invalidate()
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                activePointerId = MotionEvent.INVALID_POINTER_ID
                isResizing = false
                isMoving = false
            }
        }

        return true
    }

    private fun isOnResizeHandle(x: Float, y: Float): Boolean {
        // Check if touch point is near any of the corners
        val tolerance = resizeHandleSize / 2

        // Top-left
        if (Math.abs(x - roiRect.left) <= tolerance && Math.abs(y - roiRect.top) <= tolerance) {
            return true
        }
        // Top-right
        if (Math.abs(x - roiRect.right) <= tolerance && Math.abs(y - roiRect.top) <= tolerance) {
            return true
        }
        // Bottom-left
        if (Math.abs(x - roiRect.left) <= tolerance && Math.abs(y - roiRect.bottom) <= tolerance) {
            return true
        }
        // Bottom-right
        if (Math.abs(x - roiRect.right) <= tolerance && Math.abs(y - roiRect.bottom) <= tolerance) {
            return true
        }

        return false
    }

    private fun resizeROI(startX: Float, startY: Float, currentX: Float, currentY: Float) {
        // Determine which corner is being dragged
        val isLeftSide = Math.abs(startX - roiRect.left) <= resizeHandleSize / 2
        val isRightSide = Math.abs(startX - roiRect.right) <= resizeHandleSize / 2
        val isTopSide = Math.abs(startY - roiRect.top) <= resizeHandleSize / 2
        val isBottomSide = Math.abs(startY - roiRect.bottom) <= resizeHandleSize / 2

        // Constrain to view bounds
        val newX = currentX.coerceIn(0f, width.toFloat())
        val newY = currentY.coerceIn(0f, height.toFloat())

        // Update the appropriate sides
        if (isLeftSide) roiRect.left = Math.min(newX, roiRect.right - 100)
        if (isRightSide) roiRect.right = Math.max(newX, roiRect.left + 100)
        if (isTopSide) roiRect.top = Math.min(newY, roiRect.bottom - 100)
        if (isBottomSide) roiRect.bottom = Math.max(newY, roiRect.top + 100)
    }

    private fun moveROI(dx: Float, dy: Float) {
        // Apply movement but ensure ROI stays within view bounds
        if (roiRect.left + dx >= 0 && roiRect.right + dx <= width) {
            roiRect.left += dx
            roiRect.right += dx
        }

        if (roiRect.top + dy >= 0 && roiRect.bottom + dy <= height) {
            roiRect.top += dy
            roiRect.bottom += dy
        }
    }
}