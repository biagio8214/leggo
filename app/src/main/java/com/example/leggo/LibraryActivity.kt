package com.example.leggo

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class LibraryActivity : AppCompatActivity() {

    private lateinit var recyclerLibrary: RecyclerView
    private val booksList = mutableListOf<BookItem>()

    data class BookItem(val title: String, val uri: Uri, val path: String, var coverPath: String? = null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_library)

        recyclerLibrary = findViewById(R.id.recyclerLibrary)
        // Griglia 3 colonne
        recyclerLibrary.layoutManager = GridLayoutManager(this, 3)

        checkPermissionsAndLoad()
    }

    private fun checkPermissionsAndLoad() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            loadBooksFromDownloads()
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), 100)
            } else {
                loadBooksFromDownloads()
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            loadBooksFromDownloads()
        } else {
            Toast.makeText(this, "Permesso necessario per leggere la biblioteca", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadBooksFromDownloads() {
        booksList.clear()
        val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        
        CoroutineScope(Dispatchers.IO).launch {
            if (downloadDir.exists() && downloadDir.isDirectory) {
                val files = downloadDir.listFiles { file -> 
                    file.isFile && (file.name.endsWith(".pdf", true) || file.name.endsWith(".epub", true) || file.name.endsWith(".txt", true))
                }
                
                files?.forEach { file ->
                    val uri = Uri.fromFile(file)
                    // Genera cover cache se non esiste
                    val coverFile = File(cacheDir, "cover_lib_${file.name.hashCode()}.png")
                    if (!coverFile.exists() && file.name.endsWith(".pdf", true)) {
                        generatePdfCover(file, coverFile)
                    }
                    
                    val coverPath = if (coverFile.exists()) coverFile.absolutePath else null
                    booksList.add(BookItem(file.name, uri, file.absolutePath, coverPath))
                }
            }
            
            withContext(Dispatchers.Main) {
                recyclerLibrary.adapter = LibraryAdapter(booksList) { book ->
                    openBook(book)
                }
                if (booksList.isEmpty()) {
                    Toast.makeText(this@LibraryActivity, "Nessun libro trovato in Download.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    
    private fun generatePdfCover(pdfFile: File, outFile: File) {
        try {
            val fileDescriptor = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = PdfRenderer(fileDescriptor)
            if (renderer.pageCount > 0) {
                val page = renderer.openPage(0)
                val bitmap = Bitmap.createBitmap(page.width / 4, page.height / 4, Bitmap.Config.ARGB_8888)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                page.close()
                FileOutputStream(outFile).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 90, out)
                }
            }
            renderer.close()
            fileDescriptor.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    private fun openBook(book: BookItem) {
        try {
            // Aggiorna recenti
            BookUtils.addRecentBook(this, book.uri, book.title)
            
            val intent = Intent(this, PdfReaderActivity::class.java)
            intent.data = book.uri
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Errore apertura libro", Toast.LENGTH_SHORT).show()
        }
    }

    class LibraryAdapter(private val books: List<BookItem>, private val onItemClick: (BookItem) -> Unit) : RecyclerView.Adapter<LibraryAdapter.BookViewHolder>() {
        
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
            
            if (book.coverPath != null) {
                holder.imgCover.load(File(book.coverPath!!)) {
                    placeholder(R.drawable.leggo)
                    error(R.drawable.leggo)
                    crossfade(true)
                }
            } else {
                holder.imgCover.setImageResource(R.drawable.leggo)
            }
            
            holder.itemView.setOnClickListener { onItemClick(book) }
        }

        override fun getItemCount() = books.size
    }
}
