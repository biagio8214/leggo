package com.example.leggo

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import java.io.File

class TrashActivity : AppCompatActivity() {

    private lateinit var recyclerTrash: RecyclerView
    private lateinit var adapter: TrashAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_trash)

        recyclerTrash = findViewById(R.id.recyclerTrash)
        recyclerTrash.layoutManager = GridLayoutManager(this, 3)
        
        loadTrash()
    }

    private fun loadTrash() {
        val trashBooks = BookUtils.getTrashBooks(this)
        adapter = TrashAdapter(trashBooks)
        recyclerTrash.adapter = adapter
        
        if (trashBooks.isEmpty()) {
            Toast.makeText(this, "Il cestino è vuoto", Toast.LENGTH_SHORT).show()
        }
    }
    
    inner class TrashAdapter(private val books: List<BookUtils.RecentBook>) : RecyclerView.Adapter<TrashAdapter.ViewHolder>() {
        
        inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val imgCover: ImageView = itemView.findViewById(R.id.imgCover)
            val tvTitle: TextView = itemView.findViewById(R.id.tvTitle)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_book_cover, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val book = books[position]
            holder.tvTitle.text = book.title
            
            if (book.coverPath != null && File(book.coverPath).exists()) {
                holder.imgCover.load(File(book.coverPath)) {
                    placeholder(R.drawable.leggo)
                    crossfade(true)
                }
            } else {
                holder.imgCover.setImageResource(R.drawable.leggo)
            }
            
            holder.itemView.setOnLongClickListener {
                showRestoreDialog(book)
                true
            }
        }

        override fun getItemCount() = books.size
    }
    
    private fun showRestoreDialog(book: BookUtils.RecentBook) {
        AlertDialog.Builder(this)
            .setTitle("Gestione Cestino")
            .setMessage("Vuoi ripristinare '${book.title}' o eliminarlo definitivamente?")
            .setPositiveButton("Ripristina") { _, _ ->
                BookUtils.restoreFromTrash(this, book)
                loadTrash()
                Toast.makeText(this, "Libro ripristinato", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Elimina per sempre") { _, _ ->
                BookUtils.deletePermanently(this, book)
                loadTrash()
                Toast.makeText(this, "Libro eliminato", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("Annulla", null)
            .show()
    }
}
