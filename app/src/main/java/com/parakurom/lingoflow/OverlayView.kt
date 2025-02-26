package com.parakurom.lingoflow

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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

    private var scaleFactor = 1f
    private var imageWidth = 1
    private var imageHeight = 1

    // Store the last few middle fingertip positions for the trail effect
    private val middleFingerTrail = LinkedList<Pair<Float, Float>>()
    private val trailSize = 10  // Number of points to keep for the vanishing effect

    override fun draw(canvas: Canvas) {
        super.draw(canvas)
        results?.let { gestureRecognizerResult ->
            gestureRecognizerResult.landmarks().forEach { landmark ->
                landmark.forEach { normalizedLandmark ->
//                    canvas.drawPoint(
//                        normalizedLandmark.x() * imageWidth * scaleFactor,
//                        normalizedLandmark.y() * imageHeight * scaleFactor,
//                        pointPaint
//                    )
                }

                HandLandmarker.HAND_CONNECTIONS.forEach { connection ->
                    val start = landmark[connection!!.start()]
                    val end = landmark[connection.end()]
//                    canvas.drawLine(
//                        start.x() * imageWidth * scaleFactor,
//                        start.y() * imageHeight * scaleFactor,
//                        end.x() * imageWidth * scaleFactor,
//                        end.y() * imageHeight * scaleFactor,
//                        linePaint
//                    )
                }

                // Get middle fingertip position (Landmark Index 12)
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
}