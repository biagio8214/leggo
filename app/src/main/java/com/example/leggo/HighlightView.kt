package com.example.leggo

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

class HighlightView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val highlightPaint = Paint().apply {
        color = Color.parseColor("#80B2EBF2") // Celeste semi-trasparente
        style = Paint.Style.FILL
    }

    private var highlightRects: List<RectF> = emptyList()

    fun setHighlight(rects: List<RectF>?) {
        this.highlightRects = rects ?: emptyList()
        postInvalidate() // Ridisegna la view in modo sicuro dal background thread
    }

    fun clearHighlight() {
        this.highlightRects = emptyList()
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // L'origine (0,0) del canvas è in alto a sinistra della view
        highlightRects.forEach { rect ->
            canvas.drawRect(rect, highlightPaint)
        }
    }
}