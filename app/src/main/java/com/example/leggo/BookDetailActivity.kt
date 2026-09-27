package com.example.leggo

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import coil.load
import kotlinx.coroutines.launch
import java.io.File

class BookDetailActivity : BaseActivity() {

    private var downloadId: Long = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_book_detail)

        val title = intent.getStringExtra("title")
        val author = intent.getStringExtra("author")
        val coverUrl = intent.getStringExtra("cover_url")
        val downloadUrl = intent.getStringExtra("download_url")
        val language = intent.getStringExtra("language")

        val imgCover: ImageView = findViewById(R.id.imgCover)
        val tvTitle: TextView = findViewById(R.id.tvTitle)
        val tvAuthor: TextView = findViewById(R.id.tvAuthor)
        val tvLanguage: TextView = findViewById(R.id.tvLanguage)
        val btnDownload: Button = findViewById(R.id.btnDownload)

        tvTitle.text = title
        tvAuthor.text = author ?: "Autore Sconosciuto"
        tvLanguage.text = "Lingua: ${language ?: "N/D"}"

        imgCover.load(coverUrl) { 
            crossfade(true)
            placeholder(R.drawable.leggo)
            error(R.drawable.leggo)
        }

        btnDownload.setOnClickListener {
            if (!downloadUrl.isNullOrBlank()) {
                // Qui assumiamo l'estensione dall'URL o default epub se non specificato nell'intent
                startDirectDownload(downloadUrl, title ?: "Libro")
            } else {
                Toast.makeText(this, "Download non disponibile per questo libro", Toast.LENGTH_SHORT).show()
            }
        }
        
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(onDownloadComplete, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(onDownloadComplete, filter)
        }
    }
    
    private fun startDirectDownload(url: String, title: String) {
        try {
            // Migliore rilevamento estensione
            val extension = when {
                url.lowercase().endsWith(".pdf") -> ".pdf"
                url.lowercase().endsWith(".epub") -> ".epub"
                else -> ".epub"
            }
            
            val sanitizedTitle = title.replace(Regex("[^a-zA-Z0-9]"), "_")
            val fileName = "$sanitizedTitle$extension"
            
            val request = DownloadManager.Request(Uri.parse(url))
                .setTitle(title)
                .setDescription("Scaricando con Leggo...")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                .addRequestHeader("User-Agent", "Mozilla/5.0")
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
                                // Controllo dimensione file in modo moderno
                                val size = try {
                                    contentResolver.openFileDescriptor(Uri.parse(uriString), "r")?.use { it.statSize } ?: 0
                                } catch (e: Exception) { 0 }

                                if (size > 100) {
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

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(onDownloadComplete) } catch (e: Exception) {}
    }
}
