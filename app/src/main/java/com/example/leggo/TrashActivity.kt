package com.example.leggo

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch

class TrashActivity : BaseActivity() {

    private lateinit var recyclerTrash: RecyclerView
    private lateinit var adapter: TrashAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_trash)

        recyclerTrash = findViewById(R.id.recyclerTrash)
        recyclerTrash.layoutManager = LinearLayoutManager(this)
        adapter = TrashAdapter(emptyList()) { book, action ->
            handleBookAction(book, action)
        }
        recyclerTrash.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        loadTrash()
    }

    private fun loadTrash() {
        lifecycleScope.launch {
            val books = BookUtils.getTrashBooks(this@TrashActivity)
            adapter.updateBooks(books)
            if (books.isEmpty()) {
                Toast.makeText(this@TrashActivity, getString(R.string.trash_empty), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun handleBookAction(book: BookUtils.Book, action: String) {
        when (action) {
            "RESTORE" -> {
                lifecycleScope.launch {
                    BookUtils.restoreFromTrash(this@TrashActivity, book)
                    loadTrash()
                    runOnUiThread { Toast.makeText(this@TrashActivity, "Libro ripristinato", Toast.LENGTH_SHORT).show() }
                }
            }
            "DELETE" -> {
                showDeleteConfirmation(book)
            }
        }
    }
    
    private fun showDeleteConfirmation(book: BookUtils.Book) {
        AlertDialog.Builder(this)
            .setTitle("Elimina Definitivamente")
            .setMessage("Sei sicuro di voler eliminare definitivamente '${book.title}'? L\'azione non è reversibile.")
            .setPositiveButton("Elimina") { _, _ ->
                lifecycleScope.launch {
                    BookUtils.deletePermanently(this@TrashActivity, book)
                    loadTrash()
                    runOnUiThread { Toast.makeText(this@TrashActivity, "Libro eliminato", Toast.LENGTH_SHORT).show() }
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }
}

class TrashAdapter(private var books: List<BookUtils.Book>, private val onAction: (BookUtils.Book, String) -> Unit) : RecyclerView.Adapter<TrashAdapter.ViewHolder>() {
    
    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvTitle: TextView = itemView.findViewById(R.id.tvTrashTitle)
        val btnRestore: Button = itemView.findViewById(R.id.btnRestore)
        val btnDelete: Button = itemView.findViewById(R.id.btnDelete)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_trash_book, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val book = books[position]
        holder.tvTitle.text = book.title
        holder.btnRestore.setOnClickListener { onAction(book, "RESTORE") }
        holder.btnDelete.setOnClickListener { onAction(book, "DELETE") }
    }

    override fun getItemCount() = books.size
    
    fun updateBooks(newBooks: List<BookUtils.Book>) {
        this.books = newBooks
        notifyDataSetChanged()
    }
}