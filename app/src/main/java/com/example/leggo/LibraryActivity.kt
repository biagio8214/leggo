package com.example.leggo

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import kotlinx.coroutines.launch
import java.io.File

class LibraryActivity : BaseActivity() {

    private lateinit var recyclerLibrary: RecyclerView
    private lateinit var adapter: LibraryAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_library)

        recyclerLibrary = findViewById(R.id.recyclerLibrary)
        recyclerLibrary.layoutManager = GridLayoutManager(this, 3)
        // Inizializza l'adapter con una lista vuota
        adapter = LibraryAdapter(emptyList()) { openBook(it) }
        recyclerLibrary.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        loadLibrary()
    }

    private fun loadLibrary() {
        lifecycleScope.launch {
            val books = BookUtils.getLibraryBooks(this@LibraryActivity)
            adapter.updateBooks(books)

            if (books.isEmpty()) {
                Toast.makeText(this@LibraryActivity, getString(R.string.library_empty), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun openBook(book: BookUtils.Book) {
        lifecycleScope.launch {
            val uri = Uri.parse(book.uriString)
            // Questa chiamata ora gestisce l'aggiornamento del timestamp e la generazione della copertina
            BookUtils.addOrUpdateBook(this@LibraryActivity, uri, book.title)

            val intent = Intent(this@LibraryActivity, PdfReaderActivity::class.java).apply {
                data = uri
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(intent)
        }
    }
    
    class LibraryAdapter(private var books: List<BookUtils.Book>, private val onItemClick: (BookUtils.Book) -> Unit) : RecyclerView.Adapter<LibraryAdapter.BookViewHolder>() {
        inner class BookViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val imgCover: ImageView = itemView.findViewById(R.id.imgCover)
            val tvTitle: TextView = itemView.findViewById(R.id.tvTitle)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BookViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_book_cover, parent, false)
            return BookViewHolder(view)
        }

        override fun onBindViewHolder(holder: BookViewHolder, position: Int) {
            val book = books[position]
            holder.tvTitle.text = book.title
            if (book.coverPath != null && File(book.coverPath!!).exists()) {
                holder.imgCover.load(File(book.coverPath)) { placeholder(R.drawable.leggo).error(R.drawable.leggo) }
            } else {
                holder.imgCover.setImageResource(R.drawable.leggo)
            }
            holder.itemView.setOnClickListener { onItemClick(book) }
        }

        override fun getItemCount() = books.size
        
        // Funzione per aggiornare i dati dell'adapter
        fun updateBooks(newBooks: List<BookUtils.Book>) {
            this.books = newBooks
            notifyDataSetChanged()
        }
    }
}
