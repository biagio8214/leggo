package com.example.leggo

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.FrameLayout
import android.graphics.RectF

class ZoomLayout @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr), ScaleGestureDetector.OnScaleGestureListener {

    private val scaleDetector = ScaleGestureDetector(context, this)
    private var scale = 1.0f
    private var dx = 0f
    private var dy = 0f
    private var lastFocusX = 0f
    private var lastFocusY = 0f
    private var mode = Mode.NONE

    private enum class Mode { NONE, DRAG, ZOOM }

    override fun onScale(detector: ScaleGestureDetector): Boolean {
        val scaleFactor = detector.scaleFactor
        val prevScale = scale
        scale *= scaleFactor
        scale = scale.coerceIn(1.0f, 5.0f) // Max zoom 5x

        if (prevScale != scale) {
            val adjustedScaleFactor = scale / prevScale
            val focusX = detector.focusX
            val focusY = detector.focusY
            dx += (dx - focusX) * (adjustedScaleFactor - 1)
            dy += (dy - focusY) * (adjustedScaleFactor - 1)
        }

        applyScaleAndTranslation()
        return true
    }

    override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
        mode = Mode.ZOOM
        return true
    }

    override fun onScaleEnd(detector: ScaleGestureDetector) {
        mode = Mode.NONE
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (ev.pointerCount > 1) return true
        if (scale > 1.0f && ev.actionMasked == MotionEvent.ACTION_MOVE) return true
        return super.onInterceptTouchEvent(ev)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (scale > 1.0f) {
                    mode = Mode.DRAG
                    lastFocusX = event.x
                    lastFocusY = event.y
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (mode == Mode.DRAG && scale > 1.0f) {
                    val deltaX = event.x - lastFocusX
                    val deltaY = event.y - lastFocusY
                    dx += deltaX
                    dy += deltaY
                    applyScaleAndTranslation()
                    lastFocusX = event.x
                    lastFocusY = event.y
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                mode = Mode.NONE
            }
        }
        return true 
    }

    private fun applyScaleAndTranslation() {
        if (childCount == 0) return
        val child = getChildAt(0)
        
        val contentWidth = child.width * scale
        val contentHeight = child.height * scale
        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()

        // Gestione Orizzontale
        if (contentWidth <= viewWidth) {
            // Centra se il contenuto è più piccolo della vista
            dx = (viewWidth - contentWidth) / 2 - (child.left * scale)
        } else {
            // Limita (clamp) i bordi se il contenuto è più grande
            // Il bordo sinistro del contenuto (child.left * scale + dx) non deve superare 0
            // Il bordo destro del contenuto non deve essere minore di viewWidth
            
            // maxDx: non vogliamo che il bordo sinistro vada oltre 0 (verso destra)
            // minDx: non vogliamo che il bordo destro vada oltre viewWidth (verso sinistra)
            // rightEdge = child.width * scale + dx
            
            val maxDx = 0f
            val minDx = viewWidth - contentWidth
            dx = dx.coerceIn(minDx, maxDx)
        }

        // Gestione Verticale
        if (contentHeight <= viewHeight) {
            dy = (viewHeight - contentHeight) / 2 - (child.top * scale)
        } else {
            val maxDy = 0f
            val minDy = viewHeight - contentHeight
            dy = dy.coerceIn(minDy, maxDy)
        }

        for (i in 0 until childCount) {
            val c = getChildAt(i)
            c.scaleX = scale
            c.scaleY = scale
            c.translationX = dx
            c.translationY = dy
        }
    }
    
    fun resetZoom() {
        scale = 1.0f
        dx = 0f
        dy = 0f
        applyScaleAndTranslation()
    }

    fun isZoomed(): Boolean {
        return scale > 1.0f
    }
    
    fun toChildPoint(x: Float, y: Float): FloatArray {
        return floatArrayOf((x - dx) / scale, (y - dy) / scale)
    }
    
    fun scrollToRect(rect: RectF) {
        if (width == 0 || height == 0) return
        
        if (!isZoomed()) return
        
        val zoomedRect = RectF(
            rect.left * scale + dx,
            rect.top * scale + dy,
            rect.right * scale + dx,
            rect.bottom * scale + dy
        )
        
        val viewRect = RectF(0f, 0f, width.toFloat(), height.toFloat())
        
        if (!viewRect.contains(zoomedRect)) {
            var moveX = 0f
            var moveY = 0f
            
            if (zoomedRect.width() > width) {
                moveX = (width - zoomedRect.width()) / 2 - zoomedRect.left
            } else if (zoomedRect.left < 0) {
                moveX = -zoomedRect.left + 50 
            } else if (zoomedRect.right > width) {
                moveX = width - zoomedRect.right - 50 
            }
            
            if (zoomedRect.height() > height) {
                moveY = (height - zoomedRect.height()) / 2 - zoomedRect.top
            } else if (zoomedRect.top < 0) {
                moveY = -zoomedRect.top + 100 
            } else if (zoomedRect.bottom > height) {
                moveY = height - zoomedRect.bottom - 100
            }
            
            if (moveX != 0f || moveY != 0f) {
                dx += moveX
                dy += moveY
                applyScaleAndTranslation()
            }
        }
    }
}