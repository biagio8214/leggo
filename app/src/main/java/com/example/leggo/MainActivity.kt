package com.example.leggo

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.example.leggo.models.Book
import com.google.android.material.navigation.NavigationView
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : BaseActivity() {

    private val viewModel: MainViewModel by viewModels()

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var recyclerRecent: RecyclerView
    private lateinit var adapter: RecentBooksAdapter

    private val pickFileLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val title = FileUtils.getFileName(this, it) ?: "Libro"
            openBook(Book(title, it.toString(), null, System.currentTimeMillis(), false))
        }
    }

    private val pickDirectoryLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let {
            contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            Toast.makeText(this, "Scansione cartella avviata...", Toast.LENGTH_SHORT).show()
            viewModel.scanDirectory(it) {
                Toast.makeText(this@MainActivity, "Scansione completata!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home_final)

        drawerLayout = findViewById(R.id.drawerLayout)
        recyclerRecent = findViewById(R.id.recyclerRecentBooks)
        
        recyclerRecent.layoutManager = GridLayoutManager(this, 3)
        adapter = RecentBooksAdapter(emptyList(), { openBook(it) }, { book, view -> showBookOptions(book, view) })
        recyclerRecent.adapter = adapter

        findViewById<View>(R.id.btnMenu).setOnClickListener { drawerLayout.openDrawer(GravityCompat.START) }
        
        val btnAdd = findViewById<View>(R.id.btnAddBook)
        btnAdd.setOnClickListener { pickFileLauncher.launch(arrayOf("*/*")) }
        btnAdd.setOnLongClickListener { 
            pickDirectoryLauncher.launch(null)
            true 
        }

        findViewById<View>(R.id.btnSettings).setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        findViewById<View>(R.id.btnLibrary).setOnClickListener { startActivity(Intent(this, LibraryActivity::class.java)) }
        
        findViewById<View>(R.id.btnSearch).setOnClickListener { 
            val query = findViewById<EditText>(R.id.etSearchBook).text.toString()
            if (query.isNotBlank()) {
                val intent = Intent(this, SearchBooksActivity::class.java)
                intent.putExtra("QUERY", query)
                startActivity(intent)
            } else {
                Toast.makeText(this, "Inserisci un titolo per cercare", Toast.LENGTH_SHORT).show()
            }
        }

        setupNavigation()
        observeViewModel()
    }

    override fun onResume() {
        super.onResume()
        viewModel.loadRecentBooks()
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.recentBooks.collectLatest { books ->
                    adapter.updateBooks(books)
                }
            }
        }
    }

    private fun setupNavigation() {
        val navView: NavigationView = findViewById(R.id.navView)
        navView.setNavigationItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_library -> startActivity(Intent(this, LibraryActivity::class.java))
                R.id.nav_settings -> startActivity(Intent(this, SettingsActivity::class.java))
                R.id.nav_trash -> startActivity(Intent(this, TrashActivity::class.java))
                R.id.nav_credits -> startActivity(Intent(this, CreditsActivity::class.java))
                R.id.nav_thanks -> startActivity(Intent(this, ThanksActivity::class.java))
            }
            drawerLayout.closeDrawers()
            true
        }
    }

    private fun openBook(book: Book) {
        lifecycleScope.launch {
            if (book.uriString.isNullOrEmpty()) {
                Toast.makeText(this@MainActivity, "Errore: URI del libro non valido", Toast.LENGTH_SHORT).show()
                return@launch
            }
            
            val uri = Uri.parse(book.uriString)
            BookUtils.addOrUpdateBook(this@MainActivity, uri, book.title)
            
            val ext = book.title.lowercase().substringAfterLast(".", "")
            val mime = contentResolver.getType(uri) ?: ""
            
            val useEpubReader = ext in listOf("epub", "txt", "html", "htm", "fb2") || 
                              mime.contains("epub") || mime.contains("text/plain") || mime.contains("fb2")
            
            val target = if (useEpubReader) EpubReaderActivity::class.java else PdfReaderActivity::class.java
            
            val intent = Intent(this@MainActivity, target).apply {
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
                viewModel.renameBook(book, input.text.toString()) { success ->
                    if (!success) {
                        Toast.makeText(this@MainActivity, "Errore rinomina", Toast.LENGTH_SHORT).show()
                    }
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
                viewModel.moveToTrash(book)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showDeletePermanentlyDialog(book: Book) {
        AlertDialog.Builder(this)
            .setTitle("Elimina definitivamente")
            .setMessage("Vuoi davvero cancellare il file fisico?")
            .setPositiveButton("Elimina") { _, _ ->
                viewModel.deleteBookFile(book) { success ->
                    if (!success) {
                        Toast.makeText(this@MainActivity, "Errore eliminazione", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    class RecentBooksAdapter(
        private var books: List<Book>,
        private val onClick: (Book) -> Unit,
        private val onLongClick: (Book, View) -> Unit
    ) : RecyclerView.Adapter<RecentBooksAdapter.ViewHolder>() {
        inner class ViewHolder(v: View) : RecyclerView.ViewHolder(v) {
            val imgCover: ImageView = v.findViewById(R.id.imgCover)
            val tvTitle: TextView = v.findViewById(R.id.tvTitle)
        }
        override fun onCreateViewHolder(p: ViewGroup, t: Int) = ViewHolder(LayoutInflater.from(p.context).inflate(R.layout.item_book_cover, p, false))
        override fun onBindViewHolder(h: ViewHolder, p: Int) {
            val book = books[p]
            h.tvTitle.text = book.title
            h.tvTitle.setTextColor(Color.parseColor("#FFD700"))
            h.tvTitle.paint.isFakeBoldText = true
            
            var coverPath = book.coverPath
            if (coverPath.isNullOrEmpty()) {
                try {
                    val bookId = Uri.parse(book.uriString).lastPathSegment?.replace(Regex("[^a-zA-Z0-9]"), "_") ?: "ebook"
                    val coverFile = File(h.itemView.context.filesDir, "covers/$bookId.jpg")
                    if (coverFile.exists()) {
                        coverPath = coverFile.absolutePath
                    }
                } catch (e: Exception) { e.printStackTrace() }
            }

            if (!coverPath.isNullOrEmpty()) {
                h.imgCover.load(coverPath) { 
                    placeholder(R.drawable.leggo)
                    error(R.drawable.leggo) 
                }
            } else {
                h.imgCover.setImageResource(R.drawable.leggo)
            }
            h.itemView.setOnClickListener { onClick(book) }
            h.itemView.setOnLongClickListener { 
                onLongClick(book, h.itemView)
                true 
            }
        }
        override fun getItemCount() = books.size
        fun updateBooks(newBooks: List<Book>) { books = newBooks; notifyDataSetChanged() }
    }
}
