package com.example.leggo

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

class HighlightView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val highlights = mutableListOf<RectF>()
    private val paint = Paint().apply {
        color = 0x8000FFFF.toInt() // Semi-transparent cyan/celeste
        style = Paint.Style.FILL
    }

    fun setHighlight(rects: List<RectF>) {
        highlights.clear()
        highlights.addAll(rects)
        invalidate()
    }

    fun addHighlight(rect: RectF) {
        highlights.add(rect)
        invalidate()
    }

    fun clearHighlight() {
        highlights.clear()
        invalidate()
    }

    fun clearHighlights() {
        highlights.clear()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        for (rect in highlights) {
            canvas.drawRect(rect, paint)
        }
    }
}
