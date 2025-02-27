package com.parakurom.lingoflow

import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
    private var imageCapture: ImageCapture? = null
    private lateinit var cameraExecutor: ExecutorService
    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private var textToSpeech: TextToSpeech? = null
    private var lastRecognizedText: String = ""
    private var isSpeaking: Boolean = false

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
                    processImage(imageProxy)
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e("AutoReadFragment", "Image capture failed", exception)
                    Toast.makeText(requireContext(), "Image capture failed", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    private fun processImage(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage != null) {
            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

            textRecognizer.process(image)
                .addOnSuccessListener { visionText -> displayText(visionText) }
                .addOnFailureListener { e -> Log.e("AutoReadFragment", "OCR failed", e) }
                .addOnCompleteListener { imageProxy.close() }
        }
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
                Toast.makeText(requireContext(), "No text detected", Toast.LENGTH_SHORT).show()
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