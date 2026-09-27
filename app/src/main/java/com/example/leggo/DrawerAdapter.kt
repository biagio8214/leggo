package com.example.leggo

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class DrawerAdapter(
    private val chapters: List<Chapter>,
    private val onActionClick: (Action) -> Unit,
    private val onChapterClick: (Int) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    enum class Action { SETTINGS, SAVE, LOAD }

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_ITEM = 1
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return if (viewType == TYPE_HEADER) {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_drawer_menu, parent, false)
            HeaderViewHolder(v)
        } else {
            val v = LayoutInflater.from(parent.context).inflate(android.R.layout.simple_list_item_1, parent, false)
            ItemViewHolder(v)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (holder is HeaderViewHolder) {
            holder.btnSettings.setOnClickListener { onActionClick(Action.SETTINGS) }
            holder.btnSave.setOnClickListener { onActionClick(Action.SAVE) }
            holder.btnLoad.setOnClickListener { onActionClick(Action.LOAD) }
        } else if (holder is ItemViewHolder) {
            val realPos = position - 1
            val chapter = chapters[realPos]
            holder.textView.text = chapter.title
            holder.itemView.setOnClickListener { onChapterClick(realPos) }
        }
    }

    override fun getItemCount(): Int = chapters.size + 1

    override fun getItemViewType(position: Int): Int {
        return if (position == 0) TYPE_HEADER else TYPE_ITEM
    }

    class HeaderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val btnSettings: View = view.findViewById(R.id.menuSettings)
        val btnSave: View = view.findViewById(R.id.menuSave)
        val btnLoad: View = view.findViewById(R.id.menuLoad)
    }

    class ItemViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val textView: TextView = view.findViewById(android.R.id.text1)
    }
}
