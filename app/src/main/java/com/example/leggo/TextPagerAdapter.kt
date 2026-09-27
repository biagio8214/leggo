package com.example.leggo

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.util.Log
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class TextPagerAdapter(
    private val pages: List<String>,
    private val isLandscape: Boolean,
    private val onWordClick: (adapterPos: Int, offset: Int) -> Unit,
    private val onVerticalSwipe: () -> Unit
) : RecyclerView.Adapter<TextPagerAdapter.TextPageViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TextPageViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        val view = inflater.inflate(R.layout.item_text_page, parent, false)
        return TextPageViewHolder(view)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onBindViewHolder(holder: TextPageViewHolder, position: Int) {
        // Usiamo adapterPosition per massima compatibilità
        val adapterPos = if (holder.adapterPosition != RecyclerView.NO_POSITION) holder.adapterPosition else position

        // Apply settings from SharedPreferences
        val settings = holder.itemView.context.getSharedPreferences("LeggoSettings", Context.MODE_PRIVATE)
        val textSize = settings.getInt("text_size", 18).toFloat()
        val theme = settings.getString("reader_theme", "Giorno (Bianco)")

        val (textColor, bgColor) = when (theme) {
            "Pergamena" -> Color.BLACK to Color.parseColor("#D2B48C")
            "Notte (Nero)" -> Color.WHITE to Color.BLACK
            else -> Color.BLACK to Color.WHITE
        }

        // Apply to left (and right) TextViews
        holder.textView.textSize = textSize
        holder.textView.setTextColor(textColor)
        holder.textView.setBackgroundColor(bgColor)

        val p1Index = if (isLandscape) adapterPos * 2 else adapterPos
        holder.textView.text = pages.getOrNull(p1Index) ?: ""

        if (isLandscape) {
            holder.textViewRight?.textSize = textSize
            holder.textViewRight?.setTextColor(textColor)
            holder.textViewRight?.setBackgroundColor(bgColor)

            val p2Index = p1Index + 1
            holder.textViewRight?.text = pages.getOrNull(p2Index) ?: ""
            holder.pageSeparator.visibility = View.VISIBLE
            holder.textViewRight?.visibility = View.VISIBLE
        } else {
            holder.pageSeparator.visibility = View.GONE
            holder.textViewRight?.visibility = View.GONE
        }

        // --- GESTURE DETECTION --- //
        val gestureDetector = GestureDetector(holder.itemView.context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                val touchX = e.x
                var detectedPage = if (isLandscape) adapterPos * 2 else adapterPos
                var isRight = false

                if (isLandscape && touchX > holder.itemView.width / 2f) {
                    detectedPage += 1
                    isRight = true
                }

                val tv = if (isRight) holder.textViewRight else holder.textView
                tv?.let { textView ->
                    val loc = IntArray(2)
                    textView.getLocationOnScreen(loc)
                    val localX = e.rawX - loc[0]
                    val localY = e.rawY - loc[1]

                    if (localX < 0f || localY < 0f || localX > textView.width || localY > textView.height) return false

                    val offset = try {
                        textView.getOffsetForPosition(localX, localY)
                    } catch (ex: Exception) {
                        -1
                    }

                    if (offset >= 0) {
                        onWordClick(adapterPos, offset)
                        return true
                    }
                }
                return false
            }

            override fun onDown(e: MotionEvent): Boolean = true

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (e1 == null) return false
                val diffY = e2.y - e1.y
                if (kotlin.math.abs(diffY) > 50 && velocityY < -100) {
                    onVerticalSwipe()
                    return true
                }
                return false
            }
        })
        
        holder.itemView.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            true
        }
    }

    override fun getItemCount(): Int = if (isLandscape) (pages.size + 1) / 2 else pages.size

    class TextPageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val textView: TextView = itemView.findViewById(R.id.pageTextView)
        val pageSeparator: View = itemView.findViewById(R.id.pageSeparator)
        val textViewRight: TextView? = itemView.findViewById(R.id.pageTextViewRight)
    }
}
