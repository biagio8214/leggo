package com.example.leggo

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class TocAdapter(private val chapters: List<Chapter>, private val onClick: (Int) -> Unit) : RecyclerView.Adapter<TocAdapter.ViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val v = LayoutInflater.from(parent.context).inflate(android.R.layout.simple_list_item_1, parent, false)
        return ViewHolder(v)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val ch = chapters.getOrNull(position)
        holder.title.text = ch?.title ?: "Capitolo ${position + 1}"
        holder.itemView.setOnClickListener { onClick(position) }
    }

    override fun getItemCount(): Int = chapters.size

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(android.R.id.text1)
    }
}
