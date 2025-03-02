package com.parakurom.lingoflow

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizerResult
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import java.util.LinkedList
import kotlin.math.max

class OverlayView(context: Context?, attrs: AttributeSet?) : View(context, attrs) {

    private var results: GestureRecognizerResult? = null

    private val linePaint = Paint().apply {
        color = Color.BLUE
        strokeWidth = 8f
        style = Paint.Style.STROKE
    }

    private val pointPaint = Paint().apply {
        color = Color.YELLOW
        strokeWidth = 8f
        style = Paint.Style.FILL
    }

    // Trail properties
    private val trailPaint = Paint().apply {
        color = Color.RED
        strokeWidth = 6f
        style = Paint.Style.STROKE
    }

    // OCR Region Paint
    private val ocrRegionPaint = Paint().apply {
        color = Color.GREEN
        strokeWidth = 3f
        style = Paint.Style.STROKE
    }

    // Text for showing OCR results
    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 40f
        style = Paint.Style.FILL
        setShadowLayer(2f, 0f, 0f, Color.BLACK)
    }

    private var scaleFactor = 1f
    private var imageWidth = 1
    private var imageHeight = 1

    // Store the last few middle fingertip positions for the trail effect
    private val middleFingerTrail = LinkedList<Pair<Float, Float>>()
    private val trailSize = 10  // Number of points to keep for the vanishing effect
    private var fingerPosition: Pair<Float, Float>? = null
    var ocrRegion: Rect? = null

    // Text detected by OCR
    private var detectedText: String? = null

    override fun draw(canvas: Canvas) {
        super.draw(canvas)

        // Draw middle finger position and OCR region
        fingerPosition?.let { (x, y) ->
            // Scale to view coordinates
            val viewX = x * canvas.width / imageWidth
            val viewY = y * canvas.height / imageHeight

            // Draw circle at finger position
            pointPaint.style = Paint.Style.FILL
            canvas.drawCircle(viewX, viewY, 20f, pointPaint)

            // Draw OCR region above finger
            ocrRegion?.let { region ->
                val scaledLeft = region.left * canvas.width / imageWidth
                val scaledTop = region.top * canvas.height / imageHeight
                val scaledRight = region.right * canvas.width / imageWidth
                val scaledBottom = region.bottom * canvas.height / imageHeight

                ocrRegionPaint.style = Paint.Style.STROKE
                canvas.drawRect(
                    scaledLeft.toFloat(),
                    scaledTop.toFloat(),
                    scaledRight.toFloat(),
                    scaledBottom.toFloat(),
                    ocrRegionPaint
                )

                // Draw semi-transparent background for OCR region
                ocrRegionPaint.style = Paint.Style.FILL
                ocrRegionPaint.color = Color.argb(40, 0, 255, 0)
                canvas.drawRect(
                    scaledLeft.toFloat(),
                    scaledTop.toFloat(),
                    scaledRight.toFloat(),
                    scaledBottom.toFloat(),
                    ocrRegionPaint
                )
                ocrRegionPaint.color = Color.GREEN
                ocrRegionPaint.style = Paint.Style.STROKE

                // Draw detected text
                detectedText?.let { text ->
                    canvas.drawText(
                        text,
                        scaledLeft + 5f,
                        scaledTop - 10f,
                        textPaint
                    )
                }
            }
        }

        results?.let { gestureRecognizerResult ->
            gestureRecognizerResult.landmarks().forEach { landmark ->
                // Visualize hand landmarks if needed
                if (false) { // Disabled for now, but can be enabled if needed
                    landmark.forEach { normalizedLandmark ->
                        canvas.drawPoint(
                            normalizedLandmark.x() * imageWidth * scaleFactor,
                            normalizedLandmark.y() * imageHeight * scaleFactor,
                            pointPaint
                        )
                    }

                    HandLandmarker.HAND_CONNECTIONS.forEach { connection ->
                        val start = landmark[connection!!.start()]
                        val end = landmark[connection.end()]
                        canvas.drawLine(
                            start.x() * imageWidth * scaleFactor,
                            start.y() * imageHeight * scaleFactor,
                            end.x() * imageWidth * scaleFactor,
                            end.y() * imageHeight * scaleFactor,
                            linePaint
                        )
                    }
                }

                // Get middle fingertip position (Landmark Index 12)
                if (landmark.size > 12) {
                    val middleFingertip = landmark[12]
                    val x = middleFingertip.x() * imageWidth * scaleFactor
                    val y = middleFingertip.y() * imageHeight * scaleFactor

                    // Add new position to trail list
                    middleFingerTrail.addFirst(x to y)

                    // Keep only the latest `trailSize` points
                    if (middleFingerTrail.size > trailSize) {
                        middleFingerTrail.removeLast()
                    }

                    // Draw vanishing trail
                    for (i in 1 until middleFingerTrail.size) {
                        val (x1, y1) = middleFingerTrail[i - 1]
                        val (x2, y2) = middleFingerTrail[i]

                        // Adjust alpha for fading effect
                        trailPaint.alpha = (255 * (1 - (i.toFloat() / trailSize))).toInt()

                        canvas.drawLine(x1, y1, x2, y2, trailPaint)
                    }
                }
            }
        }
    }

    fun setResults(
        gestureRecognizerResult: GestureRecognizerResult,
        imageHeight: Int,
        imageWidth: Int,
    ) {
        results = gestureRecognizerResult

        this.imageHeight = imageHeight
        this.imageWidth = imageWidth

        // Scale landmarks to match displayed size
        scaleFactor = max(width * 1f / imageWidth, height * 1f / imageHeight)

        invalidate()
    }

    fun setFingerPosition(position: Pair<Float, Float>?) {
        fingerPosition = position
        invalidate()
    }



    fun setDetectedText(text: String?) {
        detectedText = text
        invalidate()
    }
}