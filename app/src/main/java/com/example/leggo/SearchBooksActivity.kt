package com.example.leggo

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

class SearchBooksActivity : BaseActivity() {

    private lateinit var etQuery: EditText
    private lateinit var btnSearch: Button
    private lateinit var recycler: RecyclerView
    private lateinit var progressBar: ProgressBar
    private lateinit var tvNoResults: TextView

    private var bookAdapter: BookAdapter? = null
    private val allBooks = mutableListOf<BookSearchManager.BookResult>()
    private val pendingRequests = AtomicInteger(0)
    private var downloadId: Long = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_search_books)

        etQuery = findViewById(R.id.etSearchQuery)
        btnSearch = findViewById(R.id.btnPerformSearch)
        recycler = findViewById(R.id.recyclerSearchResults)
        progressBar = findViewById(R.id.progressBar)
        tvNoResults = findViewById(R.id.tvNoResults)

        recycler.layoutManager = LinearLayoutManager(this)
        bookAdapter = BookAdapter(allBooks)
        recycler.adapter = bookAdapter

        val initialQuery = intent.getStringExtra("QUERY")
        if (!initialQuery.isNullOrEmpty()) {
            etQuery.setText(initialQuery)
            performSearch(initialQuery)
        }

        btnSearch.setOnClickListener {
            val query = etQuery.text.toString()
            if (query.isNotBlank()) performSearch(query)
        }
        
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(onDownloadComplete, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(onDownloadComplete, filter)
        }
    }
    
    private fun performSearch(query: String) {
        progressBar.visibility = View.VISIBLE
        recycler.visibility = View.GONE
        tvNoResults.visibility = View.GONE
        allBooks.clear()
        bookAdapter?.notifyDataSetChanged()
        
        pendingRequests.set(1) 
        lifecycleScope.launch {
            val results = BookSearchManager.searchLegalBooks(query)
            addResults(results)
            checkProgress()
        }
    }
    
    @Synchronized
    private fun addResults(newBooks: List<BookSearchManager.BookResult>) {
        val startPos = allBooks.size
        allBooks.addAll(newBooks)
        runOnUiThread { bookAdapter?.notifyItemRangeInserted(startPos, newBooks.size) }
    }
    
    private fun checkProgress() {
        if (pendingRequests.decrementAndGet() <= 0) {
            runOnUiThread {
                progressBar.visibility = View.GONE
                if (allBooks.isEmpty()) tvNoResults.visibility = View.VISIBLE else recycler.visibility = View.VISIBLE
            }
        }
    }
    
    private fun startDirectDownload(url: String, title: String, format: String?) {
        try {
            // Determina l'estensione corretta basandosi sul formato dichiarato o sull'URL
            val extension = when {
                format?.lowercase()?.contains("pdf") == true -> ".pdf"
                format?.lowercase()?.contains("epub") == true -> ".epub"
                url.lowercase().endsWith(".pdf") -> ".pdf"
                else -> ".epub" // Default
            }
            
            val sanitizedTitle = title.replace(Regex("[^a-zA-Z0-9]"), "_")
            val fileName = "$sanitizedTitle$extension"
            
            val request = DownloadManager.Request(Uri.parse(url))
                .setTitle(title)
                .setDescription("Scaricando con Leggo...")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                .addRequestHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(true)

            val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            downloadId = dm.enqueue(request)
            Toast.makeText(this, "Download avviato...", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Errore: impossibile avviare il download.", Toast.LENGTH_SHORT).show()
        }
    }
    
    private val onDownloadComplete = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
            if (downloadId == id && id != -1L) {
                val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                val query = DownloadManager.Query().setFilterById(id)
                val cursor = dm.query(query)
                if (cursor.moveToFirst()) {
                    val statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                    if (statusIndex != -1 && cursor.getInt(statusIndex) == DownloadManager.STATUS_SUCCESSFUL) {
                        val uriIndex = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
                        if (uriIndex != -1) {
                            val uriString = cursor.getString(uriIndex)
                            val titleIndex = cursor.getColumnIndex(DownloadManager.COLUMN_TITLE)
                            val finalTitle = if (titleIndex != -1) cursor.getString(titleIndex) else "Libro"
                            
                            if (uriString != null) {
                                val size = try {
                                    contentResolver.openFileDescriptor(Uri.parse(uriString), "r")?.use { it.statSize } ?: 0
                                } catch (e: Exception) { 0 }

                                // Aumentato limite a 5KB per evitare di salvare pagine di errore HTML come libri
                                if (size > 5000) {
                                    Toast.makeText(context, "Libro pronto in Biblioteca!", Toast.LENGTH_SHORT).show()
                                    lifecycleScope.launch {
                                        BookUtils.addOrUpdateBook(context, Uri.parse(uriString), finalTitle)
                                    }
                                } else {
                                    Toast.makeText(context, "Errore: il sito ha inviato un file non valido.", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    } else {
                        Toast.makeText(context, "Download fallito. Riprova più tardi.", Toast.LENGTH_SHORT).show()
                    }
                }
                cursor.close()
            }
        }
    }
    
    inner class BookAdapter(private val books: List<BookSearchManager.BookResult>) : RecyclerView.Adapter<BookAdapter.BookViewHolder>() {
        inner class BookViewHolder(v: View) : RecyclerView.ViewHolder(v) {
            val img: ImageView = v.findViewById(R.id.imgBookCover)
            val title: TextView = v.findViewById(R.id.tvBookTitle)
            val author: TextView = v.findViewById(R.id.tvBookAuthor)
            val source: TextView = v.findViewById(R.id.tvBookDescription)
            val action: Button = v.findViewById(R.id.btnBookAction)
        }
        override fun onCreateViewHolder(p: ViewGroup, t: Int) = BookViewHolder(LayoutInflater.from(p.context).inflate(R.layout.item_book_search, p, false))
        override fun onBindViewHolder(h: BookViewHolder, p: Int) {
            val b = books[p]
            h.title.text = b.title
            h.author.text = b.authors
            h.source.text = "Disponibile ora"
            h.source.setTextColor(Color.parseColor("#4CAF50"))
            h.img.load(b.thumbnailUrl) { 
                crossfade(true)
                placeholder(R.drawable.leggo)
                error(R.drawable.leggo) 
            }
            h.action.setOnClickListener {
                BookDetailsDialog.show(this@SearchBooksActivity, b) {
                    b.downloadUrl?.let { url ->
                        lifecycleScope.launch {
                            Toast.makeText(this@SearchBooksActivity, "Preparazione download...", Toast.LENGTH_SHORT).show()
                            startDirectDownload(url, b.title, b.format)
                        }
                    }
                }
            }
        }
        override fun getItemCount() = books.size
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(onDownloadComplete) } catch (e: Exception) {}
    }
}