package com.parakurom.lingoflow

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * Custom view for Region of Interest selection
 * This class can be used by any fragment that needs ROI selection functionality
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

    /**
     * Set initial ROI size based on view dimensions
     */
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

    /**
     * Get ROI in relative coordinates (0.0-1.0)
     * Used for converting view coordinates to image coordinates
     */
    fun getRelativeCoordinates(): RectF {
        return RectF(
            roiRect.left / width,
            roiRect.top / height,
            roiRect.right / width,
            roiRect.bottom / height
        )
    }

    /**
     * Get ROI in absolute pixel coordinates
     */
    fun getRoiRect(): RectF {
        return RectF(roiRect)
    }

    /**
     * Set ROI rectangle to specific dimensions
     */
    fun setRoi(left: Float, top: Float, right: Float, bottom: Float) {
        roiRect.set(left, top, right, bottom)
        invalidate()
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

    /**
     * Calculate ROI rectangle in target image coordinates
     */
    fun calculateROIRect(imageWidth: Int, imageHeight: Int): android.graphics.Rect {
        // Get ROI relative coordinates (0.0-1.0)
        val relativeRect = getRelativeCoordinates()

        // Convert to actual pixel coordinates in the target image
        return android.graphics.Rect(
            (relativeRect.left * imageWidth).toInt(),
            (relativeRect.top * imageHeight).toInt(),
            (relativeRect.right * imageWidth).toInt(),
            (relativeRect.bottom * imageHeight).toInt()
        )
    }
}