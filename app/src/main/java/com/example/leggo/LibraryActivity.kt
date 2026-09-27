package com.example.leggo

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.example.leggo.models.Book
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
        adapter = LibraryAdapter(emptyList(), { openBook(it) }, { book, view -> showBookOptions(book, view) })
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

    private fun openBook(book: Book) {
        lifecycleScope.launch {
            val uri = Uri.parse(book.uriString)
            BookUtils.addOrUpdateBook(this@LibraryActivity, uri, book.title)

            val ext = book.title.lowercase().substringAfterLast(".", "")
            val mime = contentResolver.getType(uri) ?: ""
            
            val useEpubReader = ext in listOf("epub", "txt", "html", "htm", "fb2") || 
                              mime.contains("epub") || mime.contains("text/plain") || mime.contains("fb2")
            
            val targetActivity = if (useEpubReader) EpubReaderActivity::class.java else PdfReaderActivity::class.java

            val intent = Intent(this@LibraryActivity, targetActivity).apply {
                data = uri
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(intent)
        }
    }

    private fun showBookOptions(book: Book, anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add("Rinomina")
        popup.menu.add("Sposta nel cestino")
        popup.menu.add("Elimina dal dispositivo")
        
        popup.setOnMenuItemClickListener { item ->
            when (item.title) {
                "Rinomina" -> showRenameDialog(book)
                "Sposta nel cestino" -> showMoveToTrashDialog(book)
                "Elimina dal dispositivo" -> showDeletePermanentlyDialog(book)
            }
            true
        }
        popup.show()
    }

    private fun showRenameDialog(book: Book) {
        val input = EditText(this).apply { setText(book.title.substringBeforeLast(".")) }
        AlertDialog.Builder(this)
            .setTitle("Rinomina libro")
            .setView(input)
            .setPositiveButton("Rinomina") { _, _ ->
                lifecycleScope.launch {
                    val success = BookUtils.renameBook(this@LibraryActivity, book, input.text.toString())
                    if (success) loadLibrary() else Toast.makeText(this@LibraryActivity, "Errore rinomina", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun showMoveToTrashDialog(book: Book) {
        AlertDialog.Builder(this)
            .setTitle(R.string.move_to_trash_title)
            .setMessage(getString(R.string.move_to_trash_message, book.title))
            .setPositiveButton(R.string.move) { _, _ ->
                lifecycleScope.launch {
                    BookUtils.moveToTrash(this@LibraryActivity, book)
                    loadLibrary()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showDeletePermanentlyDialog(book: Book) {
        AlertDialog.Builder(this)
            .setTitle("Elimina definitivamente")
            .setMessage("Vuoi davvero cancellare il file fisico?")
            .setPositiveButton("Elimina") { _, _ ->
                lifecycleScope.launch {
                    val success = BookUtils.deleteBookFile(this@LibraryActivity, book)
                    if (success) loadLibrary() else Toast.makeText(this@LibraryActivity, "Errore eliminazione", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Annulla", null)
            .show()
    }
    
    class LibraryAdapter(
        private var books: List<Book>, 
        private val onItemClick: (Book) -> Unit,
        private val onLongClick: (Book, View) -> Unit
    ) : RecyclerView.Adapter<LibraryAdapter.BookViewHolder>() {
        
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
            holder.tvTitle.setTextColor(Color.parseColor("#FFD700"))
            holder.tvTitle.paint.isFakeBoldText = true
            
            val coverPath = book.coverPath
            if (coverPath != null && File(coverPath).exists()) {
                holder.imgCover.load(File(coverPath)) { placeholder(R.drawable.leggo).error(R.drawable.leggo) }
            } else {
                holder.imgCover.setImageResource(R.drawable.leggo)
            }
            holder.itemView.setOnClickListener { onItemClick(book) }
            holder.itemView.setOnLongClickListener {
                onLongClick(book, holder.itemView)
                true
            }
        }

        override fun getItemCount() = books.size
        
        fun updateBooks(newBooks: List<Book>) {
            this.books = newBooks
            notifyDataSetChanged()
        }
    }
}
