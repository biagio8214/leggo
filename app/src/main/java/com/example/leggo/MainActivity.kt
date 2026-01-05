package com.example.leggo

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : BaseActivity() {

    private lateinit var recyclerRecent: RecyclerView
    private lateinit var tvRecentLabel: TextView

    private val openFileLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let {
            lifecycleScope.launch {
                val title = it.lastPathSegment?.split("/")?.last() ?: "Documento"
                BookUtils.addOrUpdateBook(this@MainActivity, it, title)
                loadRecentBooks()
                
                val intent = Intent(this@MainActivity, PdfReaderActivity::class.java).apply {
                    data = it
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(intent)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home_final)

        recyclerRecent = findViewById(R.id.recyclerRecentBooks)
        tvRecentLabel = findViewById(R.id.tvRecentLabel)
        recyclerRecent.layoutManager = GridLayoutManager(this, 3)

        findViewById<ImageButton>(R.id.btnAddBook).setOnClickListener {
            openFileLauncher.launch(arrayOf("application/pdf", "application/epub+zip", "text/plain"))
        }

        findViewById<ImageButton>(R.id.btnLibrary).setOnClickListener {
            startActivity(Intent(this, LibraryActivity::class.java))
        }
        
        findViewById<ImageButton>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        
        findViewById<ImageButton>(R.id.btnMenu).setOnClickListener { view ->
            showPopupMenu(view)
        }
        
        val etSearch = findViewById<EditText>(R.id.etSearchBook)
        findViewById<ImageButton>(R.id.btnSearch).setOnClickListener {
            val query = etSearch.text.toString()
            if (query.isNotBlank()) {
                val intent = Intent(this, SearchBooksActivity::class.java)
                intent.putExtra("QUERY", query)
                startActivity(intent)
            } else {
                Toast.makeText(this, getString(R.string.search_books_hint), Toast.LENGTH_SHORT).show()
            }
        }
        
        loadRecentBooks()
    }
    
    private fun showPopupMenu(view: View) {
        val popup = PopupMenu(this, view)
        popup.menuInflater.inflate(R.menu.main_menu, popup.menu)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.menu_trash -> startActivity(Intent(this, TrashActivity::class.java))
                R.id.menu_copyright -> startActivity(Intent(this, CreditsActivity::class.java))
                R.id.menu_thanks -> startActivity(Intent(this, ThanksActivity::class.java))
            }
            true
        }
        popup.show()
    }
    
    override fun onResume() {
        super.onResume()
        loadRecentBooks()
    }

    private fun loadRecentBooks() {
        lifecycleScope.launch {
            val books = BookUtils.getRecentBooks(this@MainActivity)
            if (books.isNotEmpty()) {
                recyclerRecent.visibility = View.VISIBLE
                tvRecentLabel.visibility = View.VISIBLE
                recyclerRecent.adapter = RecentBookAdapter(books)
            } else {
                recyclerRecent.visibility = View.GONE
                tvRecentLabel.visibility = View.GONE
            }
        }
    }
    
    inner class RecentBookAdapter(private val books: List<BookUtils.Book>) : RecyclerView.Adapter<RecentBookAdapter.ViewHolder>() {
        
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
                holder.imgCover.load(File(book.coverPath)) { placeholder(R.drawable.leggo).error(R.drawable.leggo) }
            } else {
                holder.imgCover.setImageResource(R.drawable.leggo)
            }
            
            holder.itemView.setOnClickListener {
                val intent = Intent(this@MainActivity, PdfReaderActivity::class.java).apply {
                    data = Uri.parse(book.uriString)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(intent)
            }
            
            holder.itemView.setOnLongClickListener {
                showDeleteDialog(book)
                true
            }
        }

        override fun getItemCount() = books.size
    }
    
    private fun showDeleteDialog(book: BookUtils.Book) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.move_to_trash_title))
            .setMessage(getString(R.string.move_to_trash_message, book.title))
            .setPositiveButton(getString(R.string.move)) { _, _ ->
                lifecycleScope.launch {
                    BookUtils.moveToTrash(this@MainActivity, book)
                    loadRecentBooks()
                }
                Toast.makeText(this, getString(R.string.book_moved_to_trash), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }
}
